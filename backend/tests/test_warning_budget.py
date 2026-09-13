from collections import Counter
import json

import pytest

from tests.warning_budget import find_growth, load_budget, normalize_message


def test_normalize_message_makes_line_wrapping_stable() -> None:
    assert normalize_message("one\n  two\tthree") == "one two three"


def test_find_growth_rejects_unknown_and_excess_warnings() -> None:
    known = ("builtins.UserWarning", "known")
    unknown = ("builtins.RuntimeWarning", "new")
    observed = Counter({known: 3, unknown: 1})

    assert find_growth(observed, {known: 2}) == [
        (unknown, 1, 0),
        (known, 3, 2),
    ]


def test_find_growth_allows_warning_reduction() -> None:
    known = ("builtins.UserWarning", "known")

    assert find_growth(Counter({known: 1}), {known: 2}) == []


def test_load_budget_rejects_duplicate_entries(tmp_path) -> None:
    budget_path = tmp_path / "warning-budget.json"
    budget_path.write_text(
        json.dumps(
            {
                "version": 1,
                "warnings": [
                    {"category": "example.Warning", "message": "same", "max_count": 1},
                    {"category": "example.Warning", "message": "same", "max_count": 2},
                ],
            }
        ),
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="duplicate"):
        load_budget(budget_path)
