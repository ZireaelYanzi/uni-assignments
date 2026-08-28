"""Interactive client for the INFO1112 tic-tac-toe protocol."""

import re
import socket
import sys

import game


BUFFER_SIZE = 8192
VALID_MODES = {"PLAYER", "VIEWER"}
MESSAGE_START = re.compile(
    r"(?=(?:LOGIN:ACKSTATUS:|REGISTER:ACKSTATUS:|CREATE:ACKSTATUS:|"
    r"ROOMLIST:ACKSTATUS:|JOIN:ACKSTATUS:|BEGIN:|INPROGRESS:|"
    r"BOARDSTATUS:|GAMEEND:))"
)
pending_messages = {}


class ServerDisconnected(Exception):
    """Raised when the server closes the TCP connection."""


class UnknownServerMessage(Exception):
    """Raised when the server sends a message outside the protocol."""


def send_message(server_socket, message: str) -> None:
    """Encode and send one protocol message."""
    server_socket.sendall(message.encode("ascii"))


def receive_message(server_socket) -> str:
    """Receive and decode one protocol message."""
    queued_messages = pending_messages.get(server_socket)
    if queued_messages:
        return queued_messages.pop(0)
    try:
        received = server_socket.recv(BUFFER_SIZE)
    except OSError as error:
        raise ServerDisconnected from error
    if not received:
        raise ServerDisconnected
    try:
        decoded = received.decode("ascii")
    except UnicodeDecodeError as error:
        raise UnknownServerMessage from error

    messages = [part for part in MESSAGE_START.split(decoded) if part]
    if not messages:
        raise UnknownServerMessage
    if len(messages) > 1:
        pending_messages.setdefault(server_socket, []).extend(messages[1:])
    return messages[0]


def _empty_board() -> list[list[str]]:
    return [[game.EMPTY for _ in range(3)] for _ in range(3)]


def _decode_board(board_status: str) -> list[list[str]]:
    if len(board_status) != 9 or any(value not in "012" for value in board_status):
        raise UnknownServerMessage
    symbols = {"0": game.EMPTY, "1": game.CROSS, "2": game.NOUGHT}
    return [
        [symbols[value] for value in board_status[index:index + 3]]
        for index in range(0, 9, 3)
    ]


def _print_board(board_status: str) -> list[list[str]]:
    board = _decode_board(board_status)
    game.print_board(board)
    return board


def _bad_auth() -> None:
    print(
        "Error: You must be logged in to perform this action",
        file=sys.stderr,
    )


def _no_room() -> None:
    print(
        "Error: You are not currently in a room to perform this action",
        file=sys.stderr,
    )


def _unknown_message() -> None:
    print("Unknown message received from server. Exiting...")
    raise UnknownServerMessage


def handle_login_response(response: str, attempted_username: str):
    """Print a LOGIN acknowledgement and return the authenticated username."""
    if response == "BADAUTH":
        _bad_auth()
        return None
    parts = response.split(":")
    if len(parts) != 3 or parts[:2] != ["LOGIN", "ACKSTATUS"]:
        _unknown_message()
    status = parts[2]
    if status == "0":
        print(f"Welcome {attempted_username}")
        return attempted_username
    if status == "1":
        print(f"Error: User {attempted_username} not found", file=sys.stderr)
    elif status == "2":
        print(
            f"Error: Wrong password for user {attempted_username}",
            file=sys.stderr,
        )
    elif status != "3":
        _unknown_message()
    return None


def handle_register_response(response: str, attempted_username: str) -> None:
    """Print a REGISTER acknowledgement."""
    parts = response.split(":")
    if len(parts) != 3 or parts[:2] != ["REGISTER", "ACKSTATUS"]:
        _unknown_message()
    status = parts[2]
    if status == "0":
        print(f"Successfully created user account {attempted_username}")
    elif status == "1":
        print(f"Error: User {attempted_username} already exists", file=sys.stderr)
    elif status != "2":
        _unknown_message()


def handle_roomlist_response(response: str, mode: str) -> None:
    """Print the response to ROOMLIST:<mode>."""
    parts = response.split(":", 3)
    if len(parts) < 3 or parts[:2] != ["ROOMLIST", "ACKSTATUS"]:
        if response == "BADAUTH":
            _bad_auth()
            return
        _unknown_message()
    if parts[2] == "0" and len(parts) == 4:
        print(f"Room available to join as {mode}: {parts[3]}")
    elif parts[2] == "1" and len(parts) == 3:
        print("Error: Please input a valid mode.", file=sys.stderr)
    else:
        _unknown_message()
        return


