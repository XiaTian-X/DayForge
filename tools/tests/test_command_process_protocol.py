"""Unexecuted host-only regression candidate; never invokes adb/build/install/device.

These deterministic sequences exercise the actual Host.phase1 classification. The mocked
process is only the host protocol boundary; it proves no Android or command behavior.
"""
import importlib.util
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest

SPEC = importlib.util.spec_from_file_location("command_process_protocol", Path(__file__).parents[1] / "run_command_process_gates.py")
PROTOCOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROTOCOL)
RUN = "00000000-0000-4000-8000-000000000001"
NONCE = "1" * 32
CASE = "post_t1_end_pre_return"
PID = 12345
ABORT = "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n"


def checkpoint():
    return {"format": 1, "run": RUN, "case": CASE, "nonce": NONCE, "stage": CASE,
        "target_package": PROTOCOL.TARGET, "process_name": PROTOCOL.TARGET, "pid": PID,
        "native_fault_hits": 1, "outer_commit_boundary_observed": True,
        "expected_interruption_is_not_junit_pass": True}


class FixedSequenceHost(PROTOCOL.Host):
    def __init__(self, folder, before="", after=ABORT, before_marker=None, after_marker=None):
        super().__init__("NEVER_EXECUTED_ADB", "fixed-host-only", folder, 2700)
        self.before, self.after = before, after
        self.before_marker, self.after_marker = before_marker, after_marker
        self.checkpoint = checkpoint()
        self.signals = 0
        self.process = SimpleNamespace(returncode=None)
        self.process.poll = lambda: self.process.returncode

    def begin(self, log, run, case, phase, nonce):
        assert (run, case, phase, nonce) == (RUN, CASE, "prepare", NONCE)
        self.log = log
        self.handle = log.open("xb")
        self.handle.write(self.before.encode()); self.handle.flush()
        self.running = self.process
        return self.process, self.handle

    def app_json(self, run, case, filename):
        assert (run, case) == (RUN, CASE)
        if filename == "checkpoint.json":
            return dict(self.checkpoint)
        if filename == "checkpoint-left.json":
            return self.after_marker if self.signals else self.before_marker
        assert filename == "prepare-terminal.json"
        return None

    def pids(self):
        return set() if self.signals else {PID}

    def call(self, *args, check=True):
        assert check
        if args == ("shell", "run-as", PROTOCOL.TARGET, "cat", f"/proc/{PID}/cmdline"):
            return SimpleNamespace(stdout=PROTOCOL.TARGET.encode() + b"\x00", returncode=0, stderr=b"")
        assert args == ("shell", "run-as", PROTOCOL.TARGET, "kill", "-9", str(PID))
        self.signals += 1
        self.handle.write(self.after.encode()); self.handle.flush()
        self.process.returncode = 0
        return SimpleNamespace(stdout=b"", returncode=0, stderr=b"")

    def pause(self, seconds):
        raise AssertionError("This deterministic sequence never waits or launches a process")


