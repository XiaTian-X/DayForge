"""Build-capacity tooling boundaries only; no Android execution or behavior evidence."""

import contextlib
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.run_android_tests import (
    BUILD_MAX_TIMEOUT_SECONDS,
    BUILD_TIMEOUT_SECONDS,
    Gate,
    build_timeout_seconds,
    main,
)
from tools.tests.test_android_shards import FakeGate
from tools.tests.test_android_targeted import FIRST, ScopedFakeGate


class AndroidBuildBudgetTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.make_repo("default")
        environment = patch.dict(
            os.environ, {"GRADLE_USER_HOME": str(self.root / "gradle-user")}, clear=True
        )
        environment.start()
        self.addCleanup(environment.stop)
        output = patch("sys.stdout", new=io.StringIO())
        output.start()
        self.addCleanup(output.stop)

    def make_repo(self, name):
        repo = self.root / name
        (repo / "android").mkdir(parents=True)
        (repo / "android/compiler-warning-budget.json").write_text(
            '{"version":1,"warnings":[]}'
        )
        return repo

    def test_build_capacity_is_defaulted_bounded_and_strictly_decimal(self):
        self.assertEqual(1200, BUILD_TIMEOUT_SECONDS)
        self.assertEqual(1200, build_timeout_seconds(None))
        self.assertEqual(3600, BUILD_MAX_TIMEOUT_SECONDS)
        for value in ("1200", "1201", "2400", "2700", "3600"):
            with self.subTest(value=value):
                self.assertEqual(int(value), build_timeout_seconds(value))
        for value in ("", "0", "1199", "3601", "10800", "-1200", "+1200", "01200",
                      "1200.0", "1.2e3", "nan", "inf", " 1200", "1200\n", "１２００", "9" * 1000):
            with self.subTest(value=value), self.assertRaises(ValueError):
                build_timeout_seconds(value)

    def test_invalid_build_capacity_rejects_before_lock_device_or_output(self):
        for arguments in (["--build-only"], ["--classes", FIRST]):
            with (
                self.subTest(arguments=arguments),
                patch.dict(os.environ, {"DAYFORGE_BUILD_TIMEOUT_SECONDS": "3601"}),
                patch("sys.argv", ["gate", *arguments]),
                patch("tools.run_android_tests.Gate") as gate,
                patch("tools.run_android_tests.verification_lock") as lock,
                patch("tools.run_android_tests.subprocess.check_output") as device,
                patch("sys.stderr", new=io.StringIO()),
            ):
                self.assertEqual(2, main())
                lock.assert_not_called(); device.assert_not_called(); gate.assert_not_called()
        with patch.dict(os.environ, {"DAYFORGE_BUILD_TIMEOUT_SECONDS": "NaN"}):
            with self.assertRaises(ValueError):
                Gate(self.repo)
        self.assertFalse((self.repo / "android/app").exists())

    def test_main_freezes_build_capacity_before_device_discovery(self):
        constructed = []

        def find_device(*_args, **_kwargs):
            os.environ["DAYFORGE_BUILD_TIMEOUT_SECONDS"] = "3601"
            return "physical-fixture\n"

        def construct(_repo, *, build_timeout):
            gate = Gate(self.repo, build_timeout=build_timeout)
            constructed.append(gate)
            return gate

        with (
            patch.dict(os.environ, {"DAYFORGE_BUILD_TIMEOUT_SECONDS": "2700"}),
            patch("sys.argv", ["gate", "--classes", FIRST]),
            patch("tools.run_android_tests.verification_lock", return_value=contextlib.nullcontext()),
            patch("tools.run_android_tests.subprocess.check_output", side_effect=find_device) as device,
            patch("tools.run_android_tests.Gate", side_effect=construct) as factory,
            patch.object(Gate, "run_gate") as run,
            patch.object(Gate, "cleanup"),
            patch.object(Gate, "finish"),
        ):
            self.assertEqual(0, main())
            device.assert_called_once()
            self.assertEqual({"build_timeout": 2700}, factory.call_args.kwargs)
            self.assertEqual(2700, constructed[0].build_timeout_seconds)
            run.assert_called_once_with(build_only=False, classes=(FIRST,), shards=())

    def test_scoped_build_budget_matches_execution_without_changing_test_budget(self):
        with patch.dict(os.environ, {
            "DAYFORGE_BUILD_TIMEOUT_SECONDS": "2700",
            "DAYFORGE_TARGETED_TIMEOUT_SECONDS": "4500",
        }):
            gate = ScopedFakeGate(self.repo)
        with patch.dict(os.environ, {
            "DAYFORGE_BUILD_TIMEOUT_SECONDS": "3600",
            "DAYFORGE_TARGETED_TIMEOUT_SECONDS": "9000",
        }):
            gate.run_gate(classes=(FIRST,))
        gate.cleanup(); gate.finish()
        build_argv, build_deadline = next(
            (argv, seconds) for name, argv, seconds in gate.calls if name == "build"
        )
        self.assertEqual(2700, build_deadline)
        self.assertEqual(["./gradlew", "--no-daemon", "lintDebug", "assembleDebug",
                          "assembleDeviceTest", "assembleDeviceTestAndroidTest"], build_argv)
        build = json.loads((gate.run / "build-budget.json").read_text())
        self.assertEqual("build", build["scope"])
        self.assertEqual("targeted", build["mode"])
        self.assertEqual([FIRST], build["selected_classes"])
        self.assertEqual([], build["selected_shards"])
        self.assertEqual(build_deadline, build["command_timeout_seconds"])
        self.assertEqual(build_argv[2:], build["tasks"])
        budget = json.loads((gate.run / "targeted-budget.json").read_text())
        self.assertEqual(2700, budget["build_timeout_seconds"])
        self.assertEqual(4500, budget["command_timeout_seconds"])
        self.assertEqual(4500, budget["gradle_timeout_seconds"])
        self.assertEqual(150000, budget["case_timeout_msec"])
        argv, deadline = next((argv, seconds) for name, argv, seconds in gate.calls if name == "targeted")
        self.assertEqual(4500, deadline)
        self.assertIn("-Pdayforge.targetedTimeoutSeconds=4500", argv)
        self.assertIn("-Pandroid.testInstrumentationRunnerArguments.timeout_msec=150000", argv)

    def test_build_only_retains_budget_on_success_or_failure_without_device_pass(self):
        for fault in (None, "build"):
            with self.subTest(fault=fault):
                with patch.dict(os.environ, {"DAYFORGE_BUILD_TIMEOUT_SECONDS": "2400"}):
                    gate = FakeGate(self.make_repo("success" if fault is None else "failure"), fault)
                if fault is None:
                    gate.run_gate(build_only=True)
                else:
                    with self.assertRaisesRegex(ValueError, "Injected command failure: build"):
                        gate.run_gate(build_only=True)
                gate.cleanup(); gate.finish()
                self.assertEqual(["build"], [name for name, _, _ in gate.calls])
                self.assertEqual(2400, gate.calls[0][2])
                budget = json.loads((gate.run / "build-budget.json").read_text())
                self.assertEqual("build_only", budget["mode"])
                self.assertEqual(2400, budget["command_timeout_seconds"])
                self.assertNotIn("status", budget)
                self.assertIsNone(gate.evidence)
                self.assertFalse((gate.run / "complete.json").exists())
                self.assertFalse((gate.run / "targeted.json").exists())
                self.assertFalse((gate.run / "targeted-budget.json").exists())

    def test_explicit_build_capacity_leaves_full_shard_deadlines_unchanged(self):
        with patch.dict(os.environ, {"DAYFORGE_BUILD_TIMEOUT_SECONDS": "3600"}):
            gate = FakeGate(self.repo)
        gate.run_gate(); gate.cleanup()
        self.assertEqual(3600, next(seconds for name, _, seconds in gate.calls if name == "build"))
        shards = [(argv, seconds) for name, argv, seconds in gate.calls if name.startswith("shard-")]
        self.assertEqual(3, len(shards))
        for argv, seconds in shards:
            self.assertEqual(10800, seconds)
            self.assertIn("-Pandroid.testInstrumentationRunnerArguments.timeout_msec=150000", argv)
            self.assertFalse(any(arg.startswith("-Pdayforge.targetedTimeoutSeconds=") for arg in argv))
        self.assertEqual("full", json.loads((gate.run / "build-budget.json").read_text())["mode"])
        self.assertFalse((gate.run / "targeted-budget.json").exists())


if __name__ == "__main__":
    unittest.main()