def handle_create_response(response: str, room_name: str) -> bool:
    """Print a CREATE acknowledgement and return whether creation succeeded."""
    if response == "BADAUTH":
        _bad_auth()
        return False
    parts = response.split(":")
    if len(parts) != 3 or parts[:2] != ["CREATE", "ACKSTATUS"]:
        _unknown_message()
    status = parts[2]
    if status == "0":
        print(f"Successfully created room {room_name}")
        print("Waiting for other player...")
        return True
    if status == "1":
        print(f"Error: Room {room_name} is invalid", file=sys.stderr)
    elif status == "2":
        print(f"Error: Room {room_name} already exists", file=sys.stderr)
    elif status == "3":
        print(
            "Error: Server already contains a maximum of 256 rooms",
            file=sys.stderr,
        )
    elif status != "4":
        _unknown_message()
    return False


def handle_join_response(response: str, room_name: str, mode: str) -> bool:
    """Print a JOIN acknowledgement and return whether joining succeeded."""
    if response == "BADAUTH":
        _bad_auth()
        return False
    parts = response.split(":")
    if len(parts) != 3 or parts[:2] != ["JOIN", "ACKSTATUS"]:
        _unknown_message()
    status = parts[2]
    if status == "0":
        print(f"Successfully joined room {room_name} as a {mode.lower()}")
        return True
    if status == "1":
        print(f"Error: No room named {room_name}", file=sys.stderr)
    elif status == "2":
        print(f"Error: The room {room_name} already has 2 players", file=sys.stderr)
    elif status != "3":
        _unknown_message()
    return False


def handle_begin(response: str) -> tuple[str, str]:
    """Print and return the players named by a BEGIN message."""
    parts = response.split(":")
    if len(parts) != 3 or parts[0] != "BEGIN":
        _unknown_message()
    player_one, player_two = parts[1:]
    print(
        f"Match between {player_one} and {player_two} will commence, "
        f"it is currently {player_one}'s turn."
    )
    return player_one, player_two


def handle_in_progress(response: str) -> tuple[str, str]:
    """Print and return the turn order named by INPROGRESS."""
    parts = response.split(":")
    if len(parts) != 3 or parts[0] != "INPROGRESS":
        _unknown_message()
    current_player, opposing_player = parts[1:]
    print(
        f"Match between {current_player} and {opposing_player} is currently "
        f"in progress, it is {current_player}'s turn"
    )
    return current_player, opposing_player


def handle_game_end(response: str, username: str, mode: str) -> None:
    """Print the final board and outcome from a GAMEEND message."""
    parts = response.split(":")
    if len(parts) not in {3, 4} or parts[0] != "GAMEEND":
        _unknown_message()
    _print_board(parts[1])
    status = parts[2]
    if status == "0" and len(parts) == 4:
        winner = parts[3]
        if mode == "VIEWER":
            print(f"{winner} has won this game")
        elif username == winner:
            print("Congratulations, you won!")
        else:
            print("Sorry you lost. Good luck next time.")
    elif status == "1" and len(parts) == 3:
        print("Game ended in a draw")
    elif status == "2" and len(parts) == 4:
        print(f"{parts[3]} won due to the opposing player forfeiting")
    else:
        _unknown_message()


def _read_coordinate(label: str) -> int:
    while True:
        try:
            value = int(input(f"{label}: "))
        except ValueError:
            print(
                f"{label} values must be an integer between 0 and 2",
                file=sys.stderr,
            )
            continue
        if 0 <= value <= 2:
            return value
        print(
            f"{label} values must be an integer between 0 and 2",
            file=sys.stderr,
        )


def get_valid_coordinates(board: list[list[str]]) -> tuple[int, int]:
    """Read an unoccupied, zero-based column and row from the user."""
    while True:
        column = _read_coordinate("Column")
        row = _read_coordinate("Row")
        occupant = board[row][column]
        if occupant == game.EMPTY:
            return column, row
        print(f"({column}, {row}) is occupied by {occupant}.", file=sys.stderr)


def _wait_for_begin(server_socket) -> tuple[str, str]:
    response = receive_message(server_socket)
    return handle_begin(response)


