import hashlib
import base64
from io import BytesIO
import json
from pathlib import Path
from unittest.mock import Mock

import pytest

from src.appearance.input import ImageInputError, read_icon_bytes
from src.appearance.png import inspect_png_stream
from src.appearance.png_structure import PngValidationError
from src.appearance.svg import inspect_svg_stream
from src.appearance.svg_path import SvgValidationError
from src.v2.appearance import IconBlob


def description(data: bytes, media_type="image/png") -> IconBlob:
    return IconBlob(
        sha256=hashlib.sha256(data).hexdigest(),
        byte_length=len(data),
        media_type=media_type,
        width=1,
        height=1,
    )


class Fragmented(BytesIO):
    def __init__(self, data: bytes, fragment=17):
        super().__init__(data)
        self.fragment = fragment
        self.requests: list[int] = []

    def read(self, size=-1):
        assert 0 < size <= 65_536, "never request unbounded input"
        self.requests.append(size)
        return super().read(min(size, self.fragment))


@pytest.mark.parametrize("size", [1, 65_535, 65_536, 65_537, 524_288, 2_097_152])
def test_exact_budgets_fragmentation_and_eof(size):
    data = b"a" * size
    source = Fragmented(data, fragment=3071)
    assert read_icon_bytes(source, description(data)) == data
    assert source.tell() == size
    assert source.requests[-1] == 1
    assert not source.closed  # lifetime belongs to the caller


@pytest.mark.parametrize("extra", [1, 65_536, 2_097_152])
def test_read_stops_at_first_extra_byte(extra):
    original = b"expected"
    source = Fragmented(original + b"x" * extra)
    with pytest.raises(ImageInputError, match="IMAGE_BYTE_LENGTH"):
        read_icon_bytes(source, description(original))
    assert source.tell() == len(original) + 1
    assert not source.closed


@pytest.mark.parametrize("short", [b"", b"a", b"abcd"])
def test_truncation_cannot_pass_with_declared_length(short):
    source = Fragmented(short)
    with pytest.raises(ImageInputError, match="IMAGE_BYTE_LENGTH"):
        read_icon_bytes(source, description(b"abcde"))
    assert not source.closed


def test_same_size_wrong_bytes_and_broken_read_contract():
    with pytest.raises(ImageInputError, match="IMAGE_HASH"):
        read_icon_bytes(BytesIO(b"abd"), description(b"abc"))
    for result in (None, "abc", b"too much"):
        source = Mock()
        source.read.return_value = result
        with pytest.raises(ImageInputError, match="IMAGE_READ_INVALID"):
            read_icon_bytes(source, description(b"abc"))
        source.read.assert_called_once_with(4)
        source.close.assert_not_called()


def test_transport_failure_and_cancellation_are_not_eof_or_format_errors():
    for error in (OSError("synthetic read failure"), KeyboardInterrupt()):
        source = Mock()
        source.read.side_effect = [b"a", error]
        with pytest.raises(type(error)) as caught:
            read_icon_bytes(source, description(b"abc"))
        assert caught.value is error
        source.close.assert_not_called()


def test_svg_has_same_byte_identity_boundary_and_result_is_frozen():
    data = b'<svg width="1" height="1"/>'
    source = Fragmented(data, fragment=1)
    result = read_icon_bytes(source, description(data, "image/svg+xml"))
    source.seek(0)
    source.write(b"x" * len(data))
    assert result == data


def test_svg_stream_entry_reaches_real_validator_after_bounded_read():
    data = b'<svg width="1" height="1"/>'
    source = Fragmented(data, fragment=1)
    inspected = inspect_svg_stream(source, description(data, "image/svg+xml"))
    assert (inspected.width, inspected.height, inspected.elements) == (1, 1, 1)
    assert not source.closed
    wrong_type = Fragmented(data)
    with pytest.raises(SvgValidationError, match="SVG_MEDIA_TYPE"):
        inspect_svg_stream(wrong_type, description(data))
    assert wrong_type.tell() == 0
    invalid = b'<svg width="1" height="1"><script/></svg>'
    with pytest.raises(SvgValidationError, match="SVG_ELEMENT"):
        inspect_svg_stream(Fragmented(invalid), description(invalid, "image/svg+xml"))


def test_png_stream_entry_reaches_real_decoder_and_never_rereads():
    cases = json.loads(
        (Path(__file__).resolve().parents[2] / "contracts/next/png.json").read_text()
    )
    case = next(case for case in cases if case["name"] == "rgba")
    data = base64.b64decode(case["png"], validate=True)
    source = Fragmented(data, fragment=1)
    inspected = inspect_png_stream(source, description(data))
    assert (inspected.width, inspected.height, inspected.color) == (1, 1, 6)
    assert source.tell() == len(data)
    assert not source.closed
    wrong_type = Fragmented(data)
    with pytest.raises(PngValidationError, match="PNG_MEDIA_TYPE"):
        inspect_png_stream(wrong_type, description(data, "image/svg+xml"))
    assert wrong_type.tell() == 0
    invalid = data[:-1] + bytes([data[-1] ^ 1])
    with pytest.raises(PngValidationError, match="PNG_CRC"):
        inspect_png_stream(Fragmented(invalid), description(invalid))
