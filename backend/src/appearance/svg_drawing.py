"""Shared preflight for native static drawing; does not render or install bytes."""

import math
import struct

from src.appearance.svg_geometry import SvgGeometry, norm
from src.appearance.svg_path import SvgValidationError, parse_numbers


MAX_DASH_WORK = 65_536
INHERITED = {
    "fill",
    "stroke",
    "fill-opacity",
    "stroke-opacity",
    "fill-rule",
    "stroke-width",
    "stroke-linecap",
    "stroke-linejoin",
    "stroke-miterlimit",
    "stroke-dasharray",
    "stroke-dashoffset",
}


def rounded_coordinate(value: float) -> float:
    return struct.unpack("!f", struct.pack("!f", value))[0]


def native_number(value: float) -> float:
    # Native Canvas uses IEEE binary32; do not turn positive widths into hairlines
    # or positive dash intervals / matrix coefficients into zero.
    try:
        rounded = rounded_coordinate(value)
    except OverflowError as error:
        raise SvgValidationError("SVG_DRAW_PRECISION") from error
    if not math.isfinite(rounded) or (value != 0 and rounded == 0):
        raise SvgValidationError("SVG_DRAW_PRECISION")
    return rounded


def viewport(attrs: dict[str, str], width: int, height: int) -> tuple[float, ...]:
    if "viewBox" not in attrs:
        return (1, 0, 0, 1, 0, 0)
    x, y, w, h = parse_numbers(attrs["viewBox"])
    sx, sy = width / w, height / h
    if attrs.get("preserveAspectRatio") != "none":
        sx = sy = min(sx, sy)
    return (sx, 0, 0, sy, (width - w * sx) / 2 - x * sx, (height - h * sy) / 2 - y * sy)


def stroke_work(
    tag: str, attrs: dict[str, str], style: dict[str, str], geometry: SvgGeometry | None
) -> float:
    def number(key: str, default=0.0) -> float:
        return parse_numbers(attrs[key])[0] if key in attrs else default

    width = parse_numbers(style.get("stroke-width", "1"))[0]
    native_number(width)
    native_number(parse_numbers(style.get("stroke-miterlimit", "4"))[0])
    offset = parse_numbers(style.get("stroke-dashoffset", "0"))[0]
    native_number(offset)
    raw = style.get("stroke-dasharray", "none")
    if raw == "none":
        return 0.0
    intervals = [native_number(value) for value in parse_numbers(raw)]
    if len(intervals) % 2:
        intervals *= 2
    period = sum(intervals)
    native_period = 0.0
    for value in intervals:
        native_period = native_number(native_period + value)
    period = min(period, native_period)
    if style.get("stroke", "none") == "none" or width == 0 or tag in {"svg", "g"}:
        return 0.0
    contours = 1
    if tag == "path":
        assert geometry is not None
        length, contours = geometry.length_bound, geometry.contours
        # Quantization can turn a tiny zigzag around a float rounding boundary
        # into a much longer native path. Bound what Canvas actually receives.
        x = y = sx = sy = native_length = 0.0
        for command in geometry.commands:
            points = tuple(rounded_coordinate(v) for v in command.values)
            if command.command == "Z":
                native_length += norm(x - sx, y - sy)
                x, y = sx, sy
            else:
                if command.command != "M":
                    for i in range(0, len(points), 2):
                        native_length += norm(points[i] - x, points[i + 1] - y)
                        x, y = points[i : i + 2]
                x, y = points[-2:]
                if command.command == "M":
                    sx, sy = x, y
        length = max(length, native_length)
    elif tag == "rect":
        length = 2 * (number("width") + number("height"))
        length = max(
            length,
            2
            * (
                rounded_coordinate(number("x") + number("width"))
                - rounded_coordinate(number("x"))
                + rounded_coordinate(number("y") + number("height"))
                - rounded_coordinate(number("y"))
            ),
        )
    elif tag in {"circle", "ellipse"}:
        length = (
            2
            * math.pi
            * (number("r") if tag == "circle" else max(number("rx"), number("ry")))
        )
        rx = number("r") if tag == "circle" else number("rx")
        ry = rx if tag == "circle" else number("ry")
        native_rx = (
            rounded_coordinate(number("cx") + rx)
            - rounded_coordinate(number("cx") - rx)
        ) / 2
        native_ry = (
            rounded_coordinate(number("cy") + ry)
            - rounded_coordinate(number("cy") - ry)
        ) / 2
        length = max(length, 2 * math.pi * max(native_rx, native_ry))
    elif tag == "line":
        length = max(
            norm(number("x2") - number("x1"), number("y2") - number("y1")),
            norm(
                rounded_coordinate(number("x2")) - rounded_coordinate(number("x1")),
                rounded_coordinate(number("y2")) - rounded_coordinate(number("y1")),
            ),
        )
    else:
        points = parse_numbers(attrs["points"], points=True)
        length = sum(
            norm(points[i] - points[i - 2], points[i + 1] - points[i - 1])
            for i in range(2, len(points), 2)
        )
        if tag == "polygon":
            length += norm(points[-2] - points[0], points[-1] - points[1])
        rounded = [rounded_coordinate(value) for value in points]
        native_length = sum(
            norm(rounded[i] - rounded[i - 2], rounded[i + 1] - rounded[i - 1])
            for i in range(2, len(rounded), 2)
        )
        if tag == "polygon":
            native_length += norm(rounded[-2] - rounded[0], rounded[-1] - rounded[1])
        length = max(length, native_length)
    # Include phase/contour starts and round upwards, not to nearest integer.
    work = (math.ceil(length / period) + contours) * len(intervals)
    if work > MAX_DASH_WORK:
        raise SvgValidationError("SVG_DASH_LIMIT")
    return work
