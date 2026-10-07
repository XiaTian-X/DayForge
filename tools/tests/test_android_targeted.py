"""Scoped tool boundary tests; these are not Android behavior evidence."""

import contextlib
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.run_android_tests import (
    COVERAGE_INPUT,
    RESULTS,
    SHARD_TIMEOUT_SECONDS,
    main,
    parse_arguments,
    selected_classes,
)
from tools.tests.test_android_shards import FakeGate, discovery, result


FIRST = "com.dayforge.FirstTest"
SECOND = "com.dayforge.SecondTest"
CASES = [(FIRST, "one"), (FIRST, "two[param=UTC]"), (SECOND, "three")]


class ScopedFakeGate(FakeGate):
    def execute(self, name, argv, timeout=120):
        log = super().execute(name, argv, timeout)
        if name == "discovery":
            chosen = argv[argv.index("class") + 1].split(",")
            self.chosen = [case for case in CASES if case[0] in chosen]
            found = list(self.chosen)
            if self.fault == "extra-discovery":
                found += [("com.dayforge.UnselectedTest", "extra")]
            log.write_text(discovery(found))
        elif name == "targeted":
            cases = list(self.chosen)
            if self.fault == "missing-case":
                cases.pop()
            elif self.fault == "extra-case":
                cases += [("com.dayforge.UnselectedTest", "extra")]
            elif self.fault == "duplicate-case":
                cases += cases[:1]
            path = self.build / RESULTS
            path.mkdir(parents=True)
            xml = result(cases)
            if self.fault == "negative-duration":
                xml = xml.replace("time='0.01'", "time='-1'", 1)
            elif self.fault in ("failure", "skipped"):
                tag = "failure" if self.fault == "failure" else "skipped"
                xml = xml.replace("/>", f"><{tag}/></testcase>", 1)
            elif self.fault == "invalid-report":
                xml = "<testsuite/>"
            if self.fault != "missing-report":
                (path / "TEST-phone.xml").write_text(xml)
            source = self.build / COVERAGE_INPUT / "phone"
            source.mkdir(parents=True)
            if self.fault != "missing-ec":
                (source / "coverage.ec").write_bytes(
                    b"" if self.fault == "empty-ec" else b"scoped-coverage"
                )
            if self.fault == "duplicate-ec":
                (source / "another.ec").write_bytes(b"another")
            if self.fault == "changed-apk":
                self.apks()[0].write_bytes(b"changed")
            if self.fault == "warning-growth":
                log.write_text("w: file:///workspace/Test.kt:12:4 New scoped warning\n")
        return log


class AndroidTargetedGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        (self.repo / "android").mkdir()
        (self.repo / "android/compiler-warning-budget.json").write_text(
            '{"version":1,"warnings":[]}'
        )
        environment = patch.dict(
            os.environ, {"GRADLE_USER_HOME": str(self.repo / "gradle-user")}, clear=True
        )
        environment.start()
        self.addCleanup(environment.stop)
        output = patch("sys.stdout", new=io.StringIO())
        output.start()
        self.addCleanup(output.stop)

    def test_selection_is_explicit_bounded_sorted_and_has_no_method_or_wildcard(self):
        self.assertEqual((FIRST, SECOND), selected_classes([SECOND, FIRST]))
        self.assertEqual(
            ("com.dayforge.OuterTest$NestedTest",),
            selected_classes(["com.dayforge.OuterTest$NestedTest"]),
        )
        for invalid in (
            [],
            [FIRST, FIRST],
            ["*"],
            [FIRST + "#one"],
            ["other.OwnerTest"],
            [FIRST + "," + SECOND],
            [FIRST + "\n"],
            ["--build-only"],
            ["com.dayforge." + "x" * 256],
            [f"com.dayforge.Test{index}" for index in range(65)],
        ):
            with self.subTest(names=invalid), self.assertRaises(ValueError):
                selected_classes(invalid)

    def test_cli_preserves_full_and_build_modes_and_rejects_ambiguous_arguments(self):
        self.assertEqual((False, ()), parse_arguments([]))
        self.assertEqual((True, ()), parse_arguments(["--build-only"]))
        self.assertEqual((False, (FIRST,)), parse_arguments(["--classes", FIRST]))
        for invalid in (
            ["--classes"],
            [FIRST],
            ["--build-only", FIRST],
            ["--classes", FIRST, "--build-only"],
        ):
            with self.subTest(arguments=invalid), self.assertRaises(ValueError):
                parse_arguments(invalid)

    def test_scoped_run_preserves_original_identity_hashes_and_never_claims_full_gate(
        self,
    ):
        gate = ScopedFakeGate(self.repo)
        prior = gate.build / RESULTS
        prior.mkdir(parents=True)
        (prior / "TEST-old.xml").write_text(result(CASES))
        gate.run_gate(classes=(FIRST,))
        self.assertFalse((gate.run / "targeted.json").exists())
        self.assertFalse((gate.run / "complete.json").exists())
        gate.cleanup()
        with patch("sys.stdout", new=io.StringIO()) as output:
            gate.finish()
        self.assertIn("full-suite gate NOT RUN", output.getvalue())
        proof = json.loads((gate.run / "targeted.json").read_text())
        self.assertEqual("targeted_complete", proof["status"])
        self.assertEqual("targeted", proof["scope"])
        self.assertEqual([FIRST], proof["selected_classes"])
        self.assertEqual(2, proof["cases"])
        self.assertEqual([list(case) for case in CASES[:2]], proof["discovered_cases"])
        self.assertEqual(2, len(proof["apk_sha256"]))
        self.assertEqual(1, len(proof["coverage_inputs_sha256"]))
        self.assertEqual("not_run_targeted_scope", proof["coverage_calibration"])
        self.assertTrue(proof["test_reports_sha256"])
        self.assertTrue((gate.run / "prior-results/TEST-old.xml").exists())
        self.assertFalse((gate.run / "complete.json").exists())
        names = [name for name, _, _ in gate.calls]
        self.assertNotIn("coverage", names)
        self.assertFalse(any(name.startswith("shard-") for name in names))
        run = next(
            (argv, deadline)
            for name, argv, deadline in gate.calls
            if name == "targeted"
        )
        self.assertEqual(SHARD_TIMEOUT_SECONDS, run[1])
        self.assertIn(
            "-Pandroid.testInstrumentationRunnerArguments.class=" + FIRST, run[0]
        )
        self.assertIn("-Pandroid.testInstrumentationRunnerArguments.log=false", run[0])
        self.assertIn(
            "-Pandroid.testInstrumentationRunnerArguments.timeout_msec=150000", run[0]
        )
        self.assertFalse(
            any("numShards" in arg or "shardIndex" in arg for arg in run[0])
        )
        self.assertIn("final-uninstall-testbed", names)
        self.assertIn("final-uninstall-tests", names)

    def test_each_selected_class_must_have_actual_discovered_tests(self):
        for names, fault in (
            ((FIRST, "com.dayforge.MissingTest"), None),
            ((FIRST,), "extra-discovery"),
        ):
            with self.subTest(names=names), tempfile.TemporaryDirectory() as directory:
                repo = Path(directory)
                (repo / "android").mkdir()
                (repo / "android/compiler-warning-budget.json").write_text(
                    '{"version":1,"warnings":[]}'
                )
                gate = ScopedFakeGate(repo, fault)
                with self.assertRaises(ValueError):
                    try:
                        gate.run_gate(classes=names)
                    finally:
                        gate.cleanup()
                self.assertNotIn("targeted", [name for name, _, _ in gate.calls])
                self.assertFalse((gate.run / "targeted.json").exists())

    def test_scoped_faults_and_partial_artifacts_never_produce_success(self):
        for fault in (
            "targeted",
            "missing-case",
            "extra-case",
            "duplicate-case",
            "negative-duration",
            "failure",
            "skipped",
            "invalid-report",
            "missing-report",
            "missing-ec",
            "empty-ec",
            "duplicate-ec",
            "changed-apk",
            "warning-growth",
        ):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as directory:
                repo = Path(directory)
                (repo / "android").mkdir()
                (repo / "android/compiler-warning-budget.json").write_text(
                    '{"version":1,"warnings":[]}'
                )
                gate = ScopedFakeGate(repo, fault)
                with self.assertRaises(ValueError):
                    try:
                        gate.run_gate(classes=(FIRST,))
                    finally:
                        gate.cleanup()
                self.assertFalse((gate.run / "targeted.json").exists())
                self.assertFalse((gate.run / "complete.json").exists())
                self.assertIn(
                    "final-uninstall-testbed", [name for name, _, _ in gate.calls]
                )

    def test_injected_global_filter_is_rejected_before_build_in_scoped_mode(self):
        gate = ScopedFakeGate(self.repo)
        with patch.dict(
            os.environ,
            {
                "ORG_GRADLE_PROJECT_android.testInstrumentationRunnerArguments.package": "anything"
            },
        ):
            with self.assertRaises(ValueError):
                gate.run_gate(classes=(FIRST,))
        self.assertEqual([], gate.calls)

    def test_build_only_cannot_silently_ignore_requested_scope(self):
        gate = ScopedFakeGate(self.repo)
        with self.assertRaises(ValueError):
            gate.run_gate(build_only=True, classes=(FIRST,))
        self.assertEqual([], gate.calls)

    def test_cleanup_failure_prevents_publication_of_scoped_success(self):
        gate = ScopedFakeGate(self.repo, "final-stop-testbed")
        gate.run_gate(classes=(FIRST, SECOND))
        with self.assertRaises(ValueError):
            gate.cleanup()
        self.assertFalse((gate.run / "targeted.json").exists())
        self.assertFalse((gate.run / "complete.json").exists())

    def test_main_checks_actual_device_and_publishes_only_after_successful_cleanup(
        self,
    ):
        for fault in (None, "final-stop-testbed"):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as directory:
                repo = Path(directory)
                (repo / "android").mkdir()
                (repo / "android/compiler-warning-budget.json").write_text(
                    '{"version":1,"warnings":[]}'
                )
                gate = ScopedFakeGate(repo, fault)
                with (
                    patch("sys.argv", ["gate", "--classes", FIRST, SECOND]),
                    patch(
                        "tools.run_android_tests.verification_lock",
                        contextlib.nullcontext,
                    ),
                    patch(
                        "tools.run_android_tests.subprocess.check_output",
                        return_value="physical-fixture\n",
                    ) as device,
                    patch("tools.run_android_tests.Gate", return_value=gate),
                    patch("sys.stderr", new=io.StringIO()) as errors,
                ):
                    self.assertEqual(0 if fault is None else 1, main())
                self.assertEqual(
                    "tools.check_android_device", device.call_args.args[0][-1]
                )
                self.assertEqual(fault is None, (gate.run / "targeted.json").exists())
                self.assertFalse((gate.run / "complete.json").exists())
                self.assertIn("targeted", [name for name, _, _ in gate.calls])
                self.assertIn("final-stop-testbed", [name for name, _, _ in gate.calls])
                if fault is None:
                    proof = json.loads((gate.run / "targeted.json").read_text())
                    self.assertEqual([FIRST, SECOND], proof["selected_classes"])
                    self.assertEqual(3, proof["cases"])
                else:
                    self.assertIn("Testbed cleanup failed", errors.getvalue())


if __name__ == "__main__":
    unittest.main()
