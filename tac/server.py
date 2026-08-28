"""Non-blocking tic-tac-toe server for the INFO1112 protocol."""

import json
import os
import re
import selectors
import socket
import sys

import bcrypt

from game import CROSS, EMPTY, NOUGHT, create_board, player_wins, players_draw


BUFFER_SIZE = 8192
MAX_ROOMS = 256
VALID_MODES = {"PLAYER", "VIEWER"}

selector = selectors.DefaultSelector()
authenticated_users = {}
rooms = {}
user_db = {}


def _error(message: str) -> None:
    """Print a server startup/configuration error and terminate."""
    print(message, file=sys.stderr)
    raise SystemExit(1)


def load_config(config_path: str) -> dict:
    """Load and validate the server configuration file."""
    if not os.path.exists(config_path):
        _error(f"Error: {config_path} doesn’t exist.")

    try:
        with open(config_path, "r", encoding="utf-8") as config_file:
            config = json.load(config_file)
    except (json.JSONDecodeError, OSError):
        _error(f"Error: {config_path} is not in a valid JSON format.")

    missing_keys = sorted({"port", "userDatabase"}.difference(config))
    if missing_keys:
        _error(f"Error: {config_path} missing key(s): {', '.join(missing_keys)}")

    port = config["port"]
    if isinstance(port, bool) or not isinstance(port, int) or not 1024 <= port <= 65535:
        _error("Error: port number out of range")

    config["userDatabase"] = os.path.expanduser(config["userDatabase"])
    return config


def load_user_database(user_db_path: str) -> dict:
    """Load and validate the bcrypt-backed user database."""
    if not os.path.exists(user_db_path):
        _error(f"Error: {user_db_path} doesn’t exist.")

    try:
        with open(user_db_path, "r", encoding="utf-8") as database_file:
            records = json.load(database_file)
    except (json.JSONDecodeError, OSError):
        _error(f"Error: {user_db_path} is not in a valid JSON format.")

    if not isinstance(records, list):
        _error(f"Error: {user_db_path} is not a JSON array.")

    for record in records:
        if not isinstance(record, dict) or set(record) != {"username", "password"}:
            _error(f"Error: {user_db_path} contains invalid user record formats.")

    return {record["username"]: record["password"] for record in records}


def check_credentials(username: str, password: str, database: dict) -> int:
    """Return the LOGIN acknowledgement status for the credentials."""
    if username not in database:
        return 1
    try:
        password_matches = bcrypt.checkpw(
            password.encode("utf-8"), database[username].encode("utf-8")
        )
    except (ValueError, TypeError):
        password_matches = False
    return 0 if password_matches else 2


def _send(client_socket, message: str) -> None:
    client_socket.sendall(message.encode("ascii"))


def _room_members(room: dict) -> list:
    return room["players"] + room["viewers"]


def _broadcast(room: dict, message: str) -> None:
    for member in list(_room_members(room)):
        try:
            _send(member, message)
        except OSError:
            pass


def _board_status(board: list[list[str]]) -> str:
    symbols = {EMPTY: "0", CROSS: "1", NOUGHT: "2"}
    return "".join(symbols[cell] for row in board for cell in row)


def _find_room(client_socket, room_store=None):
    room_store = rooms if room_store is None else room_store
    for room_name, room in room_store.items():
        if client_socket in _room_members(room):
            return room_name, room
    return None, None


def handle_login(client_socket, data, database=None, users=None):
    """Handle LOGIN:<username>:<password>."""
    database = user_db if database is None else database
    users = authenticated_users if users is None else users
    parts = data.split(":")
    if len(parts) != 3:
        _send(client_socket, "LOGIN:ACKSTATUS:3")
        return

    username, password = parts[1:]
    status = check_credentials(username, password, database)
    if status == 0:
        users[client_socket] = username
    _send(client_socket, f"LOGIN:ACKSTATUS:{status}")


def handle_register(client_socket, data, database=None, user_db_path=None):
    """Handle REGISTER:<username>:<password>."""
    database = user_db if database is None else database
    parts = data.split(":")
    if len(parts) != 3:
        _send(client_socket, "REGISTER:ACKSTATUS:2")
        return

    username, password = parts[1:]
    if username in database:
        _send(client_socket, "REGISTER:ACKSTATUS:1")
        return

    password_hash = bcrypt.hashpw(
        password.encode("utf-8"), bcrypt.gensalt()
    ).decode("ascii")
    database[username] = password_hash
    try:
        with open(user_db_path, "w", encoding="utf-8") as database_file:
            records = [
                {"username": name, "password": hashed_password}
                for name, hashed_password in database.items()
            ]
            json.dump(records, database_file)
    except OSError:
        database.pop(username, None)
        _send(client_socket, "REGISTER:ACKSTATUS:2")
        return

    _send(client_socket, "REGISTER:ACKSTATUS:0")


