"""Synthetic PNG byte streams have independently specified scanlines and pixels."""

import hashlib
import struct
import zlib

from PIL import Image, PngImagePlugin
import pytest

from src.appearance.png import inspect_png
from src.appearance.png_structure import PngValidationError, inflate
from src.v2.appearance import IconBlob


SIGNATURE = b"\x89PNG\r\n\x1a\n"


def chunk(kind: bytes, body: bytes) -> bytes:
    return (
        struct.pack(">I", len(body))
        + kind
        + body
        + struct.pack(">I", zlib.crc32(kind + body))
    )


def png(
    raw=b"\0\xff\0\0\xff",
    *,
    width=1,
    height=1,
    depth=8,
    color=6,
    interlace=0,
    before=b"",
    after=b"",
    compressed=None,
):
    return (
        SIGNATURE
        + chunk(
            b"IHDR",
            struct.pack(">IIBBBBB", width, height, depth, color, 0, 0, interlace),
        )
        + before
        + chunk(b"IDAT", zlib.compress(raw) if compressed is None else compressed)
        + after
        + chunk(b"IEND", b"")
    )


def blob(data: bytes, width=1, height=1) -> IconBlob:
    return IconBlob(
        sha256=hashlib.sha256(data).hexdigest(),
        byte_length=len(data),
        media_type="image/png",
        width=width,
        height=height,
    )


@pytest.mark.parametrize(
    "data,expected_pixels",
    [
        (png(), b"\xff\0\0\xff"),
        (png(raw=b"\0\0\xff\0", color=2), b"\0\xff\0\xff"),
        (png(raw=b"\0\x80", color=0), b"\x80\x80\x80\xff"),
        (png(raw=b"\0\x80\x40", color=4), b"\x80\x80\x80\x40"),
        (png(raw=b"\0\x80", color=0, depth=1), b"\xff\xff\xff\xff"),
        (
            png(
                raw=b"\0\x80",
                color=3,
                depth=1,
                before=chunk(b"PLTE", b"\0\0\0\xff\0\0") + chunk(b"tRNS", b"\xff\x80"),
            ),
            b"\xff\0\0\x80",
        ),
        (
            png(raw=b"\0\0\xff\0", color=2, before=chunk(b"tRNS", b"\0\0\0\xff\0\0")),
            b"\0\xff\0\0",
        ),
        (png(interlace=1), b"\xff\0\0\xff"),
    ],
    ids=[
        "rgba",
        "rgb",
        "gray",
        "gray-alpha",
        "packed-gray",
        "palette-alpha",
        "rgb-key",
        "adam7",
    ],
)
def test_real_pixel_decode_preserves_values(data, expected_pixels, monkeypatch):
    original = Image.Image.tobytes
    captured = []

    def record(image, *args, **kwargs):
        result = original(image, *args, **kwargs)
        captured.append((image.mode, result))
        return result

    monkeypatch.setattr(Image.Image, "tobytes", record)
    inspected = inspect_png(data, blob(data))
    assert (inspected.width, inspected.height) == (1, 1)
    assert captured[-1] == ("RGBA", expected_pixels)


def test_decoder_failure_cannot_be_reported_as_structural_success(monkeypatch):
    def failure(_image, *args, **kwargs):
        raise OSError("synthetic native decoder failure")

    monkeypatch.setattr(PngImagePlugin.PngImageFile, "load", failure)
    data = png()
    with pytest.raises(PngValidationError, match="PNG_DECODE"):
        inspect_png(data, blob(data))


