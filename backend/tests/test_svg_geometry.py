import json
import math
from pathlib import Path

import pytest

from src.appearance.svg_geometry import resolve_path
from src.appearance.svg_path import SvgValidationError


CASES = json.loads(
    (
        Path(__file__).resolve().parents[2] / "contracts/next/svg-geometry.json"
    ).read_text()
)


@pytest.mark.parametrize("case", CASES, ids=lambda case: case["name"])
def test_shared_absolute_geometry(case):
    if "error" in case:
        with pytest.raises(SvgValidationError, match=case["error"]):
            resolve_path(case["d"])
        return
    result = resolve_path(case["d"])
    assert result.contours == case["contours"]
    assert result.source_commands == case["source_commands"]
    assert math.isfinite(result.length_bound) and result.length_bound >= 0
    if "commands" in case:
        assert len(result.commands) == len(case["commands"])
        for actual, expected in zip(result.commands, case["commands"], strict=True):
            assert actual.command == expected[0]
            assert actual.values == pytest.approx(expected[1:], abs=1e-10)
    else:
        curves = result.commands[1:]
        assert result.commands[0].command == "M"
        assert len(curves) == len(case["curve_ends"])
        for actual, end in zip(curves, case["curve_ends"], strict=True):
            assert actual.command == "C"
            assert actual.values[-2:] == pytest.approx(end, abs=1e-10)
        assert curves[0].values[:2] == pytest.approx(case["first_control"], abs=1e-10)


def test_control_polygon_length_bound_and_moveto_discontinuities():
    result = resolve_path(
        "M0 0 L3 4 Z M100 100 Q103 104 106 100 C109 104 112 100 115 104"
    )
    assert result.length_bound == 35
    assert result.contours == 2
    assert result.source_commands == 6


def test_corrected_radius_inclusive_and_relative_accumulation():
    result = resolve_path("M0 0 A1000000 0.000001 0 0 1 0 2")
    assert (
        max(abs(value) for command in result.commands for value in command.values)
        == 1e12
    )
    repeated = resolve_path("M0 0" + "l1000000 0" * 16383)
    assert repeated.commands[-1].values == (16_383_000_000, 0)
    assert repeated.length_bound == 16_383_000_000
    assert repeated.source_commands == 16384


def test_full_arc_expansion_remains_bounded_and_ends_exactly():
    # Each almost-full large arc needs eight cubics. The endpoint must not drift.
    result = resolve_path("M0 0 A10 10 0 1 1 0.001 0 S1 1 2 2")
    assert len(result.commands) == 10
    assert result.commands[-2].values[-2:] == (0.001, 0)
    assert result.commands[-1].values[:2] == (0.001, 0)
