"""Approved whole-job/shard capacity must preserve full-suite and case guards."""

from pathlib import Path
import importlib.util
import json
import tempfile
import re
import subprocess
import unittest
from unittest.mock import patch

from tools.run_android_tests import SHARDS, SHARD_TIMEOUT_SECONDS, TARGETED_TIMEOUT_SECONDS, TARGETED_MAX_TIMEOUT_SECONDS


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

    def test_hosted_android_builds_have_independent_capacity_and_evidence(self):
        build = self.job("android_build")
        self.assertIn("    runs-on: ubuntu-latest", build)
        self.assertIn("      fail-fast: false", build)
        lanes = re.findall(r'- lane: (\w+)\n +build_seconds: "(\d+)"\n +step_minutes: (\d+)\n +job_minutes: (\d+)', build)
        self.assertEqual([("app", "1800", "35", "40"), ("instrumentation", "3600", "65", "70")], lanes)
        for setting in (
            "    timeout-minutes: ${{ matrix.job_minutes }}",
            "        timeout-minutes: ${{ matrix.step_minutes }}",
            "          DAYFORGE_BUILD_TIMEOUT_SECONDS: ${{ matrix.build_seconds }}",
            '        run: python3 .github/scripts/hosted_android_build.py "${{ matrix.lane }}"',
            "            android/app/build/reports/dayforge-device/",
        ):
            self.assertIn(setting, build)
        self.assertNotIn("continue-on-error", build)
        runner = (ROOT / "tools/run_android_tests.py").read_text()
        self.assertIn('"-Pandroid.testInstrumentationRunnerArguments.timeout_msec=150000"', runner)

    def test_required_android_check_only_accepts_two_successes_or_an_inapplicable_build(self):
        job = self.job("android")
        self.assertIn("needs: [changes, android_build]", job)
        self.assertIn("if: always()", job)
        self.assertIn("CHANGES_RESULT: ${{ needs.changes.result }}", job)
        self.assertIn("ANDROID_REQUIRED: ${{ needs.changes.outputs.android }}", job)
        self.assertIn("BUILD_RESULT: ${{ needs.android_build.result }}", job)
        check = job.split("        run: |\n", 1)[1]
        for changes, required, build, accepted in (
            ("success", "true", "success", True), ("success", "false", "skipped", True),
            ("failure", "false", "skipped", False), ("success", "true", "skipped", False),
            ("success", "true", "failure", False), ("success", "true", "cancelled", False),
            ("success", "", "skipped", False),
        ):
            with self.subTest(changes=changes, required=required, build=build):
                env = {"CHANGES_RESULT": changes, "ANDROID_REQUIRED": required, "BUILD_RESULT": build}
                self.assertEqual(subprocess.run(["sh", "-c", check], env=env).returncode == 0, accepted)

    def test_independent_builds_require_each_artifact_and_enforce_the_warning_budget(self):
        script = ROOT / ".github/scripts/hosted_android_build.py"
        spec = importlib.util.spec_from_file_location("hosted_android_build", script)
        hosted = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(hosted)
        for lane, fault in (
            ("app", None), ("instrumentation", None),
            ("app", "warning"), ("instrumentation", "warning"),
            ("app", "missing"), ("instrumentation", "missing"),
        ):
            with self.subTest(lane=lane, fault=fault), tempfile.TemporaryDirectory() as tmp:
                gate = hosted.Gate(Path(tmp), build_timeout=1800 if lane == "app" else 3600)
                (gate.android / "compiler-warning-budget.json").write_text('{"version": 1, "warnings": []}')
                outputs = [("debug", "com.dayforge")] if lane == "app" else [
                    ("deviceTest", "com.dayforge.testbed"), ("androidTest/deviceTest", "com.dayforge.testbed.test")
                ]
                for directory, package in outputs:
                    apk_dir = gate.build / "outputs/apk" / directory
                    apk_dir.mkdir(parents=True)
                    (apk_dir / "output-metadata.json").write_text(json.dumps({
                        "applicationId": package, "elements": [{"outputFile": "app.apk"}]
                    }))
                    if fault != "missing" or directory == "deviceTest":
                        (apk_dir / "app.apk").write_bytes(b"host fixture")
                (gate.build / "reports/lint-results-debug.xml").write_text("<issues/>")

                def fake_build(name, argv, seconds):
                    log = gate.run / (name + ".log")
                    log.write_text("w: file:///workspace/Test.kt:12:4 Kotlin warning\n" if fault == "warning" else "")
                    gate.logs.append(log)

                with patch.object(gate, "execute", side_effect=fake_build) as run, patch.object(hosted, "snapshot", return_value={}):
                    if fault:
                        with self.assertRaisesRegex(ValueError, "budget exceeded" if fault == "warning" else "Missing"):
                            hosted.build(gate, lane)
                        self.assertFalse((gate.run / "outputs.json").exists())
                    else:
                        hosted.build(gate, lane)
                        artifacts = json.loads((gate.run / "outputs.json").read_text())
                        self.assertEqual(2, len(artifacts))
                        for rel, digest in artifacts.items():
                            self.assertEqual(hosted.digest(gate.build / rel), digest)
                    tasks = ["lintDebug", "assembleDebug"] if lane == "app" else [
                        "assembleDeviceTest", "assembleDeviceTestAndroidTest"]
                    options = ["-Pkotlin.daemon.jvmargs=-Xmx6144m"] if lane == "instrumentation" else []
                    run.assert_called_once_with("build", ["./gradlew", "--no-daemon", *options, *tasks], gate.build_timeout_seconds)
                    budget = json.loads((gate.run / "build-budget.json").read_text())
                    self.assertEqual(tasks, budget["tasks"])
                    self.assertEqual(options, budget["gradle_options"])

    def test_compiler_heap_is_separate_and_observation_excludes_unrelated_values(self):
        props = (ROOT / "android/gradle.properties").read_text()
        self.assertEqual(["-Xmx4608m"], re.findall(r"^kotlin\.daemon\.jvmargs=(.*)$", props, re.M))
        gradle, = re.findall(r"^org\.gradle\.jvmargs=(.*)$", props, re.M)
        self.assertEqual(["-Xmx3072m"], [arg for arg in gradle.split() if arg.startswith("-Xmx")])
        script = ROOT / ".github/scripts/hosted_android_build.py"
        spec = importlib.util.spec_from_file_location("hosted_android_build", script)
        hosted = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(hosted)
        main = "org.jetbrains.kotlin.daemon.KotlinCompileDaemon"
        self.assertEqual("-Xmx6144m", hosted.kotlin_startup_heap(
            ["java", "-Xmx3G", "-Dexample=-Xmx2G", "-Xmx6144m", main, "-Xmx1G"]))
        for args in (["java", main, "-Xmx6144m"], ["java", "-Xmx6144m", "OtherMain"],
                     ["java", "-Xmx6144m-extra", "-Dexample=-Xmx1G", main]):
            self.assertIsNone(hosted.kotlin_startup_heap(args))

    def test_physical_shard_capacity_is_explicit_and_user_approved(self):
        self.assertEqual(3, SHARDS)
        self.assertEqual(10800, SHARD_TIMEOUT_SECONDS)
        self.assertEqual(2700, TARGETED_TIMEOUT_SECONDS)
        runner = (ROOT / "tools/run_android_tests.py").read_text()
        self.assertRegex(runner, r"\n\s+SHARD_TIMEOUT_SECONDS,\n")
        self.assertRegex(runner, r"\n\s+self\.targeted_timeout_seconds,\n")
        self.assertEqual(10800, TARGETED_MAX_TIMEOUT_SECONDS)
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
        self.assertIn('withTimeout(timeoutMillis) { model.state.first { it.checked && !it.busy } }', source)
        self.assertIn('val settled = if (tag == "replica-recovery-close") {\n'
                      '            withTimeout(timeoutMillis) { model.state.first { !it.visible && !it.busy } }\n'
                      '        } else settle(timeoutMillis)', source)
        self.assertIn('compose.onNodeWithTag(tag).performClick()', source)
        self.assertIn('click("replica-recovery-close")\n'
                      '            compose.onNodeWithTag("replica-recovery-list").assertDoesNotExist()', source)
        causal = (ROOT / "android/app/src/androidTest/java/com/dayforge/data/repository/"
                  "NextStructuralCausalStoreTest.kt").read_text()
        self.assertEqual(["180_000"], re.findall(r"@Test\(timeout = (\d[\d_]*)\)", causal))
        self.assertIn("@Test(timeout = 180_000) fun "
                      "oneTransactionCapturesThirtyTwoOrderedOriginsWithoutGuessingIntermediateProjection()", causal)

    def assert_native_task_capacity(self, gradle):
        targeted = re.search(
            r'val targetedDeviceTestTimeoutSeconds = providers\.gradleProperty\("dayforge.targetedTimeoutSeconds"\)'
            r'\.map \{ value ->\s*require\(value\.matches\(Regex\("\[1-9\]\[0-9\]\{3,4\}"\)\)\) '
            r'\{ "Targeted timeout must be an integer from 2700 to 10800" \}\s*'
            r'value\.toLong\(\)\.also \{ require\(it in (\d+)L\.\.(\d+)L\) '
            r'\{ "Targeted timeout must be an integer from 2700 to 10800" \} \}\s*'
            r'\}\.orElse\((\d+)L\)', gradle,
        )
        self.assertIsNotNone(targeted, "missing bounded native targeted capacity parser")
        self.assertEqual((TARGETED_TIMEOUT_SECONDS, TARGETED_MAX_TIMEOUT_SECONDS, TARGETED_TIMEOUT_SECONDS),
                         tuple(map(int, targeted.groups())))
        self.assertEqual(1, gradle.count('gradleProperty("dayforge.targetedTimeoutSeconds")'))
        capacity = re.search(
            r'val physicalDeviceTestTimeoutSeconds = providers\.gradleProperty\("dayforge.deviceTestScope"\)'
            r'\.map \{ scope ->\s*when \(scope\) \{\s*"full" -> (\d+)L\s*'
            r'"targeted" -> targetedDeviceTestTimeoutSeconds\.get\(\)\s*else -> error\("Unknown DayForge device test scope"\)\s*'
            r'\}\s*\}\.orElse\((\d+)L\)', gradle,
        )
        self.assertIsNotNone(capacity, "missing explicit full/targeted native capacities")
        self.assertEqual((SHARD_TIMEOUT_SECONDS, TARGETED_TIMEOUT_SECONDS), tuple(map(int, capacity.groups())))
        self.assertEqual(1, gradle.count('gradleProperty("dayforge.deviceTestScope")'))
        native_tasks = ("connectedDeviceTestAndroidTest", "connectedReleaseCalibrationAndroidTest")
        for name in native_tasks:
            task = re.search(
                r'tasks\.matching \{ it\.name == "' + re.escape(name) + r'" \}'
                r"\.configureEach \{\s*timeout\.set\(physicalDeviceTestTimeoutSeconds\.map "
                r"\{ Duration\.ofSeconds\(it\) \}\)\s*\}",
                gradle,
            )
            self.assertIsNotNone(task, "missing exact native task capacity: " + name)
        self.assertEqual(len(native_tasks), gradle.count("timeout.set("))
        self.assertIn('testInstrumentationRunnerArguments["timeout_msec"] = "150000"', gradle)

    def test_native_task_guard_rejects_stale_or_mismatched_limits(self):
        gradle = (ROOT / "android/app/build.gradle.kts").read_text()
        for broken in (
            gradle.replace('"full" -> 10800L', '"full" -> 2700L'),
            gradle.replace('"full" -> 10800L', '"full" -> 1500L'),
            gradle.replace('"targeted" -> targetedDeviceTestTimeoutSeconds.get()', '"targeted" -> 2700L'),
            gradle.replace('"targeted" -> targetedDeviceTestTimeoutSeconds.get()', '"targeted" -> 10800L'),
            gradle.replace('2700L..10800L', '900L..10800L'),
            gradle.replace('2700L..10800L', '2700L..10801L'),
            gradle.replace('[1-9][0-9]{3,4}', '[0-9]+'),
            gradle.replace('value.toLong()', 'value.toDouble().toLong()'),
            gradle.replace("orElse(2700L)", "orElse(10800L)"),
            gradle.replace('else -> error("Unknown DayForge device test scope")', 'else -> 10800L'),
            gradle.replace("Duration.ofSeconds(it)", "Duration.ofMinutes(it)"),
            gradle.replace("connectedDeviceTestAndroidTest", "connectedDebugAndroidTest"),
            gradle.replace("connectedReleaseCalibrationAndroidTest", "connectedReleaseAndroidTest"),
            gradle.replace(
                'tasks.matching { it.name == "connectedReleaseCalibrationAndroidTest" }.configureEach {\n'
                '    timeout.set(physicalDeviceTestTimeoutSeconds.map { Duration.ofSeconds(it) })',
                'tasks.matching { it.name == "connectedReleaseCalibrationAndroidTest" }.configureEach {\n'
                '    timeout.set(physicalDeviceTestTimeoutSeconds.map { Duration.ofSeconds(10800) })',
            ),
            gradle.replace("timeout.set(physicalDeviceTestTimeoutSeconds.map { Duration.ofSeconds(it) })", ""),
            gradle.replace('= "150000"', '= "180000"'),
            gradle + "\ntimeout.set(Duration.ofSeconds(900))\n",
        ):
            with self.subTest(broken=broken[-240:]), self.assertRaises(AssertionError):
                self.assert_native_task_capacity(broken)


if __name__ == "__main__":
    unittest.main()
