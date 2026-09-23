import base64
import json
from pathlib import Path

from PIL import Image
import pytest

from src.appearance.png import inspect_png
from src.appearance.png_structure import PngValidationError
from tests.test_png_inspection import blob


CASES = json.loads(
    (Path(__file__).resolve().parents[2] / "contracts/next/png.json").read_text()
)


@pytest.mark.parametrize("case", CASES, ids=lambda case: case["name"])
def test_shared_png_bytes(case, monkeypatch):
    data = base64.b64decode(case["png"], validate=True)
    expected = blob(data, case["width"], case["height"])
    if "error" in case:
        with pytest.raises(PngValidationError, match=case["error"]):
            inspect_png(data, expected)
    else:
        original = Image.Image.tobytes
        captured = []

        def record(image, *args, **kwargs):
            result = original(image, *args, **kwargs)
            captured.append((image.mode, result))
            return result

        monkeypatch.setattr(Image.Image, "tobytes", record)
        inspected = inspect_png(data, expected)
        assert (inspected.width, inspected.height) == (case["width"], case["height"])
        assert captured[-1] == ("RGBA", bytes(case["rgba"]))
