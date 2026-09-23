"""Bounded absolute geometry for the static SVG profile, before platform drawing."""

from dataclasses import dataclass
import math
import sys

from src.appearance.svg_path import (
    MAX_COMMANDS,
    SvgCommand,
    SvgValidationError,
    parse_path,
)


MAX_COORDINATE = 1_000_000_000_000.0
MAX_EXPANDED_COMMANDS = MAX_COMMANDS * 8


def coordinate(value: float) -> float:
    if not math.isfinite(value) or abs(value) > MAX_COORDINATE:
        raise SvgValidationError("SVG_GEOMETRY_LIMIT")
    return value


def norm(x: float, y: float) -> float:
    scale = max(abs(x), abs(y))
    if scale == 0:
        return 0.0
    a, b = x / scale, y / scale
    return scale * math.sqrt(a * a + b * b)


def ratio_product(value: float, numerator: float, denominator: float) -> float:
    if value == 0:
        return 0.0

    def exponent(number: float) -> int:
        # Match Java Math.getExponent, including its subnormal sentinel.
        return math.frexp(number)[1] - 1 if abs(number) >= sys.float_info.min else -1023

    a, b, c = exponent(value), exponent(numerator), exponent(denominator)
    mantissa = (
        math.ldexp(value, -a) * math.ldexp(numerator, -b) / math.ldexp(denominator, -c)
    )
    try:
        return coordinate(math.ldexp(mantissa, a + b - c))
    except OverflowError as error:
        raise SvgValidationError("SVG_GEOMETRY_LIMIT") from error


def arc(
    x1: float, y1: float, values: tuple[float, ...], x2: float, y2: float
) -> tuple[SvgCommand, ...]:
    if x1 == x2 and y1 == y2:
        return ()
    rx, ry = values[:2]
    if rx == 0 or ry == 0:
        return (SvgCommand("L", (x2, y2)),)
    # fmod matches Kotlin remainder for negative rotations.
    rotation = math.radians(math.fmod(values[2], 360))
    cosine, sine = math.cos(rotation), math.sin(rotation)
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    xp, yp = cosine * dx + sine * dy, -sine * dx + cosine * dy
    corrected_rx = coordinate(norm(xp, ratio_product(yp, rx, ry)))
    corrected_ry = coordinate(norm(yp, ratio_product(xp, ry, rx)))
    if corrected_rx > rx or corrected_ry > ry:
        rx, ry = corrected_rx, corrected_ry
    if rx <= 0 or ry <= 0:
        raise SvgValidationError("SVG_GEOMETRY_LIMIT")
    nx, ny = xp / rx, yp / ry
    distance = norm(nx, ny)
    if distance <= 0 or not math.isfinite(distance):
        raise SvgValidationError("SVG_GEOMETRY_LIMIT")
    sign = -1.0 if values[3] == values[4] else 1.0
    factor = sign * math.sqrt(max(0.0, 1 - distance * distance))
    cxp, cyp = rx * (ny / distance) * factor, -ry * (nx / distance) * factor
    cx = coordinate(cosine * cxp - sine * cyp + (x1 + x2) / 2)
    cy = coordinate(sine * cxp + cosine * cyp + (y1 + y2) / 2)
    ux, uy = (xp - cxp) / rx, (yp - cyp) / ry
    vx, vy = (-xp - cxp) / rx, (-yp - cyp) / ry
    start = math.atan2(uy, ux)
    sweep = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
    if values[4] == 0 and sweep > 0:
        sweep -= 2 * math.pi
    if values[4] == 1 and sweep < 0:
        sweep += 2 * math.pi
    if not math.isfinite(sweep):
        raise SvgValidationError("SVG_GEOMETRY_LIMIT")
    count = max(1, math.ceil(abs(sweep) / (math.pi / 4)))
    if count > 8:
        raise SvgValidationError("SVG_GEOMETRY_LIMIT")
    step = sweep / count

    def point(angle: float) -> tuple[float, float]:
        return (
            coordinate(
                cx + rx * cosine * math.cos(angle) - ry * sine * math.sin(angle)
            ),
            coordinate(
                cy + rx * sine * math.cos(angle) + ry * cosine * math.sin(angle)
            ),
        )

    def derivative(angle: float) -> tuple[float, float]:
        return (
            -rx * cosine * math.sin(angle) - ry * sine * math.cos(angle),
            -rx * sine * math.sin(angle) + ry * cosine * math.cos(angle),
        )

    result = []
    for index in range(count):
        a = start + index * step
        b = a + step
        p = (x1, y1) if index == 0 else point(a)
        q = (x2, y2) if index == count - 1 else point(b)
        u, v = derivative(a), derivative(b)
        alpha = 4.0 / 3.0 * math.tan(step / 4)
        result.append(
            SvgCommand(
                "C",
                tuple(
                    coordinate(value)
                    for value in (
                        p[0] + alpha * u[0],
                        p[1] + alpha * u[1],
                        q[0] - alpha * v[0],
                        q[1] - alpha * v[1],
                        q[0],
                        q[1],
                    )
                ),
            )
        )
    return tuple(result)


