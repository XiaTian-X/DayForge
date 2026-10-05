"""Whole hosted-job capacity must not weaken full-suite or native deadlines."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]


class CiCapacityTest(unittest.TestCase):
    def job(self, name):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text()
        match = re.search(
            rf"^  {name}:\n(.*?)(?=^  [a-z_]+:|\Z)",
            workflow,
            flags=re.MULTILINE | re.DOTALL,
        )
        self.assertIsNotNone(match, f"missing {name} CI job")
        return match.group(1)

    def test_backend_whole_job_has_explicit_bounded_capacity(self):
        backend = self.job("backend")
        self.assertIn("    timeout-minutes: 25\n", backend)
        self.assertIn("    needs: changes\n", backend)
        self.assertIn("needs.changes.outputs.backend == 'true'", backend)
        self.assertIn("后端作业上限 25 分钟", (ROOT / "docs/TESTING.md").read_text())

    def test_backend_still_runs_full_locked_serial_warning_gate(self):
        backend = self.job("backend")
        self.assertIn("        run: ./tools/verify backend\n", backend)
        for bypass in ("continue-on-error", "strategy:", "matrix:", "pytest ", "-k "):
            with self.subTest(bypass=bypass):
                self.assertNotIn(bypass, backend)
        verify = (ROOT / "tools/verify").read_text()
        self.assertIn("uv sync --frozen", verify)
        self.assertIn("uv run --frozen python -m pytest -p tests.warning_budget", verify)

    def test_android_job_and_case_deadlines_are_unchanged(self):
        android = self.job("android")
        self.assertIn("    timeout-minutes: 25\n", android)
        self.assertIn("        timeout-minutes: 20\n", android)
        self.assertIn("        run: ./tools/verify android-build\n", android)
        runner = (ROOT / "tools/run_android_tests.py").read_text()
        self.assertIn(
            '"-Pandroid.testInstrumentationRunnerArguments.timeout_msec=150000"', runner
        )
        self.assertRegex(runner, r"\n\s+900,\n")


if __name__ == "__main__":
    unittest.main()
