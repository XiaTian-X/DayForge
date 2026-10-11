"""Owned host/USB lifecycle boundaries; not Android/FastAPI behavior evidence."""

import io
import json
import signal
import subprocess
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from tools.android_joint_backend import (
    JOINT_CLASS,
    JOINT_MATERIAL_CLASS,
    JOINT_SCHEDULER_CLASS,
    backend_for_cases,
    fixture_environment,
)


class AndroidJointBackendTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        config = self.root / "backend/src/config.py"
        config.parent.mkdir(parents=True)
        config.write_text(
            "class Settings:\n    DATABASE_URL: str\n    JWT_SECRET_KEY: str\n    ENVIRONMENT: str\n    ASSET_ROOT: str\n"
        )
        self.gate = SimpleNamespace(
            repo=self.root, run=self.root, adb_command=Mock(), evidence={}
        )
        self.expected = {(JOINT_CLASS, "actualJoint")}
        self.process = Mock(pid=45001, returncode=None)
        self.process.poll.side_effect = lambda: self.process.returncode
        self.process.wait.side_effect = self.wait
        self.proof = {"valid": True, "active_timer_count": 0, "protocol_version": 5}
        self.url = "http://127.0.0.1:45001"
        for target, replacement in (
            ("tools.android_joint_backend.subprocess.Popen", self.start),
            ("tools.android_joint_backend.os.killpg", Mock()),
            ("tools.android_joint_backend.urlopen", lambda *_a, **_k: self.identity()),
        ):
            item = (
                patch(target, side_effect=replacement)
                if callable(replacement)
                else patch(target, replacement)
            )
            item.start()
            self.addCleanup(item.stop)
        output = patch("sys.stdout", new=io.StringIO())
        output.start()
        self.addCleanup(output.stop)

    def identity(self):
        response = io.StringIO('{"protocol_version":5}')
        response.status = 200
        return response

    def start(self, argv, **kwargs):
        self.assertEqual(self.root, kwargs["cwd"])
        self.assertEqual(str(self.root / "backend"), kwargs["env"]["PYTHONPATH"])
        self.assertTrue(kwargs["start_new_session"])
        self.assertEqual(
            [
                "uv",
                "run",
                "--frozen",
                "--no-env-file",
                "--project",
                str(self.root / "backend"),
                "python",
                "-m",
                "tests.android_joint_server",
            ],
            argv[:9],
        )
        self.directory = Path(argv[-1])
        self.directory.mkdir()
        (self.directory / "ready.json").write_text(json.dumps({"url": self.url}))
        return self.process

    def wait(self, timeout):
        self.assertEqual(15, timeout)
        self.process.returncode = 0
        (self.directory / "complete.json").write_text(json.dumps(self.proof))
        return 0

    def test_unrelated_classes_do_not_start_backend_or_mutate_phone(self):
        with backend_for_cases(
            self.gate, {("com.dayforge.UnrelatedTest", "one")}
        ) as args:
            self.assertEqual([], args)
        self.gate.adb_command.assert_not_called()
        self.assertFalse((self.root / "joint-backend").exists())

    def test_material_joint_class_alone_provisions_the_actual_owned_peer(self):
        with backend_for_cases(
            self.gate, {(JOINT_MATERIAL_CLASS, "actualMaterial")}
        ) as args:
            self.assertEqual(1, len(args))
        self.assertEqual(self.proof, self.gate.evidence["joint_backend"])
        self.gate.adb_command.assert_any_call(
            "joint-reverse-remove", "reverse", "--remove", "tcp:45001"
        )

    def test_environment_cannot_import_deployment_data_or_supervisor_flags(self):
        with patch.dict(
            "os.environ",
            {
                "DATABASE_URL": "formal-db",
                "asset_root": "formal-assets",
                "JWT_SECRET_KEY": "formal-key",
                "ENVIRONMENT": "production",
                "UVICORN_ENV_FILE": "formal.env",
                "UV_ENV_FILE": "formal.env",
                "WEB_CONCURRENCY": "4",
            },
            clear=True,
        ):
            isolated = fixture_environment(self.root)
        self.assertNotIn("DATABASE_URL", isolated)
        self.assertNotIn("asset_root", isolated)
        self.assertNotIn("UVICORN_ENV_FILE", isolated)
        self.assertNotIn("UV_ENV_FILE", isolated)
        self.assertNotIn("WEB_CONCURRENCY", isolated)
        self.assertEqual("development", isolated["ENVIRONMENT"])
        self.assertNotEqual("formal-key", isolated["JWT_SECRET_KEY"])

    def test_scheduler_joint_class_requires_owned_peer_and_terminal_proof(self):
        with backend_for_cases(
            self.gate, {(JOINT_SCHEDULER_CLASS, "actualScheduler")}
        ) as args:
            self.assertEqual(1, len(args))
            self.assertEqual({}, self.gate.evidence)
        self.assertEqual(self.proof, self.gate.evidence["joint_backend"])
        self.gate.adb_command.assert_any_call(
            "joint-reverse-remove", "reverse", "--remove", "tcp:45001"
        )

    def test_owned_loopback_uses_no_rebind_and_removes_only_owned_port(self):
        with backend_for_cases(self.gate, self.expected) as args:
            self.assertEqual(
                [
                    "-Pandroid.testInstrumentationRunnerArguments.dayforgeJointPort=45001"
                ],
                args,
            )
            self.assertEqual({}, self.gate.evidence)
        self.assertEqual(self.proof, self.gate.evidence["joint_backend"])
        self.assertEqual(
            [
                (
                    (
                        "joint-reverse",
                        "reverse",
                        "--no-rebind",
                        "tcp:45001",
                        "tcp:45001",
                    ),
                    {},
                ),
                (("joint-reverse-remove", "reverse", "--remove", "tcp:45001"), {}),
            ],
            self.gate.adb_command.call_args_list,
        )
        self.process.wait.assert_called_once_with(timeout=15)

    def test_body_failure_still_joins_process_and_removes_port_without_green_proof(
        self,
    ):
        with self.assertRaisesRegex(RuntimeError, "device failure"):
            with backend_for_cases(self.gate, self.expected):
                raise RuntimeError("device failure")
        self.assertEqual(0, self.process.returncode)
        self.assertEqual({}, self.gate.evidence)
        self.assertEqual(
            "joint-reverse-remove", self.gate.adb_command.call_args_list[-1].args[0]
        )

    def test_remote_or_noncanonical_endpoint_never_reaches_phone(self):
        self.url = "http://example.invalid:45001"
        with self.assertRaisesRegex(ValueError, "owned loopback"):
            with backend_for_cases(self.gate, self.expected):
                self.fail("foreign endpoint admitted")
        self.gate.adb_command.assert_not_called()

    def test_live_timer_terminal_proof_cannot_be_declared_complete(self):
        self.proof["active_timer_count"] = 1
        with self.assertRaisesRegex(ValueError, "terminal database proof"):
            with backend_for_cases(self.gate, self.expected):
                pass
        self.assertEqual({}, self.gate.evidence)
        self.assertEqual(
            "joint-reverse-remove", self.gate.adb_command.call_args_list[-1].args[0]
        )

    def test_failed_reverse_never_removes_someone_elses_mapping(self):
        self.gate.adb_command.side_effect = ValueError("already bound")
        with self.assertRaisesRegex(ValueError, "already bound"):
            with backend_for_cases(self.gate, self.expected):
                pass
        self.gate.adb_command.assert_called_once()
        self.process.wait.assert_called_once_with(timeout=15)

    def test_timed_out_shutdown_kills_owned_group_and_removes_port_but_still_fails(
        self,
    ):
        self.process.wait.side_effect = [subprocess.TimeoutExpired("owned", 15), 0]
        with patch("tools.android_joint_backend.os.killpg") as kill:
            with self.assertRaisesRegex(ValueError, "shut down normally"):
                with backend_for_cases(self.gate, self.expected):
                    pass
            self.assertEqual(
                [((45001, signal.SIGTERM), {}), ((45001, signal.SIGKILL), {})],
                kill.call_args_list,
            )
        self.assertEqual({}, self.gate.evidence)
        self.assertEqual(
            "joint-reverse-remove", self.gate.adb_command.call_args_list[-1].args[0]
        )
