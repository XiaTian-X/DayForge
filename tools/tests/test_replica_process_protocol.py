"""Fixed host protocol tests only; no adb, Android, network, build or installation calls.
Not yet executed. Passing these cannot prove the Android recovery scenario or safe close.
"""
import hashlib
import importlib.util
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest

SPEC = importlib.util.spec_from_file_location("replica_process_protocol", Path(__file__).parents[1] / "run_command_process_gates.py")
P = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(P)
RUN = "00000000-0000-4000-8000-000000000001"
RECOVERY = "00000000-0000-4000-8000-000000000002"
NONCE = "1" * 32
CASE = "file_repair"
ABORT = "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n"


def base(phase, pid):
    return dict(format=1, family=P.REPLICA_DRIVER, run=RUN, case=CASE, nonce=NONCE, phase=phase,
        target_package=P.TARGET, process_name=P.TARGET, pid=pid, phase_budget_ms=150000)


def pair(body):
    raw = json.dumps(body, separators=(",", ":")).encode()
    return body, hashlib.sha256(raw).hexdigest()


def prepared():
    body = base("prepare_bound", 101)
    body.update(stage="bound_closed", seed_sha256="a"*64, archive_sha256="b"*64,
        stage_sha256="c"*64, recovery_id=RECOVERY, resources_closed=True, no_unknown_graph_boundary=True,
        expected_interruption_is_not_junit_pass=True)
    return pair(body)


def fault():
    old, digest = prepared(); body = dict(old)
    body.update(phase="actual_fault", pid=102, stage="fault_closed", prepare_pid=101,
        prepare_checkpoint_sha256=digest, fault_code="IMAGE_HASH", injection_hits=1,
        exact_fault_cause_confirmed=True, release_fate="CLOSED", stage_sha256="d"*64)
    return pair(body)


def terminal():
    body = base("fresh_complete", 103)
    body.update(stage="terminal", result="same_replica_completed_and_usable", prepare_pid=101, fault_pid=102,
        prepare_checkpoint_sha256=prepared()[1], fault_checkpoint_sha256=fault()[1],
        seed_sha256="a"*64, archive_sha256="b"*64, recovery_id=RECOVERY, resources_closed=True,
        assertions={key: True for key in P.REPLICA_CHECKS})
    return body


class SequenceHost(P.ReplicaHost):
    def __init__(self, output, before="", after=ABORT, left_before=None, left_after=None):
        super().__init__("NEVER_EXECUTED_ADB", "host-only-fixed", output, 2700)
        self.before, self.after, self.left_before, self.left_after = before, after, left_before, left_after
        self.signals = 0
        self.process = SimpleNamespace(returncode=None, pid=999)
        self.process.poll = lambda: self.process.returncode

    def begin_replica(self, folder, run, case, phase, nonce):
        assert (run, case, phase, nonce) == (RUN, CASE, "prepare_bound", NONCE)
        self.handle = (folder / "instrumentation.txt").open("xb")
        self.handle.write(self.before.encode()); self.handle.flush()
        self.running = self.process
        return self.process, self.handle

    def evidence(self, run, case, phase, filename):
        assert (run, case, phase) == (RUN, CASE, "prepare_bound")
        if filename == "checkpoint.json":
            body = prepared()[0]
        elif filename == "checkpoint-left.json":
            body = self.left_after if self.signals else self.left_before
            if body is None:
                return None
        else:
            assert filename == "driver-terminal.json"
            return None
        return body, json.dumps(body, separators=(",", ":")).encode()

    def pids(self):
        return set() if self.signals else {101}

    def call(self, *args, check=True):
        assert check
        if args == ("shell", "run-as", P.TARGET, "cat", "/proc/101/cmdline"):
            return SimpleNamespace(stdout=P.TARGET.encode() + b"\x00", returncode=0, stderr=b"")
        assert args == ("shell", "run-as", P.TARGET, "kill", "-9", "101")
        self.signals += 1
        self.handle.write(self.after.encode()); self.handle.flush()
        self.process.returncode = 0
        return SimpleNamespace(stdout=b"", stderr=b"", returncode=0)

    def pause(self, seconds):
        raise AssertionError("Deterministic host tests must never wait or launch anything")


