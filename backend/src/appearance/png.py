"""Actual bounded PNG pixel decoding after independent framing/deflate checks."""

from io import BytesIO
from typing import BinaryIO

from PIL import Image, UnidentifiedImageError

from src.appearance.input import read_icon_bytes
from src.appearance.png_structure import (
    PngInspection,
    PngValidationError,
    inspect_png_structure,
)
from src.v2.appearance import IconBlob


def inspect_png(data: bytes, expected: IconBlob) -> PngInspection:
    inspected = inspect_png_structure(data, expected)
    try:
        with Image.open(BytesIO(data), formats=("PNG",)) as decoded:
            if (
                decoded.size != (inspected.width, inspected.height)
                or getattr(decoded, "n_frames", 1) != 1
            ):
                raise PngValidationError("PNG_DECODE")
            # open/verify are not pixel decoding. Do not enable truncated input
            # or disable Pillow's own safety limits as a workaround for failure.
            decoded.load()
            with decoded.convert("RGBA") as pixels:
                if len(pixels.tobytes()) != inspected.width * inspected.height * 4:
                    raise PngValidationError("PNG_DECODE")
    except (UnidentifiedImageError, OSError, ValueError) as error:
        if isinstance(error, PngValidationError):
            raise
        raise PngValidationError("PNG_DECODE") from error
    return inspected


def inspect_png_stream(source: BinaryIO, expected: IconBlob) -> PngInspection:
    """Read once with bounds, then preflight and decode; caller owns the stream."""
    if expected.media_type != "image/png":
        raise PngValidationError("PNG_MEDIA_TYPE")
    return inspect_png(read_icon_bytes(source, expected), expected)
