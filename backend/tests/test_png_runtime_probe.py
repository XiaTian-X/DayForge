"""Test the exact CI image probe without invoking Docker on this machine."""

import json
from pathlib import Path
import runpy

import pytest


ROOT = Path(__file__).resolve().parents[2]
VERIFY = runpy.run_path(str(ROOT / "tools/verify_container.py"))["verify_png_vectors"]
CASES = json.loads((ROOT / "contracts/next/png.json").read_text())


def test_runtime_image_probe_executes_actual_shared_vectors():
    VERIFY(ROOT / "contracts/next/png.json")


@pytest.mark.parametrize("fault", ["empty", "pixels", "error", "accepted"])
def test_runtime_probe_cannot_hide_empty_or_incorrect_results(tmp_path, fault):
    valid = next(case for case in CASES if "rgba" in case)
    invalid = next(case for case in CASES if "error" in case)
    if fault == "empty":
        cases = []
        message = "Missing PNG vectors"
    elif fault == "pixels":
        cases = [dict(valid, rgba=[0] * len(valid["rgba"]))]
        message = "PNG pixels changed"
    elif fault == "error":
        cases = [dict(invalid, error="not-the-expected-category")]
        message = "Unexpected PNG rejection"
    else:
        cases = [dict(valid, error="PNG_SIGNATURE")]
        message = "Invalid PNG accepted"
    path = tmp_path / "synthetic-vectors.json"
    path.write_text(json.dumps(cases))
    with pytest.raises(RuntimeError, match=message):
        VERIFY(path)