def play_game(server_socket, username: str, players: tuple[str, str]) -> bool:
    """Run the player-side turn loop. Return False when the user enters QUIT."""
    board = _empty_board()
    my_turn = username == players[0]
    while True:
        if not my_turn:
            response = receive_message(server_socket)
            if response.startswith("GAMEEND:"):
                handle_game_end(response, username, "PLAYER")
                return True
            if not response.startswith("BOARDSTATUS:"):
                _unknown_message()
            board = _print_board(response.split(":")[1])
            print("It is the current player's turn")
            my_turn = True
            continue

        command = input().strip().upper()
        if command == "QUIT":
            return False
        if command == "PLACE":
            column, row = get_valid_coordinates(board)
            send_message(server_socket, f"PLACE:{column}:{row}")
            response = receive_message(server_socket)
            if response.startswith("GAMEEND:"):
                handle_game_end(response, username, "PLAYER")
                return True
            if response == "BADAUTH":
                _bad_auth()
                return True
            if response == "NOROOM":
                _no_room()
                return True
            if not response.startswith("BOARDSTATUS:"):
                _unknown_message()
            board = _print_board(response.split(":")[1])
            print("It is the opposing player's turn")
            my_turn = False
        elif command == "FORFEIT":
            send_message(server_socket, "FORFEIT")
            response = receive_message(server_socket)
            if response == "BADAUTH":
                _bad_auth()
                return True
            if response == "NOROOM":
                _no_room()
                return True
            if not response.startswith("GAMEEND:"):
                _unknown_message()
            handle_game_end(response, username, "PLAYER")
            return True
        else:
            print(f"Unknown command: {command}")


def view_game(server_socket, username: str, initial_message: str) -> None:
    """Receive and display an entire game as a viewer."""
    if initial_message.startswith("BEGIN:"):
        current_player, opposing_player = handle_begin(initial_message)
    elif initial_message.startswith("INPROGRESS:"):
        current_player, opposing_player = handle_in_progress(initial_message)
    else:
        _unknown_message()
        return

    while True:
        response = receive_message(server_socket)
        if response.startswith("GAMEEND:"):
            handle_game_end(response, username, "VIEWER")
            return
        if not response.startswith("BOARDSTATUS:"):
            _unknown_message()
        _print_board(response.split(":")[1])
        current_player, opposing_player = opposing_player, current_player
        print(f"It is {current_player}'s turn")


def _read_mode(prompt: str, error_message: str) -> str:
    while True:
        mode = input(prompt).strip().upper()
        if mode in VALID_MODES:
            return mode
        print(error_message, file=sys.stderr)


def run_client(server_socket) -> None:
    """Read user commands and coordinate room/game sessions."""
    username = None
    while True:
        command = input().strip().upper()
        if command == "QUIT":
            return
        if command == "LOGIN":
            attempted_username = input("Enter username: ").strip()
            password = input("Enter password: ").strip()
            send_message(server_socket, f"LOGIN:{attempted_username}:{password}")
            logged_in = handle_login_response(
                receive_message(server_socket), attempted_username
            )
            if logged_in is not None:
                username = logged_in
        elif command == "REGISTER":
            attempted_username = input("Enter username: ").strip()
            password = input("Enter password: ").strip()
            send_message(server_socket, f"REGISTER:{attempted_username}:{password}")
            handle_register_response(
                receive_message(server_socket), attempted_username
            )
        elif command == "ROOMLIST":
            mode = _read_mode(
                "Do you want to have a room list as player or viewer? (Player/Viewer) ",
                "Error: Please input a valid mode.",
            )
            send_message(server_socket, f"ROOMLIST:{mode}")
            handle_roomlist_response(receive_message(server_socket), mode)
        elif command == "CREATE":
            room_name = input("Enter room name you want to create: ").strip()
            send_message(server_socket, f"CREATE:{room_name}")
            if handle_create_response(receive_message(server_socket), room_name):
                players = _wait_for_begin(server_socket)
                if not play_game(server_socket, username, players):
                    return
        elif command == "JOIN":
            room_name = input("Enter room name you want to join: ").strip()
            mode = _read_mode(
                "You wish to join the room as: (Player/Viewer) ",
                "Unknown input.",
            )
            send_message(server_socket, f"JOIN:{room_name}:{mode}")
            if handle_join_response(receive_message(server_socket), room_name, mode):
                initial_message = receive_message(server_socket)
                if mode == "PLAYER":
                    players = handle_begin(initial_message)
                    if not play_game(server_socket, username, players):
                        return
                else:
                    view_game(server_socket, username, initial_message)
        else:
            print(f"Unknown command: {command}")


def main(args: list[str]) -> None:
    """Connect to a server from <address> <port> and run the client."""
    if len(args) != 2:
        print(
            "Error: Expecting 2 arguments: <server address> <port>",
            file=sys.stderr,
        )
        raise SystemExit(1)

    server_address, port_text = args
    client_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        client_socket.connect((server_address, int(port_text)))
    except (OSError, ValueError) as error:
        print(
            f"Error: cannot connect to server at {server_address} and {port_text}.",
            file=sys.stderr,
        )
        client_socket.close()
        raise SystemExit(1) from error

    try:
        run_client(client_socket)
    except EOFError:
        pass
    except ServerDisconnected:
        print("Error: lost connection to the server.", file=sys.stderr)
    except UnknownServerMessage:
        pass
    finally:
        try:
            client_socket.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        client_socket.close()


if __name__ == "__main__":
    main(sys.argv[1:])
