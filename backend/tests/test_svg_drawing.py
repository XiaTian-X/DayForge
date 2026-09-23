"""Validate shared drawable documents; pixel assertions belong to native Android."""

import json
from pathlib import Path

import pytest

from src.appearance.svg import inspect_svg
from src.appearance.svg_path import SvgValidationError
from tests.test_svg_inspection import blob, svg


CASES = json.loads(
    (
        Path(__file__).resolve().parents[2] / "contracts/next/svg-drawing.json"
    ).read_text()
)


@pytest.mark.parametrize("case", CASES, ids=lambda case: case["name"])
def test_draw_preflight_documents(case):
    data = svg(case.get("body", ""), case.get("attrs", ""))
    if "error" in case:
        with pytest.raises(SvgValidationError, match=case["error"]):
            inspect_svg(data, blob(data))
    else:
        assert inspect_svg(data, blob(data)).width == 24


def test_dash_work_inclusive_and_document_aggregate():
    def line(length):
        return f'<line x2="{length}" stroke="#000" stroke-dasharray="1 1"/>'

    accepted = svg(line(65534))  # (ceil(65534 / 2) + 1 contour) * 2 = 65536
    inspect_svg(accepted, blob(accepted))
    for body in (line(65535), line(32768) * 2):
        data = svg(body)
        with pytest.raises(SvgValidationError, match="SVG_DASH_LIMIT"):
            inspect_svg(data, blob(data))


def test_viewport_coefficient_inclusive_and_ancestor_product():
    accepted = svg(attrs='viewBox="0 0 .000024 .000024"')
    inspect_svg(accepted, blob(accepted))
    for attrs, body in [
        ('viewBox="0 0 .000023999 .000024" preserveAspectRatio="none"', ""),
        ('viewBox="0 0 .000024 .000024"', '<g transform="scale(1.000001)"/>'),
        ('viewBox="1 0 .000024 .000024" transform="translate(-1)"', ""),
    ]:
        data = svg(body, attrs)
        with pytest.raises(SvgValidationError, match="SVG_TRANSFORM_LIMIT"):
            inspect_svg(data, blob(data))