class ReplicaProcessProtocolTest(unittest.TestCase):
    def test_three_phase_identity_and_full_fixed_oracle(self):
        P.validate_replica_checkpoint(prepared()[0], RUN, CASE, NONCE, "prepare_bound", 101)
        P.validate_replica_checkpoint(fault()[0], RUN, CASE, NONCE, "actual_fault", 102, prepared())
        P.validate_replica_terminal(terminal(), RUN, CASE, NONCE, 103, prepared(), fault())
        self.assertEqual(P.GROUPS["count"], ("pre_t0_end", "post_t0_end_pre_return", "pre_t1_end", "post_t1_end_pre_return"))
        self.assertEqual(len(P.GROUPS["metric"]), 3)

    def test_wrong_phase_nonce_pid_and_missing_prior_never_match(self):
        for key, value in (("phase", "prepare_bound"), ("nonce", "2"*32), ("pid", 101),
                ("prepare_pid", 99), ("prepare_checkpoint_sha256", "e"*64), ("seed_sha256", "e"*64),
                ("archive_sha256", "e"*64), ("injection_hits", True), ("resources_closed", 1)):
            with self.subTest(key=key):
                body = dict(fault()[0]); body[key] = value
                with self.assertRaises(P.Failed):
                    P.validate_replica_checkpoint(body, RUN, CASE, NONCE, "actual_fault", 102, prepared())
        with self.assertRaises(P.Failed):
            P.validate_replica_checkpoint(fault()[0], RUN, CASE, NONCE, "actual_fault", 102)

    def test_missing_terminal_or_any_original_assertion_is_not_pass(self):
        for body in (None, {}, dict(terminal(), pid=102), dict(terminal(), fault_checkpoint_sha256="e"*64),
                dict(terminal(), assertions={}), dict(terminal(), resources_closed=False)):
            with self.subTest(body=body):
                with self.assertRaises(P.Failed):
                    P.validate_replica_terminal(body, RUN, CASE, NONCE, 103, prepared(), fault())
        for key in P.REPLICA_CHECKS:
            body = terminal(); body["assertions"][key] = 1
            with self.assertRaises(P.Failed):
                P.validate_replica_terminal(body, RUN, CASE, NONCE, 103, prepared(), fault())

    def test_success_driver_requires_exact_final_phase_and_all_fields(self):
        values = dict(df_replica_driver=P.REPLICA_DRIVER, df_replica_run=RUN, df_replica_case=CASE,
            df_replica_nonce=NONCE, df_replica_phase="fresh_complete", df_replica_pid="103",
            df_replica_phase_budget_ms="150000", df_replica_result="PASS", df_replica_assertion_groups="8")
        log = "".join(f"INSTRUMENTATION_RESULT: {k}={v}\n" for k, v in values.items()) + "INSTRUMENTATION_CODE: -1\n"
        P.validate_replica_driver(log, RUN, CASE, NONCE, 103)
        for invalid in ("", log.replace("fresh_complete", "actual_fault"), log.replace("PASS", "FAIL"),
                log.replace("=8", "=7"), log + "INSTRUMENTATION_RESULT: df_replica_pid=103\n",
                log + "INSTRUMENTATION_RESULT: df_replica_failure_type=IOException\n"):
            with self.assertRaises(P.Failed):
                P.validate_replica_driver(invalid, RUN, CASE, NONCE, 103)

    def rejected_sequence(self, expected_signals=0, **kwargs):
        with TemporaryDirectory() as directory:
            root = Path(directory); host = SequenceHost(root, **kwargs)
            with self.assertRaises(P.Failed):
                host.checkpoint_phase(RUN, CASE, NONCE, "prepare_bound", root / "prepare_bound")
            self.assertEqual(expected_signals, host.signals)
            self.assertFalse((root / "prepare_bound/host-kill-proof.json").exists())

    def test_checkpoint_left_timeout_before_signal_is_rejected(self):
        self.rejected_sequence(left_before=dict(base("prepare_bound", 101), stage="checkpoint_left"))

    def test_driver_failure_before_signal_and_racing_signal_are_rejected(self):
        failure = "INSTRUMENTATION_RESULT: df_replica_result=FAIL\nINSTRUMENTATION_CODE: 0\n"
        self.rejected_sequence(before=failure)
        self.rejected_sequence(after=failure + ABORT, expected_signals=1)
        self.rejected_sequence(left_after=dict(base("prepare_bound", 101), stage="checkpoint_left"), expected_signals=1)

    def test_only_exact_signal_and_android_abort_forms_interruption(self):
        with TemporaryDirectory() as directory:
            root = Path(directory); host = SequenceHost(root)
            result = host.checkpoint_phase(RUN, CASE, NONCE, "prepare_bound", root / "prepare_bound")
            self.assertEqual(result, prepared())
            self.assertEqual(host.signals, 1)
            self.assertTrue((root / "prepare_bound/host-kill-proof.json").exists())
            self.assertFalse((root / "case-result.json").exists())
        self.rejected_sequence(after="INSTRUMENTATION_CODE: 0\n", expected_signals=1)
        self.rejected_sequence(before=ABORT)

    def test_raw_bytes_are_preserved_and_changed_evidence_rejected(self):
        with TemporaryDirectory() as directory:
            root = Path(directory); host = P.ReplicaHost("NEVER_EXECUTED_ADB", "host-only", root, 2700)
            raw = b'{ "space" : 1 }\n'
            self.assertEqual(host.capture(root, "checkpoint.json", ({"space": 1}, raw))[1], hashlib.sha256(raw).hexdigest())
            self.assertEqual((root / "checkpoint.json").read_bytes(), raw)
            with self.assertRaises(P.Failed):
                host.capture(root, "checkpoint.json", ({"space": 1}, b'{"space":1}'))
            with self.assertRaises(P.Failed):
                host.capture(root, "terminal.json", None)

    def test_missing_file_and_transport_failure_are_not_interchangeable(self):
        host = P.ReplicaHost("NEVER_EXECUTED_ADB", "host-only", Path("."), 2700)
        host.call = lambda *a, **kw: SimpleNamespace(returncode=1, stdout=b"", stderr=b"No such file or directory")
        self.assertIsNone(host.evidence(RUN, CASE, "actual_fault", "checkpoint.json"))
        for response in (SimpleNamespace(returncode=1, stdout=b"", stderr=b"Permission denied"),
                SimpleNamespace(returncode=0, stdout=b"{", stderr=b"")):
            host.call = lambda *a, **kw: response
            with self.assertRaises(P.Failed):
                host.evidence(RUN, CASE, "actual_fault", "checkpoint.json")
        with self.assertRaises(P.Failed):
            host.evidence(RUN, CASE, "other", "checkpoint.json")


if __name__ == "__main__":
    unittest.main()
