"""Complete SVG 1.1 path tokenization for the bounded DayForge static profile."""

from dataclasses import dataclass
import math
import re


MAX_NUMBER = 1_000_000
MAX_COMMANDS = 16_384
MAX_TEXT = 524_288
WSP = " \t\r\n"
NUMBER = re.compile(r"[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
ARITY = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0}


class SvgValidationError(ValueError):
    """Invalid/unsupported static image input; never a successful partial parse."""


@dataclass(frozen=True)
class SvgCommand:
    command: str
    values: tuple[float, ...]


class NumberScanner:
    def __init__(self, value: str):
        if len(value) > MAX_TEXT:
            raise SvgValidationError("SVG_TEXT_LIMIT")
        self.value = value
        self.position = 0

    def whitespace(self) -> None:
        while self.position < len(self.value) and self.value[self.position] in WSP:
            self.position += 1

    def separator(self) -> None:
        self.whitespace()
        if self.position < len(self.value) and self.value[self.position] == ",":
            self.position += 1
            self.whitespace()

    def number(self, *, flag: bool = False, unsigned: bool = False) -> float:
        if flag:
            if (
                self.position >= len(self.value)
                or self.value[self.position] not in "01"
            ):
                raise SvgValidationError("SVG_PATH_SYNTAX")
            value = float(self.value[self.position])
            self.position += 1
            return value
        found = NUMBER.match(self.value, self.position)
        if found is None:
            raise SvgValidationError("SVG_NUMBER_SYNTAX")
        token = found.group()
        if unsigned and token[0] in "+-":
            raise SvgValidationError("SVG_PATH_SYNTAX")
        if len(token) > 64:
            raise SvgValidationError("SVG_NUMBER_LIMIT")
        value = float(token)
        if (
            not math.isfinite(value)
            or abs(value) > MAX_NUMBER
            or (
                value == 0
                and any(c in "123456789" for c in token.lower().split("e")[0])
            )
        ):
            raise SvgValidationError("SVG_NUMBER_LIMIT")
        self.position = found.end()
        return value


def parse_numbers(
    value: str, *, maximum: int = MAX_COMMANDS * 2, points: bool = False
) -> tuple[float, ...]:
    scanner = NumberScanner(value)
    scanner.whitespace()
    result: list[float] = []
    while scanner.position < len(value):
        if result:
            previous = scanner.position
            scanner.whitespace()
            if scanner.position == len(value):
                break
            if value[scanner.position] == ",":
                scanner.position += 1
                scanner.whitespace()
            # SVG lists require comma-wsp. Only points allow x-y adjacency,
            # and only within a coordinate pair; path data has its own grammar.
            if scanner.position == previous and not (
                points
                and len(result) % 2 == 1
                and value[scanner.position : scanner.position + 1] == "-"
            ):
                raise SvgValidationError("SVG_NUMBER_SYNTAX")
        if len(result) >= maximum:
            raise SvgValidationError("SVG_NUMBER_COUNT")
        result.append(scanner.number())
    return tuple(result)


def parse_path(value: str) -> tuple[SvgCommand, ...]:
    scanner = NumberScanner(value)
    result: list[SvgCommand] = []
    implicit: str | None = None
    while True:
        scanner.whitespace()
        if scanner.position == len(value):
            return tuple(result)
        next_char = value[scanner.position]
        explicit = next_char in "MmLlHhVvCcSsQqTtAaZz"
        if explicit:
            command = next_char
            scanner.position += 1
        elif implicit is not None:
            command = implicit
        else:
            raise SvgValidationError("SVG_PATH_SYNTAX")
        upper = command.upper()
        if not result and upper != "M":
            raise SvgValidationError("SVG_PATH_SYNTAX")
        if len(result) >= MAX_COMMANDS:
            raise SvgValidationError("SVG_COMMAND_LIMIT")
        args = []
        for index in range(ARITY[upper]):
            if index or not explicit:
                previous = scanner.position
                scanner.separator()
                if upper == "A" and index == 3 and scanner.position == previous:
                    raise SvgValidationError("SVG_PATH_SYNTAX")
            else:
                scanner.whitespace()
            args.append(
                scanner.number(
                    flag=upper == "A" and index in (3, 4),
                    unsigned=upper == "A" and index in (0, 1),
                )
            )
        result.append(SvgCommand(command, tuple(args)))
        implicit = (
            None
            if upper == "Z"
            else ("l" if command == "m" else "L")
            if upper == "M"
            else command
        )