def is_valid_room_name(room_name: str) -> bool:
    """Return whether a room name meets the assignment constraints."""
    return re.fullmatch(r"[a-zA-Z0-9_\- ]{1,20}", room_name) is not None


def handle_create_room(client_socket, room_name, room_store=None, users=None):
    """Create a room and automatically add its creator as player one."""
    room_store = rooms if room_store is None else room_store
    users = authenticated_users if users is None else users
    if client_socket not in users:
        _send(client_socket, "BADAUTH")
    elif not is_valid_room_name(room_name):
        _send(client_socket, "CREATE:ACKSTATUS:1")
    elif room_name in room_store:
        _send(client_socket, "CREATE:ACKSTATUS:2")
    elif len(room_store) >= MAX_ROOMS:
        _send(client_socket, "CREATE:ACKSTATUS:3")
    else:
        room_store[room_name] = {
            "players": [client_socket],
            "viewers": [],
            "board": create_board(),
            "turn": 0,
            "started": False,
        }
        _send(client_socket, "CREATE:ACKSTATUS:0")


def handle_roomlist(client_socket, mode, room_store=None, users=None):
    """Send rooms joinable in PLAYER or VIEWER mode."""
    room_store = rooms if room_store is None else room_store
    users = authenticated_users if users is None else users
    if client_socket not in users:
        _send(client_socket, "BADAUTH")
        return
    if mode not in VALID_MODES:
        _send(client_socket, "ROOMLIST:ACKSTATUS:1")
        return

    available_rooms = [
        room_name
        for room_name, room in room_store.items()
        if (mode == "PLAYER" and len(room["players"]) < 2)
        or (mode == "VIEWER" and len(room["players"]) == 2)
    ]
    _send(client_socket, f"ROOMLIST:ACKSTATUS:0:{','.join(available_rooms)}")


def handle_join_room(client_socket, room_name, mode, room_store=None, users=None):
    """Join an existing room as a player or viewer."""
    room_store = rooms if room_store is None else room_store
    users = authenticated_users if users is None else users
    if client_socket not in users:
        _send(client_socket, "BADAUTH")
        return
    if mode not in VALID_MODES:
        _send(client_socket, "JOIN:ACKSTATUS:3")
        return
    if room_name not in room_store:
        _send(client_socket, "JOIN:ACKSTATUS:1")
        return

    room = room_store[room_name]
    if mode == "PLAYER" and len(room["players"]) >= 2:
        _send(client_socket, "JOIN:ACKSTATUS:2")
        return

    if mode == "PLAYER":
        room["players"].append(client_socket)
    else:
        room["viewers"].append(client_socket)
    _send(client_socket, "JOIN:ACKSTATUS:0")

    if mode == "VIEWER" and room["started"]:
        current_index = room["turn"]
        current_name = users[room["players"][current_index]]
        opposing_name = users[room["players"][1 - current_index]]
        _send(client_socket, f"INPROGRESS:{current_name}:{opposing_name}")
    elif mode == "PLAYER" and len(room["players"]) == 2:
        room["started"] = True
        first_name = users[room["players"][0]]
        second_name = users[room["players"][1]]
        _broadcast(room, f"BEGIN:{first_name}:{second_name}")


def handle_place(client_socket, x, y, room_store=None, users=None):
    """Apply a move and broadcast BOARDSTATUS or GAMEEND."""
    room_store = rooms if room_store is None else room_store
    users = authenticated_users if users is None else users
    room_name, room = _find_room(client_socket, room_store)
    if room is None or client_socket not in room["players"]:
        _send(client_socket, "NOROOM")
        return

    player_index = room["players"].index(client_socket)
    marker = CROSS if player_index == 0 else NOUGHT
    room["board"][y][x] = marker
    board_status = _board_status(room["board"])

    if player_wins(marker, room["board"]):
        winner = users[client_socket]
        _broadcast(room, f"GAMEEND:{board_status}:0:{winner}")
        del room_store[room_name]
    elif players_draw(room["board"]):
        _broadcast(room, f"GAMEEND:{board_status}:1")
        del room_store[room_name]
    else:
        room["turn"] = 1 - player_index
        _broadcast(room, f"BOARDSTATUS:{board_status}")


def handle_forfeit(client_socket, room_store=None, users=None):
    """End the current game with the opposing player as winner."""
    room_store = rooms if room_store is None else room_store
    users = authenticated_users if users is None else users
    room_name, room = _find_room(client_socket, room_store)
    if room is None or client_socket not in room["players"]:
        _send(client_socket, "NOROOM")
        return

    player_index = room["players"].index(client_socket)
    winner_socket = room["players"][1 - player_index]
    winner = users[winner_socket]
    _broadcast(room, f"GAMEEND:{_board_status(room['board'])}:2:{winner}")
    del room_store[room_name]


