"""Tooling regressions only; fake command boundaries do not count as Android tests."""

import io
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.check_android_coverage import CANARIES
from tools.run_android_tests import (
    COVERAGE_INPUT,
    COVERAGE_REPORT,
    RESULTS,
    SHARDS,
    TARGET,
    TEST_PACKAGE,
    Gate,
    check_global_arguments,
    command,
    discover,
    validate_shards,
    verification_lock,
)
from tools.tests.test_android_results import suite


def discovery(cases):
    lines = []
    for current, (class_name, name) in enumerate(cases, 1):
        for code in (1, 0):
            lines.extend(
                (
                    "INSTRUMENTATION_STATUS: class=" + class_name,
                    "INSTRUMENTATION_STATUS: test=" + name,
                    f"INSTRUMENTATION_STATUS: current={current}",
                    f"INSTRUMENTATION_STATUS: numtests={len(cases)}",
                    f"INSTRUMENTATION_STATUS_CODE: {code}",
                )
            )
    return "\n".join(lines + ["INSTRUMENTATION_CODE: -1"])


def result(cases):
    return suite(
        "".join(
            f"<testcase classname='{class_name}' name='{name}' time='0.01'/>"
            for class_name, name in cases
        ),
        tests=len(cases),
    )


CASES = [("Test", "one"), ("Test", "two[param=UTC]"), ("AnotherTest", "three")]


class FakeGate(Gate):
    def __init__(self, repo, fault=None):
        super().__init__(repo)
        self.calls = []
        self.fault = fault
        self.serial = "physical-fixture"

    def execute(self, name, argv, timeout=120):
        self.calls.append((name, argv, timeout))
        log = self.run / (name + ".log")
        log.write_text("success\n")
        self.logs.append(log)
        if name == self.fault:
            raise ValueError("Injected command failure: " + name)
        if name == "build":
            for relative, package in (
                ("deviceTest", TARGET),
                ("androidTest/deviceTest", TEST_PACKAGE),
            ):
                path = self.build / "outputs/apk" / relative
                path.mkdir(parents=True)
                (path / "fixture.apk").write_bytes(package.encode())
                (path / "output-metadata.json").write_text(
                    json.dumps(
                        {
                            "applicationId": package,
                            "elements": [{"outputFile": "fixture.apk"}],
                        }
                    )
                )
        elif name == "discovery":
            log.write_text(discovery(CASES))
        elif name.startswith("shard-"):
            index = int(name[-1])
            cases = CASES[index::SHARDS]
            if self.fault == "missing-case" and index == 1:
                cases = CASES[:1]
            path = self.build / RESULTS
            path.mkdir(parents=True)
            (path / "TEST-phone.xml").write_text(result(cases))
            path = self.build / COVERAGE_INPUT / "phone"
            path.mkdir(parents=True)
            if self.fault != "missing-ec":
                (path / "coverage.ec").write_bytes(f"coverage-{index}".encode())
            if self.fault == "changed-apk":
                self.apks()[0].write_bytes(b"changed")
        elif name == "coverage":
            path = self.build / COVERAGE_REPORT
            path.mkdir(parents=True)
            classes = "".join(
                f"<class name='{name}'><counter type='LINE' covered='1'/></class>"
                for name in CANARIES
            )
            (path / "report.xml").write_text(
                "<report><package>" + classes + "</package></report>"
            )
            if self.fault == "bad-coverage":
                (path / "report.xml").write_text("<report/>")
        elif "-packages-" in name:
            # AGP may already have uninstalled these. Only exact known packages count.
            log.write_text("package:" + argv[-1] + "\n")
        return log


class AndroidShardGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        (self.repo / "android").mkdir()
        (self.repo / "android/compiler-warning-budget.json").write_text(
            '{"version":1,"warnings":[]}'
        )
        self.environment = patch.dict(
            os.environ, {"GRADLE_USER_HOME": str(self.repo / "gradle-user")}, clear=True
        )
        self.environment.start()
        self.addCleanup(self.environment.stop)
        output = patch("sys.stdout", new=io.StringIO())
        output.start()
        self.addCleanup(output.stop)

    def test_discovery_preserves_parameterized_identity_and_complete_pairs(self):
        self.assertEqual(set(CASES), discover(discovery(CASES)))

    def test_discovery_rejects_missing_pairs_duplicates_counts_skips_and_failure(self):
        valid = discovery(CASES)
        for invalid in (
            "",
            valid.replace("INSTRUMENTATION_CODE: -1", ""),
            valid.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
            valid.replace("numtests=3", "numtests=4", 1),
            valid.replace("STATUS_CODE: 0", "STATUS_CODE: -3", 1),
            valid.replace("STATUS_CODE: 0", "STATUS_CODE: -2", 1),
            valid.replace("current=1", "current=2", 1),
            discovery([CASES[0], CASES[0]]),
            valid.rsplit("INSTRUMENTATION_STATUS_CODE: 0", 1)[0],
        ):
            with (
                self.subTest(invalid=invalid),
                self.assertRaises((ValueError, KeyError)),
            ):
                discover(invalid)

    def test_shards_reject_missing_duplicate_extra_and_incomplete_cases(self):
        directories = [self.repo / f"shard-{index}" for index in range(SHARDS)]
        for path in directories:
            path.mkdir()

        def write(first, second):
            for path, cases in zip(directories, (first, second), strict=True):
                (path / "TEST-phone.xml").write_text(result(cases))

        write(CASES[::2], CASES[1::2])
        self.assertEqual(3, validate_shards(set(CASES), directories))
        for first, second in (
            (CASES[:1], CASES[1:2]),
            (CASES, CASES[:1]),
            (CASES[::2], [("Extra", "test")]),
        ):
            write(first, second)
            with self.assertRaises(ValueError):
                validate_shards(set(CASES), directories)
        with self.assertRaises(ValueError):
            validate_shards(set(CASES), directories[:1])
        write(CASES[::2], CASES[1::2])
        (directories[1] / "TEST-phone.xml").write_text(
            result(CASES[1::2]).replace("time='0.01'", "time='-1'")
        )
        with self.assertRaises(ValueError):
            validate_shards(set(CASES), directories)

    def test_full_gate_is_sequential_bounded_complete_and_only_touches_testbed(self):
        gate = FakeGate(self.repo)
        prior = gate.build / RESULTS
        prior.mkdir(parents=True)
        (prior / "TEST-old.xml").write_text(result(CASES))
        gate.run_gate()
        self.assertFalse((gate.run / "complete.json").exists())
        gate.cleanup()
        gate.finish()
        names = [name for name, _, _ in gate.calls]
        self.assertLess(names.index("discovery"), names.index("shard-0"))
        self.assertLess(names.index("shard-0"), names.index("shard-1"))
        self.assertLess(names.index("shard-1"), names.index("coverage"))
        self.assertEqual(
            3, json.loads((gate.run / "complete.json").read_text())["cases"]
        )
        self.assertTrue((gate.run / "prior-results/TEST-old.xml").exists())
        for index in range(SHARDS):
            self.assertEqual(
                f"coverage-{index}".encode(),
                (gate.build / COVERAGE_INPUT / f"shard-{index}.ec").read_bytes(),
            )
        for name, argv, timeout in gate.calls:
            if name.startswith("shard-"):
                self.assertEqual(900, timeout)
                self.assertIn(
                    "-Pandroid.testInstrumentationRunnerArguments.log=false", argv
                )
                self.assertIn(
                    "-Pandroid.testInstrumentationRunnerArguments.timeout_msec=150000",
                    argv,
                )
                self.assertIn("--no-daemon", argv)
                self.assertFalse(
                    any(
                        "Arguments.class" in item or "Arguments.package=" in item
                        for item in argv
                    )
                )
            if "-uninstall-" in name:
                self.assertIn(argv[-1], (TARGET, TEST_PACKAGE))

    def test_failures_never_publish_completion_or_continue_to_coverage(self):
        for fault in (
            "build",
            "discovery",
            "shard-0",
            "shard-1",
            "missing-ec",
            "missing-case",
            "changed-apk",
            "coverage",
            "bad-coverage",
        ):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as directory:
                repo = Path(directory)
                (repo / "android").mkdir()
                (repo / "android/compiler-warning-budget.json").write_text(
                    '{"version":1,"warnings":[]}'
                )
                gate = FakeGate(repo, fault)
                with self.assertRaises(ValueError):
                    try:
                        gate.run_gate()
                    finally:
                        gate.cleanup()
                self.assertFalse((gate.run / "complete.json").exists())
                if fault not in ("coverage", "bad-coverage"):
                    self.assertNotIn("coverage", [name for name, _, _ in gate.calls])

    def test_build_only_never_installs_discovers_runs_or_claims_device_pass(self):
        gate = FakeGate(self.repo)
        with patch("sys.stdout", new=io.StringIO()) as output:
            gate.run_gate(build_only=True)
            gate.cleanup()
            gate.finish()
        self.assertIn("physical-device tests NOT RUN", output.getvalue())
        self.assertEqual(["build"], [name for name, _, _ in gate.calls])
        self.assertFalse((gate.run / "complete.json").exists())

    def test_external_arguments_and_symlink_output_cannot_bypass_gate(self):
        with patch.dict(
            os.environ,
            {
                "ORG_GRADLE_PROJECT_android.testInstrumentationRunnerArguments.log": "true"
            },
        ):
            with self.assertRaises(ValueError):
                check_global_arguments(self.repo)
        (self.repo / "android/gradle.properties").write_text(
            "android.testInstrumentationRunnerArguments.class=SomeTest\n"
        )
        with self.assertRaises(ValueError):
            check_global_arguments(self.repo)
        gate = FakeGate(self.repo)
        output = gate.build / RESULTS
        output.parent.mkdir(parents=True)
        output.symlink_to(self.repo, target_is_directory=True)
        with self.assertRaises(ValueError):
            gate.rotate(RESULTS, "unsafe")
        with self.assertRaises(ValueError):
            gate.rotate("../arbitrary", "unsafe")
        self.assertTrue(output.is_symlink())

    def test_lock_excludes_another_run_and_is_reusable(self):
        with verification_lock():
            with self.assertRaises(ValueError), verification_lock():
                pass
        with verification_lock():
            pass

    def test_process_timeout_joins_owned_child_and_retains_log(self):
        log = self.repo / "process.log"
        with patch("sys.stdout", new=io.StringIO()), self.assertRaises(ValueError):
            command(
                [
                    sys.executable,
                    "-c",
                    "import os,time; print(os.getpid(),flush=True); time.sleep(60)",
                ],
                self.repo,
                log,
                1,
            )
        pid = int(log.read_text().strip())
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)

    def test_process_nonzero_exit_is_not_a_pass(self):
        with patch("sys.stdout", new=io.StringIO()), self.assertRaises(ValueError):
            command(
                [sys.executable, "-c", "raise SystemExit(4)"],
                self.repo,
                self.repo / "failed.log",
                5,
            )

    def test_process_cancellation_joins_owned_child(self):
        log = self.repo / "cancelled.log"
        now = time.monotonic()
        with (
            patch(
                "tools.run_android_tests.time.monotonic",
                side_effect=[now, now, KeyboardInterrupt],
            ),
            self.assertRaises(KeyboardInterrupt),
        ):
            command(
                [
                    sys.executable,
                    "-c",
                    "import os,time; print(os.getpid(),flush=True); time.sleep(60)",
                ],
                self.repo,
                log,
                5,
            )
        with self.assertRaises(ProcessLookupError):
            os.kill(int(log.read_text().strip()), 0)

    def test_cleanup_checks_exact_package_and_attempts_all_steps_after_failure(self):
        gate = FakeGate(self.repo, "final-stop-testbed")
        gate.owned_device = True
        with self.assertRaises(ValueError):
            gate.cleanup()
        names = [name for name, _, _ in gate.calls]
        self.assertIn("final-uninstall-tests", names)
        self.assertIn("final-uninstall-testbed", names)
        self.assertFalse((gate.run / "complete.json").exists())

    def test_shell_entrypoint_supports_full_and_build_only_with_nounset(self):
        binary = self.repo / "bin"
        binary.mkdir()
        python = binary / "python3"
        python.write_text(
            '#!/usr/bin/env bash\nprintf "%s\\n" "$*" >> "$DAYFORGE_TOOL_COMMANDS"\n'
        )
        python.chmod(0o700)
        java_home = self.repo / "jdk"
        (java_home / "bin").mkdir(parents=True)
        java = java_home / "bin/java"
        java.write_text("#!/usr/bin/env bash\nexit 0\n")
        java.chmod(0o700)
        for scope in ("android", "android-build"):
            log = self.repo / (scope + ".log")
            environment = os.environ | {
                "PATH": str(binary) + os.pathsep + os.defpath,
                "JAVA_HOME": str(java_home),
                "DAYFORGE_TOOL_COMMANDS": str(log),
            }
            root = Path(__file__).resolve().parents[2]
            executed = subprocess.run(
                ["/bin/bash", str(root / "tools/verify"), scope],
                cwd=self.repo,
                env=environment,
                capture_output=True,
                text=True,
                timeout=10,
            )
            self.assertEqual(0, executed.returncode, executed.stderr)
            last = log.read_text().splitlines()[-1]
            self.assertEqual(
                "-m tools.run_android_tests"
                + (" --build-only" if scope == "android-build" else ""),
                last,
            )


if __name__ == "__main__":
    unittest.main()
