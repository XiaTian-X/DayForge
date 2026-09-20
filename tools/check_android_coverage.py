#!/usr/bin/env python3
"""Reject physical-device coverage reports that omit exercised application classes."""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# Instrumentation calibration, not percentage targets or proof of full coverage.
CANARIES = (
    "com/dayforge/util/NumericInputUtils",
    "com/dayforge/data/repository/MetricRepository",
    "com/dayforge/domain/service/TimerService",
)


def validate(report: Path) -> list[str]:
    try:
        root = ET.parse(report).getroot()
    except (OSError, ET.ParseError) as error:
        return [f"Cannot read Android coverage report: {error}"]
    classes = {node.get("name"): node for node in root.findall("./package/class")}
    errors = []
    for name in CANARIES:
        node = classes.get(name)
        counter = None if node is None else node.find("./counter[@type='LINE']")
        try:
            covered = 0 if counter is None else int(counter.get("covered", "0"))
        except ValueError:
            covered = 0
        if covered <= 0:
            errors.append(
                f"Coverage calibration failed: {name} has no covered source lines"
            )
    return errors


def main() -> int:
    report = (
        Path(sys.argv[1])
        if len(sys.argv) > 1
        else Path(__file__).resolve().parents[1]
        / "android/app/build/reports/coverage/androidTest/deviceTest/connected/report.xml"
    )
    errors = validate(report)
    for error in errors:
        print(error, file=sys.stderr)
    if not errors:
        print(
            "Android coverage calibration passed (device input validation, Room repository, TimerService)."
        )
    return int(bool(errors))


if __name__ == "__main__":
    raise SystemExit(main())
