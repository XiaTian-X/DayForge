"""Fail-closed static SVG profile, before any rendering or durable installation."""

from dataclasses import dataclass
import hashlib
import math
import re
from xml.parsers import expat

from src.appearance.svg_path import (
    MAX_COMMANDS,
    MAX_NUMBER,
    MAX_TEXT,
    WSP,
    SvgValidationError,
    parse_numbers,
    parse_path,
)
from src.v2.appearance import IconBlob


IDENTITY: tuple[float, ...] = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
SVG_NS = "http://www.w3.org/2000/svg"
GEOMETRY = {
    "svg": {"width", "height", "viewBox", "version", "preserveAspectRatio"},
    "g": set(),
    "path": {"d"},
    "rect": {"x", "y", "width", "height", "rx", "ry"},
    "circle": {"cx", "cy", "r"},
    "ellipse": {"cx", "cy", "rx", "ry"},
    "line": {"x1", "y1", "x2", "y2"},
    "polyline": {"points"},
    "polygon": {"points"},
}
REQUIRED = {
    "svg": {"width", "height"},
    "path": {"d"},
    "rect": {"width", "height"},
    "circle": {"r"},
    "ellipse": {"rx", "ry"},
    "polyline": {"points"},
    "polygon": {"points"},
}
COMMON = {
    "id",
    "fill",
    "stroke",
    "fill-opacity",
    "stroke-opacity",
    "opacity",
    "fill-rule",
    "stroke-width",
    "stroke-linecap",
    "stroke-linejoin",
    "stroke-miterlimit",
    "stroke-dasharray",
    "stroke-dashoffset",
    "transform",
}
ENUMS = {
    "fill-rule": {"nonzero", "evenodd"},
    "stroke-linecap": {"butt", "round", "square"},
    "stroke-linejoin": {"miter", "round", "bevel"},
    "version": {"1.1"},
    "preserveAspectRatio": {"none", "xMidYMid meet"},
}
COLOR = re.compile(r"(?:none|#[0-9a-fA-F]{3}|#[0-9a-fA-F]{6})\Z")
IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_.:-]{0,127}\Z")
TRANSFORM = re.compile(
    r"(matrix|translate|scale|rotate|skewX|skewY)[ \t\r\n]*\(([^()]*)\)"
)


def require(condition: bool, code: str) -> None:
    if not condition:
        raise SvgValidationError(code)


def matrix_product(
    left: tuple[float, ...], right: tuple[float, ...]
) -> tuple[float, ...]:
    a, b, c, d, e, f = left
    g, h, i, j, k, m = right
    result = (
        a * g + c * h,
        b * g + d * h,
        a * i + c * j,
        b * i + d * j,
        a * k + c * m + e,
        b * k + d * m + f,
    )
    require(
        all(math.isfinite(value) and abs(value) <= MAX_NUMBER for value in result),
        "SVG_TRANSFORM_LIMIT",
    )
    return result


def transforms(value: str) -> tuple[tuple[float, ...], int]:
    result = IDENTITY
    position = 0
    count = 0
    while position < len(value):
        previous = position
        while position < len(value) and value[position] in WSP:
            position += 1
        if position == len(value):
            break
        if count and value[position] == ",":
            position += 1
            while position < len(value) and value[position] in WSP:
                position += 1
        require(not count or position > previous, "SVG_TRANSFORM_SYNTAX")
        found = TRANSFORM.match(value, position)
        require(found is not None, "SVG_TRANSFORM_SYNTAX")
        assert found is not None
        name, raw = found.groups()
        args = parse_numbers(raw, maximum=6)
        require(
            len(args)
            in {
                "matrix": {6},
                "translate": {1, 2},
                "scale": {1, 2},
                "rotate": {1, 3},
                "skewX": {1},
                "skewY": {1},
            }[name],
            "SVG_TRANSFORM_SYNTAX",
        )
        count += 1
        require(count <= 64, "SVG_TRANSFORM_LIMIT")
        if name == "matrix":
            matrix = args
        elif name == "translate":
            matrix = (1, 0, 0, 1, args[0], args[1] if len(args) == 2 else 0)
        elif name == "scale":
            matrix = (args[0], 0, 0, args[1] if len(args) == 2 else args[0], 0, 0)
        elif name == "rotate":
            sine, cosine = (
                math.sin(math.radians(args[0])),
                math.cos(math.radians(args[0])),
            )
            x, y = args[1:] if len(args) == 3 else (0, 0)
            matrix = (
                cosine,
                sine,
                -sine,
                cosine,
                x - cosine * x + sine * y,
                y - sine * x - cosine * y,
            )
        else:
            angle = math.radians(args[0])
            require(abs(math.cos(angle)) > 0.000001, "SVG_TRANSFORM_LIMIT")
            tangent = math.tan(angle)
            matrix = (
                (1, 0, tangent, 1, 0, 0)
                if name == "skewX"
                else (1, tangent, 0, 1, 0, 0)
            )
        result = matrix_product(result, matrix)
        position = found.end()
    return result, count


@dataclass(frozen=True)
class SvgInspection:
    width: int
    height: int
    elements: int
    commands: int


