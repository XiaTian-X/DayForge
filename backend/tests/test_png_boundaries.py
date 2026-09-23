import struct
import zlib

import pytest

from src.appearance.png import inspect_png
from src.appearance.png_structure import PngValidationError
from src.v2.appearance import IconBlob
from tests.test_png_inspection import SIGNATURE, blob, chunk, png


def test_description_must_match_actual_bytes():
    data = png()
    expected = blob(data)
    cases: list[tuple[dict[str, object], str]] = [
        ({"sha256": "0" * 64}, "PNG_HASH"),
        ({"byte_length": len(data) + 1}, "PNG_BYTE_LENGTH"),
        ({"width": 2}, "PNG_DIMENSIONS"),
        ({"height": 2}, "PNG_DIMENSIONS"),
        ({"media_type": "image/svg+xml"}, "PNG_MEDIA_TYPE"),
    ]
    for updates, code in cases:
        description = IconBlob.model_validate(expected.model_dump() | updates)
        with pytest.raises(PngValidationError, match=code):
            inspect_png(data, description)
    with pytest.raises(PngValidationError, match="PNG_BYTE_LENGTH"):
        inspect_png(b"", expected)
    invalid = b"not png"
    with pytest.raises(PngValidationError, match="PNG_SIGNATURE"):
        inspect_png(invalid, blob(invalid))


@pytest.mark.parametrize("size", [1, 1024])
def test_exact_canvas_budgets(size):
    data = png(width=size, height=size, raw=(b"\0" + b"\xff\0\0\xff" * size) * size)
    result = inspect_png(data, blob(data, size, size))
    assert result.width == result.height == size


@pytest.mark.parametrize(
    "depth,color,compression,filtering,interlace",
    [
        (3, 6, 0, 0, 0),
        (16, 3, 0, 0, 0),
        (8, 1, 0, 0, 0),
        (8, 6, 1, 0, 0),
        (8, 6, 0, 1, 0),
        (8, 6, 0, 0, 2),
    ],
)
def test_invalid_header_fields(depth, color, compression, filtering, interlace):
    data = SIGNATURE + chunk(
        b"IHDR",
        struct.pack(">IIBBBBB", 1, 1, depth, color, compression, filtering, interlace),
    )
    data += chunk(b"IDAT", zlib.compress(b"\0\xff\0\0\xff")) + chunk(b"IEND", b"")
    with pytest.raises(PngValidationError, match="PNG_HEADER"):
        inspect_png(data, blob(data))


def test_chunk_budget_inclusive_and_idat_can_be_split():
    accepted = png(after=chunk(b"tEXt", b"a\0") * 4093)
    assert inspect_png(accepted, blob(accepted)).width == 1
    rejected = png(after=chunk(b"tEXt", b"a\0") * 4094)
    with pytest.raises(PngValidationError, match="PNG_CHUNK_LIMIT"):
        inspect_png(rejected, blob(rejected))
    compressed = zlib.compress(b"\0\xff\0\0\xff")
    header = chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0))
    split = (
        SIGNATURE
        + header
        + chunk(b"IDAT", compressed[:3])
        + chunk(b"IDAT", compressed[3:])
        + chunk(b"IEND", b"")
    )
    assert inspect_png(split, blob(split)).width == 1
    interrupted = (
        SIGNATURE
        + header
        + chunk(b"IDAT", compressed[:3])
        + chunk(b"tEXt", b"a\0")
        + chunk(b"IDAT", compressed[3:])
        + chunk(b"IEND", b"")
    )
    with pytest.raises(PngValidationError, match="PNG_ORDER"):
        inspect_png(interrupted, blob(interrupted))


def test_metadata_limits_per_chunk_and_total():
    body = b"a\0" + b"x" * 262142
    accepted = png(after=chunk(b"tEXt", body) * 4)
    assert inspect_png(accepted, blob(accepted)).width == 1
    too_much = png(after=chunk(b"tEXt", body) * 4 + chunk(b"tEXt", b"a\0"))
    with pytest.raises(PngValidationError, match="PNG_METADATA_LIMIT"):
        inspect_png(too_much, blob(too_much))
    too_large = png(after=chunk(b"tEXt", body + b"x"))
    with pytest.raises(PngValidationError, match="PNG_METADATA_LIMIT"):
        inspect_png(too_large, blob(too_large))
    expanded = png(after=chunk(b"zTXt", b"a\0\0" + zlib.compress(b"x" * 262144)))
    assert inspect_png(expanded, blob(expanded)).width == 1


def test_byte_limit_inclusive_without_bypassing_metadata_budgets():
    # Many empty *deflate blocks*, not PNG chunks, form one valid tiny image.
    # This independently tests input size without making giant decoded pixels.
    empty_block = b"\0\0\0\xff\xff"
    pixels = b"\0\xff\0\0\xff"
    final_block = b"\x01\x05\0\xfa\xff" + pixels
    suffix = final_block + struct.pack(">I", zlib.adler32(pixels))
    overhead = len(png(compressed=b"\x78\x01" + suffix, after=chunk(b"tEXt", b"a\0")))
    count, padding = divmod(2_097_152 - overhead, len(empty_block))
    data = png(
        compressed=b"\x78\x01" + empty_block * count + suffix,
        after=chunk(b"tEXt", b"a\0" + b"x" * padding),
    )
    assert len(data) == 2_097_152
    assert inspect_png(data, blob(data)).width == 1
    with pytest.raises(PngValidationError, match="PNG_BYTE_LENGTH"):
        inspect_png(data + b"x", blob(data))
