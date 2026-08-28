"""Reusable board and win/draw logic for tic-tac-toe."""

from typing import Optional

__all__ = [
    "NOUGHT",
    "CROSS",
    "EMPTY",
    "create_board",
    "print_board",
    "player_turn",
    "player_wins",
    "players_draw"
]

BOARD_SIZE = 3
CELL_SIZE = 5

ROW_SEPARATOR = '-'
COLUMN_SEPARATOR = '|'
N_ROW_SEPARATORS = CELL_SIZE + (CELL_SIZE - 1) * (BOARD_SIZE - 1)

NOUGHT = 'O'
CROSS = 'X'
EMPTY = ' '

Board = list[list[str]]

#############################################################
############### Private functions—do not use! ###############
#############################################################

def _player_wins_vertically(player: str, board: Board) -> bool:
    return any(
        all(board[y][x] == player for y in range(BOARD_SIZE))
        for x in range(BOARD_SIZE)
    )

def _player_wins_horizontally(player: str, board: Board) -> bool:
    return any(
        all(board[x][y] == player for y in range(BOARD_SIZE))
        for x in range(BOARD_SIZE)
    )

def _player_wins_diagonally(player: str, board: Board) -> bool:
    return (
        all(board[y][y] == player for y in range(BOARD_SIZE)) or
        all(board[BOARD_SIZE - 1 - y][y] == player for y in range(BOARD_SIZE))
    )

def _try_read_value(prompt: str) -> Optional[int]:
    try:
        value = int(input(prompt))
    except ValueError:
        return None
    return value if 1 <= value < BOARD_SIZE + 1 else None

def _empty_board_position(board: Board) -> tuple[int, int]:
    while True:
        while (column := _try_read_value("Column: ")) is None:
            print(f"Column values must be between 1 and {BOARD_SIZE}")
        while (row := _try_read_value("Row: ")) is None:
            print(f"Row values must be between 1 and {BOARD_SIZE}")
        x = column - 1
        y = row - 1
        if (occupant := board[y][x]) == EMPTY:
            return (y, x)
        print(f"({column}, {row}) is occupied by {occupant}")

##########################################################
############### Public functions—use these ###############
##########################################################

def create_board() -> Board:
    """Create a new board"""
    return [[EMPTY for _ in range(BOARD_SIZE)] for _ in range(BOARD_SIZE)]

def print_board(board: Board):
    """Print the board"""
    print(ROW_SEPARATOR * N_ROW_SEPARATORS)
    for row in board:
        for value in row:
            print(f"{COLUMN_SEPARATOR} {value} ", end='')
        print(COLUMN_SEPARATOR)
        print(ROW_SEPARATOR * N_ROW_SEPARATORS)

def player_turn(player: str, board: Board) -> tuple[int, int]:
    """Does a player's turn and returns the position of the turn"""
    y, x = _empty_board_position(board)
    board[y][x] = player
    return (x + 1, y + 1)

def player_wins(player: str, board: Board) -> bool:
    """Determines whether the specified player wins given the board"""
    return (
        _player_wins_vertically(player, board) or
        _player_wins_horizontally(player, board) or
        _player_wins_diagonally(player, board)
    )

def players_draw(board: Board) -> bool:
    """Determines whether the players draw on the given board"""
    return all(
        board[y][x] != EMPTY
        for y in range(BOARD_SIZE)
        for x in range(BOARD_SIZE)
    )

def tic_tac_toe() -> None:
    """Main game loop for tic-tac-toe"""
    board = create_board()
    player = CROSS

    game_won = game_drawn = False
    while not game_won and not game_drawn:
        print_board(board)
        print()

        player_turn(player, board)
        print()

        if player_wins(player, board):
            game_won = True
        elif players_draw(board):
            game_drawn = True
        else:
            player = NOUGHT if player == CROSS else CROSS

    print_board(board)
    print()

    if game_won:
        winner = "Noughts" if player == NOUGHT else "Crosses"
        print(winner, "wins!")
    elif game_drawn:
        print("Draw!")

if __name__ == "__main__":
    tic_tac_toe()