class CommandProcessHostClassificationTest(unittest.TestCase):
    def rejected_sequence(self, before="", after=ABORT, expected_signals=0, before_marker=None, after_marker=None):
        with TemporaryDirectory() as directory:
            folder = Path(directory)
            host = FixedSequenceHost(folder, before, after, before_marker, after_marker)
            with self.assertRaises(PROTOCOL.Failed):
                host.phase1(RUN, CASE, NONCE, folder)
            self.assertEqual(expected_signals, host.signals)
            self.assertFalse((folder / "host-kill-proof.json").exists())
            self.assertTrue((folder / "prepare-instrumentation.txt").is_file())

    def test_retained_checkpoint_and_live_adb_cannot_hide_driver_failure(self):
        self.rejected_sequence(before="INSTRUMENTATION_RESULT: df_command_result=FAIL\nINSTRUMENTATION_CODE: 0\n")

    def test_retained_checkpoint_cannot_hide_unexpected_driver_success(self):
        self.rejected_sequence(before="INSTRUMENTATION_RESULT: df_command_result=PASS\nINSTRUMENTATION_CODE: -1\n")

    def test_terminal_arriving_between_precheck_and_signal_is_rejected_after_drain(self):
        self.rejected_sequence(after="INSTRUMENTATION_RESULT: df_command_result=FAIL\n" + ABORT, expected_signals=1)

    def test_checkpoint_left_marker_rejects_timeout_even_without_flushed_terminal(self):
        marker = {"run": RUN, "case": CASE, "nonce": NONCE, "pid": PID, "stage": "checkpoint_left"}
        self.rejected_sequence(before_marker=marker)

    def test_checkpoint_left_racing_signal_is_rejected_after_signal(self):
        marker = {"run": RUN, "case": CASE, "nonce": NONCE, "pid": PID, "stage": "checkpoint_left"}
        self.rejected_sequence(after_marker=marker, expected_signals=1)

    def test_wrong_identity_marker_is_failure_not_absence(self):
        self.rejected_sequence(before_marker={"run": "other"})

    def test_unexplained_normal_canceled_code_is_not_expected_kill(self):
        self.rejected_sequence(after="INSTRUMENTATION_CODE: 0\n", expected_signals=1)

    def test_duplicate_abort_codes_are_rejected(self):
        self.rejected_sequence(after=ABORT + "INSTRUMENTATION_CODE: 0\n", expected_signals=1)

    def test_android_abort_before_signal_is_rejected(self):
        self.rejected_sequence(before=ABORT)

    def test_actual_signal_followed_only_by_android_abort_is_expected_interruption(self):
        with TemporaryDirectory() as directory:
            folder = Path(directory); host = FixedSequenceHost(folder)
            self.assertEqual(PID, host.phase1(RUN, CASE, NONCE, folder))
            self.assertEqual(1, host.signals)
            self.assertTrue((folder / "host-kill-proof.json").is_file())
            self.assertFalse((folder / "case-result.json").exists())  # No phase2 oracle, no passed case.

    def test_checkpoint_bool_fields_are_not_truthiness_or_integer_equivalence(self):
        for name in ("outer_commit_boundary_observed", "expected_interruption_is_not_junit_pass"):
            for invalid in (False, 1, 0, "true", None):
                with self.subTest(name=name, invalid=invalid):
                    value = checkpoint(); value[name] = invalid
                    with self.assertRaises(PROTOCOL.Failed):
                        PROTOCOL.validate_checkpoint(value, RUN, CASE, NONCE)

    def test_checkpoint_pid_nonce_and_hit_identity_are_exact(self):
        for name, invalid in (("pid", True), ("pid", 0), ("nonce", "wrong"), ("native_fault_hits", True), ("native_fault_hits", 2)):
            with self.subTest(name=name):
                value = checkpoint(); value[name] = invalid
                with self.assertRaises(PROTOCOL.Failed):
                    PROTOCOL.validate_checkpoint(value, RUN, CASE, NONCE)

    def test_marker_read_permission_error_is_not_absence(self):
        host = PROTOCOL.Host("NEVER_EXECUTED_ADB", "fixed-host-only", Path("."), 2700)
        host.call = lambda *args, **kwargs: SimpleNamespace(returncode=1, stdout=b"", stderr=b"Permission denied")
        with self.assertRaises(PROTOCOL.Failed):
            host.app_json(RUN, CASE, "checkpoint-left.json")

    def test_only_missing_marker_is_absence(self):
        host = PROTOCOL.Host("NEVER_EXECUTED_ADB", "fixed-host-only", Path("."), 2700)
        host.call = lambda *args, **kwargs: SimpleNamespace(returncode=1, stdout=b"", stderr=b"cat: fixed path: No such file or directory")
        self.assertIsNone(host.app_json(RUN, CASE, "checkpoint-left.json"))


if __name__ == "__main__":
    unittest.main()