def _attributes(tag: str, attrs: dict[str, str]) -> tuple[int, tuple[float, ...], int]:
    require(
        set(attrs) <= GEOMETRY[tag] | COMMON and REQUIRED.get(tag, set()) <= set(attrs),
        "SVG_ATTRIBUTES",
    )
    commands = 0
    transform, transform_count = IDENTITY, 0
    for key, value in attrs.items():
        if key == "id":
            require(IDENTIFIER.fullmatch(value) is not None, "SVG_ATTRIBUTES")
        elif key in ENUMS:
            require(value in ENUMS[key], "SVG_ATTRIBUTES")
        elif key in {"fill", "stroke"}:
            require(COLOR.fullmatch(value) is not None, "SVG_ATTRIBUTES")
        elif key == "d":
            commands += len(parse_path(value))
        elif key == "transform":
            transform, transform_count = transforms(value)
        elif key == "stroke-dasharray" and value == "none":
            continue
        else:
            values = parse_numbers(
                value.removesuffix("px")
                if tag == "svg" and key in {"width", "height"}
                else value,
                points=key == "points",
            )
            if key == "viewBox":
                require(
                    len(values) == 4 and values[2] > 0 and values[3] > 0, "SVG_GEOMETRY"
                )
            elif key == "points":
                require(
                    len(values) >= (6 if tag == "polygon" else 4)
                    and len(values) % 2 == 0,
                    "SVG_GEOMETRY",
                )
                commands += len(values) // 2
            elif key == "stroke-dasharray":
                require(
                    0 < len(values) <= 64 and min(values) >= 0 and max(values) > 0,
                    "SVG_GEOMETRY",
                )
            else:
                require(len(values) == 1, "SVG_GEOMETRY")
                number = values[0]
                if key in {"width", "height", "rx", "ry", "r", "stroke-width"}:
                    require(number >= 0, "SVG_GEOMETRY")
                if key.endswith("opacity"):
                    require(0 <= number <= 1, "SVG_GEOMETRY")
                if key == "stroke-miterlimit":
                    require(number >= 1, "SVG_GEOMETRY")
    return commands, transform, transform_count


def inspect_svg(data: bytes, expected: IconBlob) -> SvgInspection:
    require(expected.media_type == "image/svg+xml", "SVG_MEDIA_TYPE")
    require(
        len(data) <= MAX_TEXT and len(data) == expected.byte_length, "SVG_BYTE_LENGTH"
    )
    require(hashlib.sha256(data).hexdigest() == expected.sha256, "SVG_HASH")
    try:
        decoded = data.decode("utf-8-sig")
    except UnicodeDecodeError as error:
        raise SvgValidationError("SVG_ENCODING") from error
    # Reject declarations before invoking a parser; handlers below are defense
    # in depth. UTF-16/32 and compressed input cannot bypass this UTF-8 boundary.
    require(
        "<!DOCTYPE" not in decoded and "<!ENTITY" not in decoded, "SVG_XML_FORBIDDEN"
    )
    parser = expat.ParserCreate("UTF-8", "|")
    stack: list[tuple[str, tuple[float, ...]]] = []
    elements = commands = transform_count = 0
    width = height = 0

    def start(name: str, attrs: dict[str, str]) -> None:
        nonlocal elements, commands, transform_count, width, height
        namespace, _, tag = name.rpartition("|")
        require(namespace in {"", SVG_NS} and tag in GEOMETRY, "SVG_ELEMENT")
        require(
            (not stack and elements == 0 and tag == "svg")
            or (bool(stack) and stack[-1][0] in {"svg", "g"} and tag != "svg"),
            "SVG_STRUCTURE",
        )
        elements += 1
        require(elements <= 2048 and len(stack) < 16, "SVG_TREE_LIMIT")
        count, matrix, transforms_count = _attributes(tag, attrs)
        commands += count
        transform_count += transforms_count
        require(commands <= MAX_COMMANDS, "SVG_COMMAND_LIMIT")
        require(transform_count <= 256, "SVG_TRANSFORM_LIMIT")
        if not stack:
            dimensions = [
                parse_numbers(attrs[key].removesuffix("px"))[0]
                for key in ("width", "height")
            ]
            require(
                all(
                    number.is_integer() and 1 <= number <= 1024 for number in dimensions
                ),
                "SVG_DIMENSIONS",
            )
            width, height = map(int, dimensions)
            require(
                (width, height) == (expected.width, expected.height), "SVG_DIMENSIONS"
            )
        stack.append((tag, matrix_product(stack[-1][1] if stack else IDENTITY, matrix)))

    def end(_name: str) -> None:
        stack.pop()

    def content(value: str) -> None:
        require(not value.strip(WSP), "SVG_TEXT")

    def forbidden(*_args) -> None:
        raise SvgValidationError("SVG_XML_FORBIDDEN")

    def declaration(version: str, encoding: str | None, _standalone: int) -> None:
        require(
            version == "1.0" and (encoding is None or encoding.upper() == "UTF-8"),
            "SVG_ENCODING",
        )

    parser.StartElementHandler = start
    parser.EndElementHandler = end
    parser.CharacterDataHandler = content
    parser.StartDoctypeDeclHandler = forbidden
    parser.EntityDeclHandler = forbidden
    parser.ProcessingInstructionHandler = forbidden
    parser.XmlDeclHandler = declaration
    parser.StartNamespaceDeclHandler = lambda _prefix, uri: require(
        uri in {None, "", SVG_NS}, "SVG_NAMESPACE"
    )
    parser.SetParamEntityParsing(expat.XML_PARAM_ENTITY_PARSING_NEVER)
    try:
        parser.Parse(data, True)
    except expat.ExpatError as error:
        raise SvgValidationError("SVG_XML_SYNTAX") from error
    require(elements > 0 and not stack, "SVG_STRUCTURE")
    return SvgInspection(width, height, elements, commands)
