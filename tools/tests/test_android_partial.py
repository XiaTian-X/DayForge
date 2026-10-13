"""Selected fixed-shard orchestration only; no simulated Android behavior claim."""

import contextlib
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.run_android_tests import SHARDS, main, parse_arguments, selected_shards
from tools.tests.test_android_shards import CASES, FakeGate, discovery


class PartialFakeGate(FakeGate):
    def execute(self, name, argv, timeout=120):
        log = super().execute(name, argv, timeout)
        if name.startswith("partition-"):
            index = int(name[-1])
            cases = CASES[index::SHARDS]
            if self.fault == "overlap" and index == 1:
                cases = CASES[:1]
            elif self.fault == "unexpected" and index == 1:
                cases = [("OtherTest", "unknown")]
            elif self.fault == "incomplete-discovery" and index == 1:
                log.write_text("INSTRUMENTATION_CODE: -1")
                return log
            log.write_text(discovery(cases))
            if self.fault == "discovery-apk-change":
                self.apks()[0].write_bytes(b"changed")
        elif name == "discovery" and self.fault == "missing-partition":
            log.write_text(discovery(CASES + [("Extra", "undiscovered")]))
        return log


class AndroidPartialGateTest(unittest.TestCase):
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

    def test_selection_requires_explicit_proper_subset_and_rejects_mixed_modes(self):
        self.assertEqual((False, (), (1, 2)), parse_arguments(["--shards", "2", "1"]))
        for arguments in (
            ["--shards"],
            ["--shards", "1", "1"],
            ["--shards", "0", "1", "2"],
            ["--shards", "-1"],
            ["--shards", "3"],
            ["--shards", "01"],
            ["--shards", "1", "--classes", "com.dayforge.Test"],
            ["--build-only", "--shards", "1"],
        ):
            with self.subTest(arguments=arguments), self.assertRaises(ValueError):
                parse_arguments(arguments)
        for indices in ((True,), (1.0,), (1, 1), (-1,), (3,), (0, 1, 2), ()):
            with self.subTest(indices=indices), self.assertRaises(ValueError):
                selected_shards(indices)
        for options in (
            {"build_only": True, "shards": (1,)},
            {"classes": ("com.dayforge.Test",), "shards": (1,)},
        ):
            gate = PartialFakeGate(self.repo)
            with self.assertRaises(ValueError):
                gate.run_gate(**options)
            self.assertEqual([], gate.calls)

    def test_partial_checks_fresh_partition_union_but_never_claims_full_or_reuses_results(
        self,
    ):
        gate = PartialFakeGate(self.repo)
        with patch("tools.run_android_tests.backend_for_cases") as backend:
            backend.return_value = contextlib.nullcontext([])
            gate.run_gate(shards=(1, 2))
        self.assertEqual(set(CASES[1:]), backend.call_args.args[1])
        self.assertFalse((gate.run / "partial.json").exists())
        gate.cleanup()
        with patch("sys.stdout", new=io.StringIO()) as output:
            gate.finish()
        self.assertIn("full-suite gate NOT RUN", output.getvalue())
        self.assertFalse((gate.run / "complete.json").exists())
        self.assertFalse((gate.run / "targeted.json").exists())
        proof = json.loads((gate.run / "partial.json").read_text())
        self.assertEqual("partial_complete", proof["status"])
        self.assertEqual([1, 2], proof["selected_shards"])
        self.assertEqual([0], proof["unexecuted_shards"])
        self.assertEqual(2, proof["cases"])
        self.assertEqual(3, len(proof["discovered_cases"]))
        self.assertEqual(3, len(proof["partitions"]))
        self.assertEqual(2, len(proof["coverage_inputs_sha256"]))
        self.assertEqual(2, len(proof["test_reports_sha256"]))
        self.assertEqual("not_run_partial_scope", proof["coverage_calibration"])
        names = [name for name, _, _ in gate.calls]
        self.assertNotIn("shard-0", names)
        self.assertNotIn("coverage", names)
        self.assertLess(names.index("shard-1"), names.index("shard-2"))
        for index in range(SHARDS):
            args = next(
                args for name, args, _ in gate.calls if name == f"partition-{index}"
            )
            self.assertEqual("true", args[args.index("log") + 1])
            self.assertEqual("3", args[args.index("numShards") + 1])
            self.assertEqual(str(index), args[args.index("shardIndex") + 1])
        for name, args, deadline in gate.calls:
            if name.startswith("shard-"):
                self.assertEqual(10800, deadline)
                self.assertIn("-Pdayforge.deviceTestScope=full", args)
                self.assertIn(
                    "-Pandroid.testInstrumentationRunnerArguments.numShards=3", args
                )
                self.assertIn(
                    "-Pandroid.testInstrumentationRunnerArguments.log=false", args
                )
                self.assertIn(
                    "-Pandroid.testInstrumentationRunnerArguments.timeout_msec=150000",
                    args,
                )
                self.assertFalse(any("Arguments.class=" in arg for arg in args))

    def test_discovery_execution_and_cleanup_faults_never_publish_partial_success(self):
        for fault in (
            "overlap",
            "unexpected",
            "incomplete-discovery",
            "missing-partition",
            "discovery-apk-change",
            "partition-2",
            "shard-1",
            "shard-2",
            "missing-case",
            "last-shard-report",
            "missing-ec",
            "last-shard-empty-ec",
            "changed-apk",
            "final-stop-testbed",
        ):
            with self.subTest(fault=fault):
                # Exercise real orchestration including physical check and cleanup;
                # only the command boundaries are faked by this tooling test.
                gate = PartialFakeGate(self.repo, fault)
                with (
                    patch("sys.argv", ["gate", "--shards", "1", "2"]),
                    patch(
                        "tools.run_android_tests.verification_lock",
                        contextlib.nullcontext,
                    ),
                    patch(
                        "tools.run_android_tests.subprocess.check_output",
                        return_value="physical-fixture\n",
                    ),
                    patch("tools.run_android_tests.Gate", return_value=gate),
                    patch("sys.stderr", new=io.StringIO()),
                ):
                    self.assertEqual(1, main())
                self.assertFalse((gate.run / "partial.json").exists())
                self.assertFalse((gate.run / "complete.json").exists())
                self.assertIn("final-stop-testbed", [name for name, _, _ in gate.calls])
                # Next fixture has a fresh build root, just as independent real runs.
                self.temp.cleanup()
                self.temp = tempfile.TemporaryDirectory()
                self.addCleanup(self.temp.cleanup)
                self.repo = Path(self.temp.name)
                (self.repo / "android").mkdir()
                (self.repo / "android/compiler-warning-budget.json").write_text(
                    '{"version":1,"warnings":[]}'
                )


if __name__ == "__main__":
    unittest.main()