@dataclass(frozen=True)
class SvgGeometry:
    commands: tuple[SvgCommand, ...]
    length_bound: float
    contours: int
    source_commands: int


def resolve_path(value: str) -> SvgGeometry:
    result: list[SvgCommand] = []
    x = y = sx = sy = length = 0.0
    previous = ""
    cubic: tuple[float, float] | None = None
    quadratic: tuple[float, float] | None = None
    contours = 0

    def append(command: str, values: tuple[float, ...]) -> None:
        nonlocal x, y, sx, sy, length, contours
        if result and result[-1].command == "Z" and command not in {"M", "Z"}:
            # SVG closepath ends the subpath. Drawing afterwards starts another
            # at the same point; make the native contour and dash reset explicit.
            append("M", (x, y))
        for item in values:
            coordinate(item)
        if len(result) >= MAX_EXPANDED_COMMANDS:
            raise SvgValidationError("SVG_GEOMETRY_LIMIT")
        if command == "Z":
            length += norm(x - sx, y - sy)
        elif command != "M":
            px, py = x, y
            for index in range(0, len(values), 2):
                length += norm(values[index] - px, values[index + 1] - py)
                px, py = values[index : index + 2]
        result.append(SvgCommand(command, values))
        if command == "Z":
            x, y = sx, sy
        else:
            x, y = values[-2:]
        if command == "M":
            sx, sy = x, y
            contours += 1

    tokens = parse_path(value)
    for token in tokens:
        code, values, relative = (
            token.command.upper(),
            token.values,
            token.command.islower(),
        )

        def point(index: int) -> tuple[float, float]:
            return (
                coordinate(values[index] + (x if relative else 0)),
                coordinate(values[index + 1] + (y if relative else 0)),
            )

        next_cubic = next_quadratic = None
        if code == "Z":
            append("Z", ())
        elif code in {"M", "L"}:
            append(code, point(0))
        elif code == "H":
            append("L", (values[0] + (x if relative else 0), y))
        elif code == "V":
            append("L", (x, values[0] + (y if relative else 0)))
        elif code == "C":
            a, b, end = point(0), point(2), point(4)
            next_cubic = b
            append("C", a + b + end)
        elif code == "S":
            a = (
                (2 * x - cubic[0], 2 * y - cubic[1])
                if previous in {"C", "S"} and cubic is not None
                else (x, y)
            )
            b, end = point(0), point(2)
            next_cubic = b
            append("C", a + b + end)
        elif code == "Q":
            a, end = point(0), point(2)
            next_quadratic = a
            append("Q", a + end)
        elif code == "T":
            a = (
                (2 * x - quadratic[0], 2 * y - quadratic[1])
                if previous in {"Q", "T"} and quadratic is not None
                else (x, y)
            )
            end = point(0)
            next_quadratic = a
            append("Q", a + end)
        elif code == "A":
            end = point(5)
            for part in arc(x, y, values, *end):
                append(part.command, part.values)
            x, y = end
        else:
            raise SvgValidationError("SVG_PATH_SYNTAX")
        previous, cubic, quadratic = code, next_cubic, next_quadratic
    return SvgGeometry(tuple(result), length, contours, len(tokens))
