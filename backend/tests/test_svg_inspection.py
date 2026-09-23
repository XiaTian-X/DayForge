"""Independent shared vectors plus exact resource boundaries; no rendering claim."""

import hashlib
import json
from pathlib import Path

import pytest

from src.appearance.svg import inspect_svg
from src.appearance.svg_path import SvgValidationError, parse_numbers, parse_path
from src.v2.appearance import IconBlob


FIXTURE = json.loads(
    (Path(__file__).resolve().parents[2] / "contracts/next/svg.json").read_text()
)


def blob(data: bytes) -> IconBlob:
    return IconBlob(
        sha256=hashlib.sha256(data).hexdigest(),
        byte_length=len(data),
        media_type="image/svg+xml",
        width=24,
        height=24,
    )


def svg(body: str = "", attrs: str = "") -> bytes:
    return f'<svg width="24" height="24" {attrs}>{body}</svg>'.encode()


@pytest.mark.parametrize("case", FIXTURE["paths"], ids=lambda case: case["name"])
def test_shared_paths(case):
    if case.get("invalid"):
        with pytest.raises(SvgValidationError):
            parse_path(case["value"])
    else:
        actual = parse_path(case["value"])
        assert [[item.command, list(item.values)] for item in actual] == case[
            "commands"
        ]


@pytest.mark.parametrize("case", FIXTURE["svg"], ids=lambda case: case["name"])
def test_shared_documents(case):
    data = case["xml"].encode()
    if case.get("invalid"):
        with pytest.raises(SvgValidationError):
            inspect_svg(data, blob(data))
    else:
        result = inspect_svg(data, blob(data))
        assert (result.width, result.height) == (24, 24)
        assert (result.elements, result.commands) == (
            case["elements"],
            case["commands"],
        )


def test_actual_bytes_bound_to_description():
    data = svg()
    original = blob(data)
    cases: list[tuple[dict[str, object], str]] = [
        ({"media_type": "image/png"}, "SVG_MEDIA_TYPE"),
        ({"byte_length": len(data) + 1}, "SVG_BYTE_LENGTH"),
        ({"sha256": "0" * 64}, "SVG_HASH"),
        ({"width": 25}, "SVG_DIMENSIONS"),
        ({"height": 25}, "SVG_DIMENSIONS"),
    ]
    for updates, code in cases:
        expected = IconBlob.model_validate(original.model_dump() | updates)
        with pytest.raises(SvgValidationError, match=code):
            inspect_svg(data, expected)
    with pytest.raises(SvgValidationError, match="SVG_BYTE_LENGTH"):
        inspect_svg(b"", original)


@pytest.mark.parametrize(
    "data", [b"\xff", b"\xc0\xaf", svg().decode().encode("utf-16")]
)
def test_invalid_encodings(data):
    with pytest.raises(SvgValidationError):
        inspect_svg(data, blob(data))


def test_utf8_bom_allowed_and_hash_includes_it():
    data = b"\xef\xbb\xbf" + svg()
    assert inspect_svg(data, blob(data)).elements == 1
    with pytest.raises(SvgValidationError, match="SVG_BYTE_LENGTH"):
        inspect_svg(data, blob(data[3:]))


def test_exact_byte_limit_and_one_more():
    overhead = svg("<!---->")
    data = svg("<!--" + "a" * (524_288 - len(overhead)) + "-->")
    assert len(data) == 524_288
    assert inspect_svg(data, blob(data)).elements == 1
    with pytest.raises(SvgValidationError, match="SVG_BYTE_LENGTH"):
        inspect_svg(data + b" ", blob(data))


@pytest.mark.parametrize(
    "accepted,rejected,code",
    [
        (svg("<g/>" * 2047), svg("<g/>" * 2048), "SVG_TREE_LIMIT"),
        (
            svg("<g>" * 15 + "</g>" * 15),
            svg("<g>" * 16 + "</g>" * 16),
            "SVG_TREE_LIMIT",
        ),
        (
            svg('<path d="M0 0' + "L1 1" * 16_383 + '"/>'),
            svg('<path d="M0 0' + "L1 1" * 16_384 + '"/>'),
            "SVG_COMMAND_LIMIT",
        ),
        (
            svg(
                '<path d="M0 0'
                + "L1 1" * 8191
                + '"/>'
                + '<path d="M0 0'
                + "L1 1" * 8191
                + '"/>'
            ),
            svg(
                '<path d="M0 0'
                + "L1 1" * 8191
                + '"/>'
                + '<path d="M0 0'
                + "L1 1" * 8192
                + '"/>'
            ),
            "SVG_COMMAND_LIMIT",
        ),
        (
            svg(attrs='transform="' + "scale(1) " * 64 + '"'),
            svg(attrs='transform="' + "scale(1) " * 65 + '"'),
            "SVG_TRANSFORM_LIMIT",
        ),
        (
            svg(('<g transform="' + "scale(1) " * 64 + '"/>') * 4),
            svg(
                ('<g transform="' + "scale(1) " * 64 + '"/>') * 4
                + '<g transform="scale(1)"/>'
            ),
            "SVG_TRANSFORM_LIMIT",
        ),
    ],
    ids=[
        "elements",
        "depth",
        "path",
        "document-paths",
        "transforms",
        "document-transforms",
    ],
)
def test_inclusive_budgets(accepted, rejected, code):
    inspect_svg(accepted, blob(accepted))
    with pytest.raises(SvgValidationError, match=code):
        inspect_svg(rejected, blob(rejected))


def test_number_limits_and_full_consumption():
    assert parse_numbers("0" * 64) == (0,)
    assert parse_numbers("1-2 3-4", points=True) == (1, -2, 3, -4)
    assert parse_numbers("1, 2\n") == (1, 2)
    for value in ["0" * 65, "1,", "1 2, ", "1,,2", "1-2", "1.2.3", "1 nope"]:
        with pytest.raises(SvgValidationError):
            parse_numbers(value)
    assert len(parse_numbers("0 " * 32768)) == 32768
    with pytest.raises(SvgValidationError, match="SVG_NUMBER_COUNT"):
        parse_numbers("0 " * 32769)
    with pytest.raises(SvgValidationError, match="SVG_TEXT_LIMIT"):
        parse_path(" " * 524289)


@pytest.mark.parametrize("dimension", ["0", "-1", "1025", "24.5", "1em", "NaN"])
def test_canvas_dimensions(dimension):
    data = f'<svg width="{dimension}" height="24"/>'.encode()
    with pytest.raises(SvgValidationError):
        inspect_svg(data, blob(data))


@pytest.mark.parametrize("dimension", [1, 1024])
def test_inclusive_canvas_dimensions(dimension):
    data = f'<svg width="{dimension}" height="{dimension}"/>'.encode()
    expected = IconBlob.model_validate(
        blob(data).model_dump() | {"width": dimension, "height": dimension}
    )
    result = inspect_svg(data, expected)
    assert result.width == result.height == dimension
