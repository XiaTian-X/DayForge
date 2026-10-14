"""Approved whole-job/shard capacity must preserve full-suite and case guards."""

from pathlib import Path
import re
import unittest

from tools.run_android_tests import SHARDS, SHARD_TIMEOUT_SECONDS, TARGETED_TIMEOUT_SECONDS


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
        self.assert_backend_capacity(backend)
        self.assertIn("    needs: changes\n", backend)
        self.assertIn("needs.changes.outputs.backend == 'true'", backend)
        self.assertIn("后端作业上限 40 分钟", (ROOT / "docs/TESTING.md").read_text())

    def assert_backend_capacity(self, backend):
        self.assertEqual(
            ["40"],
            re.findall(r"^    timeout-minutes: (\d+)$", backend, flags=re.MULTILINE),
            "require exactly one explicit backend whole-job capacity",
        )

    def test_backend_capacity_guard_rejects_stale_missing_or_duplicate_deadlines(self):
        backend = self.job("backend")
        for broken in (
            backend.replace("timeout-minutes: 40", "timeout-minutes: 15"),
            backend.replace("timeout-minutes: 40", "timeout-minutes: 25"),
            backend.replace("timeout-minutes: 40", "timeout-minutes: 60"),
            backend.replace("    timeout-minutes: 40\n", ""),
            backend.replace("    timeout-minutes: 40\n", "        timeout-minutes: 40\n"),
            backend + "    timeout-minutes: 40\n",
            backend + "    timeout-minutes: 25\n",
        ):
            with self.subTest(broken=broken), self.assertRaises(AssertionError):
                self.assert_backend_capacity(broken)

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
        self.assertEqual(10800, SHARD_TIMEOUT_SECONDS)
        self.assertEqual(2700, TARGETED_TIMEOUT_SECONDS)
        runner = (ROOT / "tools/run_android_tests.py").read_text()
        self.assertRegex(runner, r"\n\s+SHARD_TIMEOUT_SECONDS,\n")
        self.assertRegex(runner, r"\n\s+TARGETED_TIMEOUT_SECONDS,\n")
        self.assert_native_task_capacity((ROOT / "android/app/build.gradle.kts").read_text())
        testing = (ROOT / "docs/TESTING.md").read_text()
        self.assertIn("完整三片每片上限180分钟，定向批次仍45分钟", testing)
        self.assertIn("真机测试单项上限 150 秒", testing)

    def test_real_recovery_journey_has_only_its_approved_bounded_exception(self):
        source = (ROOT / "android/app/src/androidTest/java/com/dayforge/data/repository/"
                  "RealBackendReplicaRecoveryJointTest.kt").read_text()
        self.assertEqual(["300_000"], re.findall(r"@Test\(timeout = (\d[\d_]*)\)", source))
        self.assertIn("@Test(timeout = 300_000) fun "
                      "restoredBackendRequiresFreshLoginAndConsentRetainsArchiveAndResumesOrdinarySync()", source)
        self.assertIn("timeoutMillis: Long = 60_000", source)
        self.assertIn("withTimeout(timeoutMillis) { block() }", source)
        self.assertIn('step("explicit consent and confirmed replica switch")', source)
        self.assertIn('step("explicit bounded material resume and final release", timeoutMillis = 90_000)', source)
        self.assertIn('click("replica-recovery-resume", timeoutMillis = 90_000)', source)
        self.assertIn('val settled = settle(timeoutMillis)', source)
        causal = (ROOT / "android/app/src/androidTest/java/com/dayforge/data/repository/"
                  "NextStructuralCausalStoreTest.kt").read_text()
        self.assertEqual(["180_000"], re.findall(r"@Test\(timeout = (\d[\d_]*)\)", causal))
        self.assertIn("@Test(timeout = 180_000) fun "
                      "oneTransactionCapturesThirtyTwoOrderedOriginsWithoutGuessingIntermediateProjection()", causal)

    def assert_native_task_capacity(self, gradle):
        capacity = re.search(
            r'val physicalDeviceTestTimeoutSeconds = providers\.gradleProperty\("dayforge.deviceTestScope"\)'
            r'\.map \{ scope ->\s*when \(scope\) \{\s*"full" -> (\d+)L\s*'
            r'"targeted" -> (\d+)L\s*else -> error\("Unknown DayForge device test scope"\)\s*'
            r'\}\s*\}\.orElse\((\d+)L\)',
            gradle,
        )
        self.assertIsNotNone(capacity, "missing explicit full/targeted native capacities")
        self.assertEqual(
            (SHARD_TIMEOUT_SECONDS, TARGETED_TIMEOUT_SECONDS, TARGETED_TIMEOUT_SECONDS),
            tuple(map(int, capacity.groups())),
        )
        self.assertEqual(1, gradle.count('gradleProperty("dayforge.deviceTestScope")'))
        task = re.search(
            r'tasks\.matching \{ it\.name == "connectedDeviceTestAndroidTest" \}'
            r"\.configureEach \{\s*timeout\.set\(physicalDeviceTestTimeoutSeconds\.map "
            r"\{ Duration\.ofSeconds\(it\) \}\)\s*\}",
            gradle,
        )
        self.assertIsNotNone(task, "missing exact native task capacity")
        self.assertEqual(1, gradle.count("timeout.set("))
        self.assertIn('testInstrumentationRunnerArguments["timeout_msec"] = "150000"', gradle)

    def test_native_task_guard_rejects_stale_or_mismatched_limits(self):
        gradle = (ROOT / "android/app/build.gradle.kts").read_text()
        for broken in (
            gradle.replace('"full" -> 10800L', '"full" -> 2700L'),
            gradle.replace('"full" -> 10800L', '"full" -> 1500L'),
            gradle.replace('"targeted" -> 2700L', '"targeted" -> 10800L'),
            gradle.replace('"targeted" -> 2700L', '"targeted" -> 900L'),
            gradle.replace("orElse(2700L)", "orElse(10800L)"),
            gradle.replace('else -> error("Unknown DayForge device test scope")', 'else -> 10800L'),
            gradle.replace("Duration.ofSeconds(it)", "Duration.ofMinutes(it)"),
            gradle.replace("connectedDeviceTestAndroidTest", "connectedDebugAndroidTest"),
            gradle.replace("timeout.set(physicalDeviceTestTimeoutSeconds.map { Duration.ofSeconds(it) })", ""),
            gradle.replace('= "150000"', '= "180000"'),
            gradle + "\ntimeout.set(Duration.ofSeconds(900))\n",
        ):
            with self.subTest(broken=broken[-240:]), self.assertRaises(AssertionError):
                self.assert_native_task_capacity(broken)


if __name__ == "__main__":
    unittest.main()
