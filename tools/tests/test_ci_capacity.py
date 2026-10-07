"""Approved whole-job/shard capacity must preserve full-suite and case guards."""

from pathlib import Path
import re
import unittest

from tools.run_android_tests import SHARDS, SHARD_TIMEOUT_SECONDS


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

    def test_android_hosted_job_and_case_deadlines_are_unchanged(self):
        android = self.job("android")
        self.assertIn("    timeout-minutes: 25\n", android)
        self.assertIn("        timeout-minutes: 20\n", android)
        self.assertIn("        run: ./tools/verify android-build\n", android)
        runner = (ROOT / "tools/run_android_tests.py").read_text()
        self.assertIn(
            '"-Pandroid.testInstrumentationRunnerArguments.timeout_msec=150000"', runner
        )

    def test_physical_shard_capacity_is_explicit_and_user_approved(self):
        self.assertEqual(3, SHARDS)
        self.assertEqual(1200, SHARD_TIMEOUT_SECONDS)
        runner = (ROOT / "tools/run_android_tests.py").read_text()
        self.assertRegex(runner, r"\n\s+SHARD_TIMEOUT_SECONDS,\n")
        testing = (ROOT / "docs/TESTING.md").read_text()
        self.assertIn("每批 Android 测试任务/包装进程上限 20 分钟", testing)
        self.assertIn("真机测试单项上限 150 秒", testing)


if __name__ == "__main__":
    unittest.main()
