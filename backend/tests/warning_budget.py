"""Pytest plugin that prevents unreviewed warning growth."""

from __future__ import annotations

from collections import Counter
import json
from pathlib import Path
import re
from typing import TypeAlias

import pytest


WarningKey: TypeAlias = tuple[str, str]
WARNING_BUDGET_PATH = Path(__file__).resolve().parents[1] / "warning-budget.json"
_observed: Counter[WarningKey] = Counter()


def normalize_message(message: str) -> str:
    return re.sub(r"\s+", " ", message).strip()


def warning_key(category: type[Warning], message: str) -> WarningKey:
    return (
        f"{category.__module__}.{category.__qualname__}",
        normalize_message(message),
    )


def load_budget(path: Path = WARNING_BUDGET_PATH) -> dict[WarningKey, int]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if payload.get("version") != 1 or not isinstance(payload.get("warnings"), list):
        raise ValueError("warning budget must contain version 1 and a warnings list")

    budget: dict[WarningKey, int] = {}
    for entry in payload["warnings"]:
        key = (entry["category"], normalize_message(entry["message"]))
        maximum = entry["max_count"]
        if key in budget:
            raise ValueError(f"duplicate warning budget entry: {key!r}")
        if not isinstance(maximum, int) or maximum < 1:
            raise ValueError(f"invalid max_count for {key!r}: {maximum!r}")
        budget[key] = maximum
    return budget


def find_growth(
    observed: Counter[WarningKey], budget: dict[WarningKey, int]
) -> list[tuple[WarningKey, int, int]]:
    return sorted(
        (key, count, budget.get(key, 0))
        for key, count in observed.items()
        if count > budget.get(key, 0)
    )


def pytest_load_initial_conftests() -> None:
    _observed.clear()


def pytest_warning_recorded(warning_message: object, **_: object) -> None:
    category = getattr(warning_message, "category")
    message = getattr(warning_message, "message")
    _observed[warning_key(category, str(message))] += 1


@pytest.hookimpl(trylast=True)
def pytest_sessionfinish(session: pytest.Session) -> None:
    try:
        budget = load_budget()
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        session.config.issue_config_time_warning(
            pytest.PytestConfigWarning(f"Invalid pytest warning budget: {error}"),
            stacklevel=2,
        )
        if session.exitstatus == pytest.ExitCode.OK:
            session.exitstatus = pytest.ExitCode.TESTS_FAILED
        return

    reporter = session.config.pluginmanager.get_plugin("terminalreporter")
    growth = find_growth(_observed, budget)
    stale = [
        (key, _observed.get(key, 0), maximum)
        for key, maximum in sorted(budget.items())
        if _observed.get(key, 0) < maximum
    ]

    if reporter:
        reporter.write_sep("=", "warning budget")
        reporter.write_line(
            f"{sum(_observed.values())} observed, {sum(budget.values())} allowed"
        )
        for (category, message), actual, maximum in growth:
            reporter.write_line(
                f"EXCEEDED [{category}] {actual} observed, {maximum} allowed: {message}",
                red=True,
            )
        if stale:
            reporter.write_line(
                "Some counts are below budget. Reduce the baseline after a full test run.",
                yellow=True,
            )

    if growth and session.exitstatus == pytest.ExitCode.OK:
        session.exitstatus = pytest.ExitCode.TESTS_FAILED
