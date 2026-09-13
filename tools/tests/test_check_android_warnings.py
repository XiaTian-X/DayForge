import json
from collections import Counter
from pathlib import Path
import tempfile
import unittest

from tools.check_android_warnings import find_growth, load_budget, parse_warnings


class AndroidWarningCheckTest(unittest.TestCase):
    def test_parses_supported_diagnostics_and_ignores_host_warning(self) -> None:
        warnings = parse_warnings(
            [
                "w: file:///workspace/Test.kt:12:4 Kotlin warning",
                "/workspace/res/values/strings.xml:2:3: Resource warning",
                "/workspace/Test.java:8: warning: Javac warning",
                "Warning: SDK processing versions differ",
            ]
        )

        self.assertEqual(
            warnings,
            {
                ("kotlin", "Kotlin warning"): 1,
                ("android-resource", "Resource warning"): 1,
                ("javac", "Javac warning"): 1,
            },
        )

    def test_unknown_and_excess_warnings_are_growth(self) -> None:
        known = ("kotlin", "known")
        unknown = ("javac", "new")

        self.assertEqual(
            find_growth(Counter({known: 3, unknown: 1}), {known: 2}),
            [(unknown, 1, 0), (known, 3, 2)],
        )

    def test_reduced_warning_count_is_allowed(self) -> None:
        warning = ("kotlin", "known")

        self.assertEqual(find_growth(Counter({warning: 1}), {warning: 2}), [])

    def test_duplicate_budget_entry_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "budget.json"
            path.write_text(
                json.dumps(
                    {
                        "version": 1,
                        "warnings": [
                            {"kind": "kotlin", "message": "same", "max_count": 1},
                            {"kind": "kotlin", "message": "same", "max_count": 2},
                        ],
                    }
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "duplicate"):
                load_budget(path)


if __name__ == "__main__":
    unittest.main()