def handle_game_command(data, client_socket):
    """Validate membership and dispatch a game-related command."""
    if client_socket not in authenticated_users:
        _send(client_socket, "BADAUTH")
        return

    _, room = _find_room(client_socket)
    if room is None:
        _send(client_socket, "NOROOM")
        return
    if client_socket in room["viewers"]:
        return

    if data.startswith("PLACE:"):
        _, x_value, y_value = data.split(":")
        handle_place(client_socket, int(x_value), int(y_value))
    elif data == "FORFEIT":
        handle_forfeit(client_socket)


def handle_disconnection(connection):
    """Remove a client and treat an in-progress player departure as a forfeit."""
    room_name, room = _find_room(connection)
    username = authenticated_users.get(connection)
    if room is not None:
        if connection in room["viewers"]:
            room["viewers"].remove(connection)
        elif connection in room["players"]:
            player_index = room["players"].index(connection)
            if room["started"] and len(room["players"]) == 2:
                winner_socket = room["players"][1 - player_index]
                winner = authenticated_users.get(winner_socket, "")
                message = f"GAMEEND:{_board_status(room['board'])}:2:{winner}"
                for member in list(_room_members(room)):
                    if member is not connection:
                        try:
                            _send(member, message)
                        except OSError:
                            pass
            del rooms[room_name]

    authenticated_users.pop(connection, None)
    try:
        selector.unregister(connection)
    except (KeyError, ValueError):
        pass
    connection.close()
    if username:
        print(f"Client {username} disconnected.")


def read(connection, config):
    """Read and dispatch one protocol message from a ready client socket."""
    try:
        received = connection.recv(BUFFER_SIZE)
        if not received:
            handle_disconnection(connection)
            return
        data = received.decode("ascii")

        _, room = _find_room(connection)
        if room is not None and connection in room["viewers"]:
            return

        if data == "LOGIN" or data.startswith("LOGIN:"):
            handle_login(connection, data)
        elif data == "REGISTER" or data.startswith("REGISTER:"):
            handle_register(connection, data, user_db, config["userDatabase"])
        elif data == "CREATE" or data.startswith("CREATE:"):
            parts = data.split(":")
            if connection not in authenticated_users:
                _send(connection, "BADAUTH")
            elif len(parts) != 2:
                _send(connection, "CREATE:ACKSTATUS:4")
            else:
                handle_create_room(connection, parts[1])
        elif data == "ROOMLIST" or data.startswith("ROOMLIST:"):
            parts = data.split(":")
            if connection not in authenticated_users:
                _send(connection, "BADAUTH")
            elif len(parts) != 2:
                _send(connection, "ROOMLIST:ACKSTATUS:1")
            else:
                handle_roomlist(connection, parts[1])
        elif data == "JOIN" or data.startswith("JOIN:"):
            parts = data.split(":")
            if connection not in authenticated_users:
                _send(connection, "BADAUTH")
            elif len(parts) != 3 or parts[2] not in VALID_MODES:
                _send(connection, "JOIN:ACKSTATUS:3")
            else:
                handle_join_room(connection, parts[1], parts[2])
        elif data.startswith("PLACE:") or data == "FORFEIT":
            handle_game_command(data, connection)
    except (OSError, UnicodeDecodeError, ValueError, IndexError):
        handle_disconnection(connection)


def accept(server_socket, config):
    """Accept and register a new non-blocking client connection."""
    connection, address = server_socket.accept()
    connection.setblocking(False)
    selector.register(
        connection,
        selectors.EVENT_READ,
        lambda: read(connection, config),
    )
    print(f"Connection established with {address}")


def main(args: list[str]) -> None:
    """Start the server from a single configuration path argument."""
    if len(args) != 1:
        _error("Error: Expecting 1 argument: <server config path>.")

    config = load_config(args[0])
    user_db.clear()
    user_db.update(load_user_database(config["userDatabase"]))

    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server_socket.bind(("0.0.0.0", config["port"]))
    server_socket.listen()
    server_socket.setblocking(False)
    selector.register(
        server_socket,
        selectors.EVENT_READ,
        lambda: accept(server_socket, config),
    )
    print(f"Server listening on port {config['port']}")

    try:
        while True:
            for key, _ in selector.select():
                key.data()
    except KeyboardInterrupt:
        pass
    finally:
        selector.close()
        server_socket.close()


if __name__ == "__main__":
    main(sys.argv[1:])
