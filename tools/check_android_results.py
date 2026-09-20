#!/usr/bin/env python3
"""Reject empty, skipped or inconsistent physical instrumentation results."""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def validate(directory: Path) -> list[str]:
    reports = sorted(directory.glob("TEST-*.xml"))
    if not reports:
        return ["No physical Android test reports found"]
    errors = []
    for report in reports:
        try:
            suite = ET.parse(report).getroot()
            if suite.tag != "testsuite":
                raise ValueError("Expected a JUnit testsuite")
            counts = {
                key: int(suite.attrib[key])
                for key in ("tests", "failures", "errors", "skipped")
            }
            cases = suite.findall("testcase")
            if counts["tests"] <= 0 or counts["tests"] != len(cases):
                raise ValueError(
                    "Test count is empty or does not match testcase entries"
                )
            if any(counts[key] != 0 for key in ("failures", "errors", "skipped")):
                raise ValueError(
                    "Physical Android tests contain failures, errors or skipped cases"
                )
            if any(
                case.find(kind) is not None
                for case in cases
                for kind in ("failure", "error", "skipped")
            ):
                raise ValueError(
                    "A testcase failed or was skipped despite the suite counters"
                )
        except (OSError, ET.ParseError, KeyError, ValueError) as error:
            errors.append(f"{report.name}: {error}")
    return errors


def main() -> int:
    directory = (
        Path(sys.argv[1])
        if len(sys.argv) > 1
        else Path(__file__).resolve().parents[1]
        / "android/app/build/outputs/androidTest-results/connected/deviceTest"
    )
    errors = validate(directory)
    for error in errors:
        print(error, file=sys.stderr)
    if not errors:
        print(
            "Physical Android result checks passed (nonempty, no failures/errors/skips)."
        )
    return int(bool(errors))


if __name__ == "__main__":
    raise SystemExit(main())
