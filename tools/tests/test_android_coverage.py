import tempfile
import unittest
from pathlib import Path

from tools.check_android_coverage import CANARIES, validate


class AndroidCoverageCalibrationTest(unittest.TestCase):
    def check(self, classes):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.xml"
            path.write_text(
                "<report><package name='com/dayforge'>"
                + classes
                + "</package></report>"
            )
            return validate(path)

    def test_executed_application_classes_pass(self):
        classes = "".join(
            f"<class name='{name}'><counter type='LINE' missed='200' covered='1'/></class>"
            for name in CANARIES
        )
        self.assertEqual([], self.check(classes))

    def test_generated_classes_cannot_hide_missing_application_classes(self):
        classes = "".join(
            f"<class name='{name}$Generated'><counter type='LINE' missed='0' covered='500'/></class>"
            for name in CANARIES
        )
        self.assertEqual(len(CANARIES), len(self.check(classes)))

    def test_missing_service_coverage_fails_even_with_other_coverage(self):
        classes = f"<class name='{CANARIES[0]}'><counter type='LINE' missed='0' covered='50'/></class>"
        classes += f"<class name='{CANARIES[1]}'><counter type='LINE' missed='200' covered='0'/></class>"
        self.assertEqual(2, len(self.check(classes)))

    def test_unreadable_or_malformed_report_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.xml"
            self.assertTrue(validate(path))
            path.write_text("<report>")
            self.assertTrue(validate(path))
