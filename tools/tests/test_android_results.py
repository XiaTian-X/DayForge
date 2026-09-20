import tempfile
import unittest
from pathlib import Path

from tools.check_android_results import validate


def suite(cases="<testcase name='behavior' classname='PhysicalTest'/>", **counts):
    attributes = dict(tests=1, failures=0, errors=0, skipped=0) | counts
    return (
        "<testsuite "
        + " ".join(f"{key}='{value}'" for key, value in attributes.items())
        + ">"
        + cases
        + "</testsuite>"
    )


class AndroidResultsGateTest(unittest.TestCase):
    def check(self, *reports):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for index, report in enumerate(reports):
                (root / f"TEST-device-{index}.xml").write_text(report)
            return validate(root)

    def test_nonempty_successful_device_suites_pass(self):
        self.assertEqual([], self.check(suite(), suite()))

    def test_missing_empty_or_malformed_results_fail(self):
        self.assertTrue(self.check())
        for report in ("<testsuite>", "<report/>", "<testsuite/>", suite("", tests=0)):
            with self.subTest(report=report):
                self.assertTrue(self.check(report))

    def test_skipped_failed_or_errored_cases_fail_even_among_successful_suites(self):
        for counter, element in (
            ("skipped", "skipped"),
            ("failures", "failure"),
            ("errors", "error"),
        ):
            with self.subTest(counter=counter):
                failed = suite(
                    f"<testcase name='behavior'><{element}/></testcase>", **{counter: 1}
                )
                self.assertEqual(1, len(self.check(suite(), failed)))

    def test_case_results_cannot_be_hidden_by_zero_suite_counters(self):
        for element in ("skipped", "failure", "error"):
            with self.subTest(element=element):
                self.assertTrue(self.check(suite(f"<testcase><{element}/></testcase>")))

    def test_invalid_or_inconsistent_counters_fail(self):
        for counts in (
            {"tests": 2},
            {"tests": -1},
            {"tests": "invalid"},
            {"skipped": -1},
        ):
            with self.subTest(counts=counts):
                self.assertTrue(self.check(suite(**counts)))