@pytest.mark.parametrize(
    "data,code",
    [
        (png()[:-1], "PNG_TRUNCATED"),
        (png() + b"extra", "PNG_END"),
        (png()[:-12], "PNG_END"),
        (png()[:-1] + b"\0", "PNG_CRC"),
        (png(before=chunk(b"acTL", b"\0" * 8)), "PNG_ANIMATION"),
        (png(before=chunk(b"fcTL", b"\0" * 26)), "PNG_ANIMATION"),
        (png(after=chunk(b"fdAT", b"\0" * 4)), "PNG_ANIMATION"),
        (png(before=chunk(b"FAIL", b"")), "PNG_CHUNK"),
        (png(before=chunk(b"sRGB", b"\0") * 2), "PNG_DUPLICATE"),
        (png(after=chunk(b"gAMA", struct.pack(">I", 45455))), "PNG_ORDER"),
        (png(compressed=zlib.compress(b"\0\xff\0\0\xff") + b"x"), "PNG_DEFLATE"),
        (png(compressed=zlib.compress(b"\0\xff\0\0\xff")[:-1]), "PNG_DEFLATE"),
        (png(raw=b"\0\xff"), "PNG_SCANLINES"),
        (png(raw=b"\0" * 6), "PNG_EXPANSION_LIMIT"),
        (png(raw=b"\x05\xff\0\0\xff"), "PNG_FILTER"),
        (png(color=3, raw=b"\0\0"), "PNG_ORDER"),
        (
            png(color=3, raw=b"\0\x01", before=chunk(b"PLTE", b"\xff\0\0")),
            "PNG_PALETTE",
        ),
        (
            png(color=3, raw=b"\x01\x01", before=chunk(b"PLTE", b"\xff\0\0")),
            "PNG_PALETTE",
        ),
        (png(before=chunk(b"tRNS", b"\0")), "PNG_TRANSPARENCY"),
        (
            png(before=chunk(b"zTXt", b"label\0\0" + zlib.compress(b"a" * 262145))),
            "PNG_EXPANSION_LIMIT",
        ),
        (
            png(
                before=chunk(
                    b"iTXt", b"label\0\x01\0\0\0" + zlib.compress(b"a" * 262145)
                )
            ),
            "PNG_EXPANSION_LIMIT",
        ),
        (
            png(before=chunk(b"iCCP", b"profile\0\0" + zlib.compress(b"a" * 262145))),
            "PNG_EXPANSION_LIMIT",
        ),
        (
            png(before=chunk(b"iCCP", b"profile\0\0" + zlib.compress(b"invalid"))),
            "PNG_METADATA",
        ),
        (
            png(before=chunk(b"zTXt", b" leading\0\0" + zlib.compress(b"safe"))),
            "PNG_METADATA",
        ),
        (png(before=chunk(b"iTXt", b"label\0\0\0\0\0\xff")), "PNG_METADATA"),
    ],
    ids=[
        "truncated",
        "trailing",
        "missing-end",
        "crc",
        "actl",
        "fctl",
        "fdat",
        "unknown",
        "duplicate",
        "order",
        "deflate-trailing",
        "deflate-short",
        "scanlines-short",
        "scanlines-long",
        "filter",
        "missing-palette",
        "palette-index",
        "filtered-palette-index",
        "alpha-trns",
        "ztxt-bomb",
        "itxt-bomb",
        "iccp-bomb",
        "iccp-header",
        "keyword",
        "utf8",
    ],
)
def test_invalid_actual_bytes(data, code):
    with pytest.raises(PngValidationError, match=code):
        inspect_png(data, blob(data))


@pytest.mark.parametrize(
    "kind,body",
    [
        (b"tEXt", b"label\0safe text"),
        (b"zTXt", b"label\0\0" + zlib.compress(b"safe text")),
        (
            b"iTXt",
            b"label\0\x01\0zh-CN\0"
            + "名字".encode()
            + b"\0"
            + zlib.compress("内容".encode()),
        ),
        (b"iTXt", b"label\0\0\0\0\0"),
        (b"sRGB", b"\0"),
        (b"gAMA", struct.pack(">I", 45455)),
        (
            b"cHRM",
            struct.pack(">8I", 31270, 32900, 64000, 33000, 30000, 60000, 15000, 6000),
        ),
        (b"pHYs", struct.pack(">IIB", 72, 72, 0)),
        (b"sBIT", b"\x08" * 4),
        (b"bKGD", b"\0" * 6),
        (b"tIME", struct.pack(">HBBBBB", 2026, 9, 23, 1, 2, 3)),
    ],
)
def test_supported_metadata_is_bounded_but_preserved(kind, body):
    data = png(before=chunk(kind, body))
    original = bytes(data)
    assert inspect_png(data, blob(data)).color == 6
    assert data == original


def test_exact_inflate_limit_and_complete_stream():
    assert inflate(zlib.compress(b"abc"), 3) == b"abc"
    assert inflate(zlib.compress(b""), 0) == b""
    for compressed in [
        zlib.compress(b"abc") + zlib.compress(b""),
        zlib.compress(b"abc")[:-1],
    ]:
        with pytest.raises(PngValidationError, match="PNG_DEFLATE"):
            inflate(compressed, 3)
