#!/usr/bin/env python3
"""Reject Android compiler/resource warnings outside the reviewed warning budget."""

from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys
from typing import Iterable


WarningKey = tuple[str, str]

KOTLIN_WARNING = re.compile(r"^w: .+?:\d+:\d+ (?P<message>.+)$")
RESOURCE_WARNING = re.compile(
    r"^.+?[\\/]res[\\/].+?:\d+:\d+: (?P<message>.+)$"
)
JAVAC_WARNING = re.compile(r"^.+?:\d+: warning: (?P<message>.+)$")


def normalize_message(message: str) -> str:
    return " ".join(message.split())


def parse_warnings(lines: Iterable[str]) -> Counter[WarningKey]:
    warnings: Counter[WarningKey] = Counter()
    for raw_line in lines:
        line = raw_line.rstrip("\n")
        for kind, pattern in (
            ("kotlin", KOTLIN_WARNING),
            ("android-resource", RESOURCE_WARNING),
            ("javac", JAVAC_WARNING),
        ):
            match = pattern.match(line)
            if match:
                warnings[(kind, normalize_message(match.group("message")))] += 1
                break
    return warnings


def load_budget(path: Path) -> dict[WarningKey, int]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if payload.get("version") != 1 or not isinstance(payload.get("warnings"), list):
        raise ValueError("warning budget must contain version 1 and a warnings list")

    budget: dict[WarningKey, int] = {}
    for entry in payload["warnings"]:
        key = (entry["kind"], normalize_message(entry["message"]))
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


def print_observed(observed: Counter[WarningKey]) -> None:
    payload = {
        "version": 1,
        "warnings": [
            {"kind": kind, "message": message, "max_count": count}
            for (kind, message), count in sorted(observed.items())
        ],
    }
    print(json.dumps(payload, ensure_ascii=False, indent=2))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("log", type=Path, help="Gradle output captured from a clean build")
    parser.add_argument(
        "--budget",
        type=Path,
        help="reviewed JSON budget (defaults to android/compiler-warning-budget.json)",
    )
    parser.add_argument(
        "--print-observed",
        action="store_true",
        help="print the normalized warnings as a candidate JSON budget",
    )
    args = parser.parse_args()

    observed = parse_warnings(args.log.read_text(encoding="utf-8").splitlines())
    if args.print_observed:
        print_observed(observed)
        return 0

    budget_path = (
        args.budget
        or Path(__file__).resolve().parents[1]
        / "android"
        / "compiler-warning-budget.json"
    )
    try:
        budget = load_budget(budget_path)
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"Invalid Android compiler warning budget: {error}", file=sys.stderr)
        return 2

    growth = find_growth(observed, budget)
    if growth:
        print("Android compiler/resource warning budget exceeded:", file=sys.stderr)
        for (kind, message), actual, maximum in growth:
            print(
                f"  [{kind}] {actual} observed, {maximum} allowed: {message}",
                file=sys.stderr,
            )
        return 1

    stale = [
        (key, observed.get(key, 0), maximum)
        for key, maximum in sorted(budget.items())
        if observed.get(key, 0) < maximum
    ]
    print(
        f"Android warning budget passed: {sum(observed.values())} observed, "
        f"{sum(budget.values())} allowed."
    )
    if stale:
        print(
            "Warning counts below budget were observed; reduce the budget in the same PR "
            "when the build was clean and fully recompiled."
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
