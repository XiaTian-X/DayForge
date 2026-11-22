"""Pure host negative protocol contracts. No Android/backend execution or discovery."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location("dual_protocol", Path(__file__).parents[1] / "run_dual_physical_acceptance.py")
D = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(D)

# Deliberately synthetic identifiers; never select a connected device by default.
SYNTHETIC_SERIALS = {"A": "synthetic-physical-A", "B": "synthetic-physical-B"}


def identity(n):
    return f"ad670000-0000-4000-8000-{n:012d}"


class DualPhysicalProtocol(unittest.TestCase):
    def setUp(self):
        self.run, self.nonce, self.previous = identity(1), "a" * 32, "b" * 64
        keys = sorted(D.ADDED["A1"] | D.ADDED["B1"])
        self.shared = {key: identity(10 + n) for n, key in enumerate(keys)}
        self.report = {"format": "dual-physical-phase/v1", "status": "PASS", "owners_closed": True,
                       "run": self.run, "nonce": self.nonce, "phase": "B2", "role": "B", "previous": self.previous,
                       "request_witnesses": [], "pid": 123, "widget_scope": "isolated", "offline_transport": "java.net.ConnectException", "installation": self.shared["installation_b"],
                       "device": self.shared["device_b"], "shared": self.shared | {"count_fact": identity(30), "count_operation": identity(31)},
                       "checks": {key: True for key in ("fresh_authority", "same_installation", "original_domain", "actual_end", "exact_effects", "known_close")},
                       "native": [{"kind": kind, "sequence": n + 1, "begin": n * 10, "end": n * 10 + 3, "accepted": []}
                                  for n, kind in enumerate(("T0", "COUNT_T1"))]}
        self.bundle = {"df_dual_driver": "dual-physical/v1", "df_dual_pid": "123"} | {
            "df_dual_" + key: self.report[key] for key in ("run", "nonce", "phase", "previous")}

    def validate(self, report=None, **change):
        params = dict(report=self.report if report is None else report, bundle=self.bundle, phase="B2", run=self.run,
                      nonce=self.nonce, previous=self.previous, prior_shared=self.shared, pid=123)
        params.update(change)
        with mock.patch.object(D.subprocess, "run", side_effect=AssertionError("Pure check cannot access devices")):
            return D.validate_report(**params)

    def test_exact_original_ids_native_end_and_known_close(self):
        self.assertEqual(self.report["shared"], self.validate())

    def test_rejects_different_process_or_previous_phase(self):
        for key, value in (("pid", 124), ("previous", "c" * 64), ("nonce", "c" * 32)):
            body = copy.deepcopy(self.report); body[key] = value
            with self.assertRaises(D.P.Failed): self.validate(body)

    def test_known_close_cannot_be_inferred_from_success_or_finish(self):
        body = copy.deepcopy(self.report); body["owners_closed"] = False
        with self.assertRaises(D.P.Failed): self.validate(body)

    def test_second_logical_device_is_not_a_second_installation(self):
        body = copy.deepcopy(self.report); body["shared"]["installation_b"] = body["shared"]["installation_a"]
        body["installation"] = body["shared"]["installation_b"]
        with self.assertRaises(D.P.Failed): self.validate(body)

    def test_phase_marker_does_not_replace_original_commit_end(self):
        body = copy.deepcopy(self.report); body["native"].pop()
        with self.assertRaises(D.P.Failed): self.validate(body)

    def test_auth_or_codec_io_failure_is_not_network_isolation(self):
        body = copy.deepcopy(self.report); body["offline_transport"] = "java.io.IOException"
        with self.assertRaises(D.P.Failed): self.validate(body)

    def test_offline_phase_cannot_include_an_ack(self):
        body = copy.deepcopy(self.report)
        body["native"].append({"kind": "ACK", "sequence": 3, "begin": 30, "end": 40, "accepted": [identity(31)]})
        with self.assertRaises(D.P.Failed): self.validate(body)

    def test_shared_identity_cannot_be_rewritten_by_other_device(self):
        body = copy.deepcopy(self.report); body["shared"]["habit"] = identity(99)
        with self.assertRaises(D.P.Failed): self.validate(body)

    def test_unexpected_authority_or_credentials_never_enter_shared_metadata(self):
        body = copy.deepcopy(self.report); body["shared"]["access_token"] = "synthetic-secret"
        with self.assertRaises(D.P.Failed): self.validate(body)

    def test_missing_assertion_group_is_failure(self):
        body = copy.deepcopy(self.report); body["checks"].pop("fresh_authority")
        with self.assertRaises(D.P.Failed): self.validate(body)

    def test_junit_or_p1_pass_cannot_be_dual_result(self):
        for text in ("OK (5 tests)\nINSTRUMENTATION_CODE: -1\n",
                     "INSTRUMENTATION_RESULT: df_command_result=PASS\nINSTRUMENTATION_CODE: -1\n",
                     "INSTRUMENTATION_RESULT: df_dual_result=PASS\nINSTRUMENTATION_CODE: 0\n"):
            with self.assertRaises(D.P.Failed): D.result_bundle(text)

    def test_duplicate_instrumentation_terminal_cannot_hide_failure(self):
        text = "INSTRUMENTATION_RESULT: df_dual_result=FAIL\nINSTRUMENTATION_RESULT: df_dual_result=PASS\nINSTRUMENTATION_CODE: -1\n"
        with self.assertRaises(D.P.Failed): D.result_bundle(text)

    def test_api35_or_plain_migration_cannot_be_api27_joint_admission(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for item in ({"serial": SYNTHETIC_SERIALS["A"], "api": 35}, {"serial": SYNTHETIC_SERIALS["B"], "api": 26}):
                with self.assertRaises(D.P.Failed): D.validate_ordinary(root, item, {}, {}, api27_serial=SYNTHETIC_SERIALS["B"])
            original = {"format": "ordinary-whole-batch-mapping/v1", "validated_success": True, "exit_code": 0,
                        "source_drift": [], "new_sources": [], "validation_errors": [],
                        "classes": {"CountCommandMigrationTest": {"cases": 6}}, "expected_class_methods": {"CountCommandMigrationTest": 6}}
            file = root / "ordinary.json"; file.write_text(json.dumps(original))
            item = {"serial": SYNTHETIC_SERIALS["B"], "api": 27, "path": "ordinary.json", "sha256": D.sha(file)}
            with self.assertRaises(D.P.Failed): D.validate_ordinary(root, item, {}, {}, api27_serial=SYNTHETIC_SERIALS["B"])


class DualFailureEvidence(unittest.TestCase):
    def device(self, root):
        return D.Device("synthetic-adb-never-executed", "B", SYNTHETIC_SERIALS["B"], root / "device", 10)

    def test_timeout_keeps_original_partial_bytes_and_same_exception(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); device = self.device(root)
            original = D.subprocess.TimeoutExpired(["synthetic"], 1, output=b"partial\xffout", stderr=b"partial-error")
            with mock.patch.object(D.subprocess, "run", side_effect=original) as invoke:
                with self.assertRaises(D.subprocess.TimeoutExpired) as raised: device.call("shell", "fixed-read")
            self.assertIs(original, raised.exception); self.assertEqual(1, invoke.call_count)
            self.assertEqual(b"partial\xffout", (device.output / "command-0001.stdout").read_bytes())
            self.assertEqual(b"partial-error", (device.output / "command-0001.stderr").read_bytes())
            terminal = json.loads((device.output / "command-0001.result.json").read_text())
            self.assertTrue(terminal["timed_out"]); self.assertTrue(terminal["stdout_available"])
            self.assertTrue(terminal["stderr_available"]); self.assertIsNone(terminal["exit_code"])

    def test_no_partial_output_is_distinct_from_received_empty_output(self):
        with tempfile.TemporaryDirectory() as directory:
            device = self.device(Path(directory))
            original = D.subprocess.TimeoutExpired(["synthetic"], 1, output=b"", stderr=None)
            with mock.patch.object(D.subprocess, "run", side_effect=original):
                with self.assertRaises(D.subprocess.TimeoutExpired): device.call("shell", "fixed-read")
            terminal = json.loads((device.output / "command-0001.result.json").read_text())
            self.assertTrue(terminal["stdout_available"]); self.assertFalse(terminal["stderr_available"])
            self.assertEqual(b"", (device.output / "command-0001.stderr").read_bytes())

    def test_process_start_error_records_failure_and_propagates_same_object(self):
        with tempfile.TemporaryDirectory() as directory:
            device = self.device(Path(directory)); original = OSError("synthetic-start-error")
            with mock.patch.object(D.subprocess, "run", side_effect=original):
                with self.assertRaises(OSError) as raised: device.call("shell", "fixed-read")
            self.assertIs(original, raised.exception)
            terminal = json.loads((device.output / "command-0001.result.json").read_text())
            self.assertEqual("OSError", terminal["error_type"]); self.assertFalse(terminal["timed_out"])
            self.assertFalse(terminal["stdout_available"]); self.assertIsNone(terminal["exit_code"])

    def test_evidence_write_failure_cannot_replace_original_timeout(self):
        with tempfile.TemporaryDirectory() as directory:
            device = self.device(Path(directory)); original = D.subprocess.TimeoutExpired(["synthetic"], 1, output=b"partial")
            with mock.patch.object(D.subprocess, "run", side_effect=original), mock.patch.object(Path, "write_bytes", side_effect=OSError("synthetic-disk")):
                with self.assertRaises(D.subprocess.TimeoutExpired) as raised: device.call("shell", "fixed-read")
            self.assertIs(original, raised.exception)
            self.assertIn("DIAGNOSTIC_PERSISTENCE_FAILED:OSError", original.__notes__)

    def test_phase_forensic_failure_cannot_mask_original_assertion(self):
        class Process:
            pid = 456
            def __init__(self): self.returncode = None; self.waits = []
            def poll(self): return self.returncode
            def wait(self, **kwargs): self.waits.append(kwargs); self.returncode = 0; return 0
        for pre_wait in (False, True):
            with self.subTest(pre_wait=pre_wait), tempfile.TemporaryDirectory() as directory:
                device = self.device(Path(directory))
                original = OSError("synthetic-pre-wait") if pre_wait else AssertionError("synthetic-phase")
                process = Process()
                real_save = D.save
                def save(path, body):
                    if pre_wait and path.name == "pid.json": raise original
                    return real_save(path, body)
                with mock.patch.object(device, "stopped"), mock.patch.object(device, "image", return_value={}), \
                     mock.patch.object(device, "pids", return_value={123}), \
                     mock.patch.object(device, "call", return_value=D.subprocess.CompletedProcess([], 0, D.TARGET.encode() + b"\0", b"")), \
                     mock.patch.object(device, "collect_failure", side_effect=OSError("synthetic-collector")), \
                     mock.patch.object(D.subprocess, "Popen", return_value=process), \
                     mock.patch.object(D.os, "killpg") as signals, \
                     mock.patch.object(D, "save", side_effect=save), \
                     mock.patch.object(D, "result_bundle", side_effect=original):
                    with self.assertRaises(type(original)) as raised:
                        device.phase("B2", identity(1), "a" * 32, "b" * 64, {}, 40001, {})
                self.assertIs(original, raised.exception)
                self.assertIn("PHASE_FORENSIC_FAILURE:OSError", original.__notes__)
                self.assertIsNone(device.stage_deadline)
                if pre_wait:
                    signals.assert_called_once_with(456, D.signal.SIGTERM)
                    self.assertEqual([{"timeout": 5}], process.waits)
                else:
                    signals.assert_not_called()

    def failure(self):
        def node(kind, line):
            return {"type": kind, "frame_count": 1, "frames": [{"class": "com.dayforge.DualPhysicalScenario", "method": "offlineCount", "file": "DualPhysicalScenario.kt", "line": line}],
                    "frames_truncated": False, "cause": None, "suppressed_count": 0, "suppressed": [], "suppressed_truncated": False}
        primary = node("java.lang.AssertionError", 321)
        primary["cause"] = node("java.io.IOException", 78)
        primary["suppressed"] = [node("java.lang.IllegalStateException", 456)]; primary["suppressed_count"] = 1
        return {"format": "dual-physical-failure/v1", "run": identity(1), "phase": "B2", "nonce": "a" * 32,
                "previous": "b" * 64, "pid": 123, "failure": primary}

    def validate_failure(self, report):
        return D.validate_failure_report(report, phase="B2", run=identity(1), nonce="a" * 32, previous="b" * 64, pid=123)

    def test_safe_stack_cause_and_suppressed_keep_original_primary_location(self):
        report = self.failure()
        self.assertEqual("java.lang.AssertionError", self.validate_failure(report))
        self.assertEqual(321, report["failure"]["frames"][0]["line"])
        self.assertEqual("java.lang.IllegalStateException", report["failure"]["suppressed"][0]["type"])

    def test_arbitrary_exception_message_is_not_a_safe_diagnostic(self):
        report = self.failure(); report["failure"]["cause"]["message"] = "synthetic-private-token"
        with self.assertRaises(D.P.Failed): self.validate_failure(report)

    def test_old_process_failure_cannot_bind_to_current_phase(self):
        report = self.failure(); report["pid"] = 999
        with self.assertRaises(D.P.Failed): self.validate_failure(report)


class DualSixRequestWitnesses(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.database = Path(self.temp.name) / "synthetic.sqlite"
        self.shared = {key: identity(100 + n) for n, key in enumerate(sorted(D.SHARED))}
        scope = D.witness_scope(self.shared)
        self.witnesses = []
        for index, ((kind, entity), device) in enumerate(scope.items(), 1):
            operation = self.shared["count_operation"] if kind == "activity_event" else self.shared["metric_operation"] if kind == "metric_observation" else identity(200 + index)
            self.witnesses.append({"operation_id": operation, "device_id": device, "entity_type": kind, "entity_uuid": entity, "action": "upsert", "replay_status": "already_applied"} |
                                  {field: format(index + 9, "x") * 64 for field in D.WITNESS_HASHES})
        with D.sqlite3.connect(self.database) as db:
            db.executescript("""
                CREATE TABLE users(id INTEGER, public_id TEXT, username TEXT);
                CREATE TABLE client_devices(id INTEGER, public_id TEXT, installation_id TEXT, registered_protocol_version INTEGER, structural_edit_enabled INTEGER, revoked_at TEXT, user_id INTEGER);
                CREATE TABLE user_sync_policies(user_id INTEGER, primary_editor_device_id INTEGER);
                CREATE TABLE sync_operations(operation_id TEXT, device_id INTEGER, entity_type TEXT, entity_uuid TEXT, action TEXT, status TEXT, request_hash TEXT, user_id INTEGER);
                CREATE TABLE activity_events(public_id TEXT, event_type TEXT, value REAL, owner_user_id INTEGER);
                CREATE TABLE metric_observations(public_id TEXT, value REAL, owner_user_id INTEGER);
                CREATE TABLE tracked_metrics(id INTEGER, public_id TEXT, owner_user_id INTEGER);
                CREATE TABLE plan_nodes(id INTEGER, public_id TEXT);
                CREATE TABLE activity_metric_links_v2(public_id TEXT, activity_node_id INTEGER, metric_id INTEGER, prompt_on_complete INTEGER, is_active INTEGER, owner_user_id INTEGER);
            """)
            db.execute("INSERT INTO users VALUES(1,?,?)", (self.shared["account"], "joint-count-command"))
            for role, number in (("a", 101), ("b", 102)):
                db.execute("INSERT INTO client_devices VALUES(?,?,?,5,0,NULL,1)", (number, self.shared["device_" + role], self.shared["installation_" + role]))
            db.execute("INSERT INTO user_sync_policies VALUES(1,102)")
            for index, witness in enumerate(self.witnesses, 1):
                device = 101 if witness["device_id"] == self.shared["device_a"] else 102
                db.execute("INSERT INTO sync_operations VALUES(?,?,?,?,?,'applied',?,1)", (witness["operation_id"], device, witness["entity_type"], witness["entity_uuid"], "upsert", format(index, "x") * 64))
            db.execute("INSERT INTO activity_events VALUES(?,'count_delta',1,1)", (self.shared["count_fact"],))
            db.execute("INSERT INTO metric_observations VALUES(?,23.625,1)", (self.shared["metric_fact"],))
            db.executemany("INSERT INTO tracked_metrics VALUES(?,?,1)", [(301, self.shared["metric"]), (302, self.shared["new_metric"])])
            db.execute("INSERT INTO plan_nodes VALUES(201,?)", (self.shared["habit"],))
            db.execute("INSERT INTO activity_metric_links_v2 VALUES(?,201,301,1,1,1)", (self.shared["link"],))

    def validate(self, witnesses=None):
        return D.terminal_database(self.database, self.shared, self.witnesses if witnesses is None else witnesses)

    def test_all_six_match_without_equating_wire_and_operation_hashes(self):
        result = self.validate()
        self.assertEqual(6, len(result["request_witnesses"]))
        self.assertTrue(all(row["wire_sha256"] != row["server_request_sha256"] for row in result["request_witnesses"]))

    def test_definition_operation_substitution_fails_even_when_six_count_and_facts_match(self):
        definition = next(w for w in self.witnesses if w["entity_type"] == "plan_node")
        with D.sqlite3.connect(self.database) as db:
            db.execute("UPDATE sync_operations SET operation_id=? WHERE operation_id=?", (identity(999), definition["operation_id"]))
        with self.assertRaises(D.P.Failed): self.validate()

    def test_new_primary_metric_cannot_be_attributed_to_old_device(self):
        definition = next(w for w in self.witnesses if w["entity_uuid"] == self.shared["new_metric"])
        with D.sqlite3.connect(self.database) as db:
            db.execute("UPDATE sync_operations SET device_id=101 WHERE operation_id=?", (definition["operation_id"],))
        with self.assertRaises(D.P.Failed): self.validate()

    def test_link_definition_ack_cannot_be_omitted_or_duplicated(self):
        missing = [w for w in self.witnesses if w["entity_type"] != "activity_metric_link"]
        with self.assertRaises(D.P.Failed): self.validate(missing)
        with self.assertRaises(D.P.Failed): self.validate(missing + [missing[0]])


class DualPrivateDeviceBinding(unittest.TestCase):
    """Pure-file admission and fake process contracts; never invoke a real adb/backend."""
    def setUp(self):
        for name in ("run", "Popen"):
            guard = mock.patch.object(D.subprocess, name, side_effect=AssertionError("Pure host test cannot execute processes"))
            guard.start(); self.addCleanup(guard.stop)

    def body(self, serials):
        return {"format": "dual-physical-admission/v1", "serials": serials,
                "apis": dict(D.APIS), "phases": list(D.PHASES)}

    def args(self, root, body):
        path = root / "admission.json"; path.write_text(json.dumps(body))
        return SimpleNamespace(source_root=root, admission=path, admission_sha256=D.sha(path),
                               output=root / "android/app/build/reports/dayforge-dual/synthetic-run")

    def test_explicit_two_role_binding_has_no_default_and_owns_its_copy(self):
        serials = {"B": "another-synthetic-B", "A": "another-synthetic-A"}
        actual = D.admitted_serials(self.body(serials))
        self.assertEqual(["A", "B"], list(actual)); self.assertEqual(serials, actual)
        serials["B"] = "mutated-synthetic-B"
        self.assertEqual("another-synthetic-B", actual["B"])
        with self.assertRaises(D.P.Failed): D.admitted_serials({})

    def test_invalid_binding_rejected_by_pinned_loader_before_output_or_device_work(self):
        invalid = [None, [], "synthetic", {}, {"A": SYNTHETIC_SERIALS["A"]},
                   dict(SYNTHETIC_SERIALS, C="synthetic-third"),
                   {"A": "synthetic-same", "B": "synthetic-same"}]
        for bad in (None, 27, "", " leading", "trailing ", "two words", "line\nbreak", "bad\0value", "-s", "emulator-5554"):
            invalid.append(dict(SYNTHETIC_SERIALS, B=bad))
        for serials in invalid:
            with self.subTest(serials=serials), tempfile.TemporaryDirectory() as directory:
                root = Path(directory); args = self.args(root, self.body(serials))
                with mock.patch.object(D, "Device", side_effect=AssertionError("Invalid admission reached a device")) as device, \
                     mock.patch.object(D, "check_global_arguments", side_effect=AssertionError("Invalid admission escaped validation")) as global_check:
                    with self.assertRaisesRegex(D.P.Failed, "binding"): D.run(args)
                device.assert_not_called(); global_check.assert_not_called(); self.assertFalse(args.output.exists())

    def test_role_api_contract_and_admission_pin_are_not_relaxed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); body = self.body(dict(SYNTHETIC_SERIALS)); body["apis"] = {"A": 27, "B": 35}
            args = self.args(root, body)
            with self.assertRaisesRegex(D.P.Failed, "fixed dual scope"): D.run(args)
            body["apis"] = dict(D.APIS); args = self.args(root, body)
            changed = json.loads(args.admission.read_text()); changed["serials"]["B"] = "unreviewed-synthetic-B"
            args.admission.write_text(json.dumps(changed))
            with self.assertRaisesRegex(D.P.Failed, "Reviewed dual admission changed"): D.run(args)
            self.assertFalse(args.output.exists())

    def ordinary(self, root):
        def record(name, value):
            file = root / name; file.write_text(json.dumps(value))
            return {"path": name, "sha256": D.sha(file)}
        sources = {"synthetic-source.kt": "c" * 64}
        source = record("sources.json", {"files": sources})
        review = record("review.json", {"synthetic": "review"})
        checks = record("checks.json", {"synthetic": "checks"})
        raw = record("raw.json", {"synthetic": "raw evidence"})
        index = record("files.json", {raw["path"]: raw["sha256"]})
        artifacts = {D.TARGET: [{"sha256": "a" * 64}], D.TEST: [{"sha256": "b" * 64}]}
        summary = record("summary.json", {"validated_success": True, "exit_code": 0, "failures": 0, "skipped": 0,
            "run": identity(501), "apk_sha256": {"main": "a" * 64, "test": "b" * 64},
            "cases": [{"class": D.JOINT_CLASS, "method": method, "failures": 0, "errors": 0, "skipped": 0}
                      for method in sorted(D.METHODS)]})
        result = record("ordinary.json", {"format": "ordinary-whole-batch-mapping/v1", "validated_success": True,
            "exit_code": 0, "failures": 0, "cases": 5, "expected_test_methods": 5,
            "source_drift": [], "new_sources": [], "validation_errors": [], "run_id": identity(501),
            "classes": {D.JOINT_CLASS: {"cases": 5, "failures": 0, "skipped": 0}},
            "expected_class_methods": {D.JOINT_CLASS: 5}, "manifest": source["path"], "independent_review": review,
            "independent_checks": checks, "original_summary": summary, "originals_index": index})
        device = record("device.json", {"serial": SYNTHETIC_SERIALS["B"], "api": 27, "run_id": identity(501),
            "summary_sha256": summary["sha256"], "independent_review_sha256": review["sha256"]})
        return result | {"serial": SYNTHETIC_SERIALS["B"], "api": 27, "sources_sha256": source["sha256"],
                         "device_evidence": device}, sources, artifacts

    def test_api27_prerequisite_and_actual_device_proof_bind_exact_admitted_b(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); item, sources, artifacts = self.ordinary(root)
            result = D.validate_ordinary(root, item, sources, artifacts, api27_serial=SYNTHETIC_SERIALS["B"])
            self.assertEqual(identity(501), result["run"])
            for wrong in (SYNTHETIC_SERIALS["A"], "other-synthetic-B"):
                with self.subTest(wrong=wrong):
                    with self.assertRaisesRegex(D.P.Failed, "API27 physical prerequisite missing"):
                        D.validate_ordinary(root, item, sources, artifacts, api27_serial=wrong)
            device = root / item["device_evidence"]["path"]; original = json.loads(device.read_text())
            for field, value in (("serial", SYNTHETIC_SERIALS["A"]), ("serial", "other-synthetic-B"), ("api", 35)):
                device.write_text(json.dumps(original | {field: value})); item["device_evidence"]["sha256"] = D.sha(device)
                with self.subTest(field=field, value=value):
                    with self.assertRaisesRegex(D.P.Failed, "API27 evidence is not bound"):
                        D.validate_ordinary(root, item, sources, artifacts, api27_serial=SYNTHETIC_SERIALS["B"])

    def test_fake_runtime_uses_admitted_serial_in_command_and_failure_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); serials = {"B": "runtime-synthetic-B", "A": "runtime-synthetic-A"}
            body = self.body(serials); args = self.args(root, body)
            fake = D.subprocess.CompletedProcess([], 0, b"offline\n", b"")
            with mock.patch.object(D, "load_admission", return_value=(body, {})), \
                 mock.patch.object(D, "check_global_arguments"), \
                 mock.patch.object(D.subprocess, "run", return_value=fake) as invoke:
                with self.assertRaisesRegex(D.P.Failed, "Physical device not authorized/online"): D.run(args)
            self.assertEqual(1, invoke.call_count)
            self.assertEqual(["-s", serials["A"], "get-state"], invoke.call_args.args[0][1:])
            plan = json.loads((args.output / "plan.json").read_text())
            result = json.loads((args.output / "result.json").read_text())
            command = json.loads((args.output / "A/command-0001.json").read_text())
            self.assertEqual(serials, plan["serials"]); self.assertEqual(serials, result["serials"])
            self.assertEqual(serials["A"], command["argv"][2]); self.assertFalse(result["success"])
            self.assertEqual({"A": "not_acquired", "B": "not_acquired"}, result["cleanup"])

    def test_device_argument_and_pid_record_use_the_same_explicit_binding(self):
        class Process:
            pid = 456
            def poll(self): return 0
            def wait(self, **kwargs): return 0
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); serial = "pid-synthetic-B"
            device = D.Device("synthetic-adb-never-executed", "B", serial, root / "device", 10)
            # Only PID acquisition sees a running process; terminal parsing deliberately fails.
            process = mock.Mock(wraps=Process()); process.poll.side_effect = [None, 0]
            original = D.P.Failed("synthetic-terminal-rejection")
            with mock.patch.object(device, "stopped"), mock.patch.object(device, "image", return_value={}), \
                 mock.patch.object(device, "pids", return_value={123}), \
                 mock.patch.object(device, "call", return_value=D.subprocess.CompletedProcess([], 0, D.TARGET.encode() + b"\0", b"")), \
                 mock.patch.object(device, "collect_failure", return_value=False), \
                 mock.patch.object(D.subprocess, "Popen", return_value=process) as launch, \
                 mock.patch.object(D, "result_bundle", side_effect=original):
                with self.assertRaises(D.P.Failed) as raised:
                    device.phase("B2", identity(1), "a" * 32, "b" * 64, {}, 40001, {})
            self.assertIs(original, raised.exception)
            self.assertEqual(serial, launch.call_args.args[0][2])
            self.assertEqual({"serial": serial, "pid": 123}, json.loads((device.output / "B2/pid.json").read_text()))


class ModernPhysicalAdmission(unittest.TestCase):
    """Private pair selection and lineage are verified before any subprocess can run."""
    def setUp(self):
        for name in ("run", "Popen"):
            patch = mock.patch.object(D.subprocess, name, side_effect=AssertionError("No real process in admission test"))
            patch.start()
            self.addCleanup(patch.stop)

    def test_device_info_reads_serial_and_sdk_independently_of_manufacturer_fields(self):
        self.assertEqual(("test-A", "35"), D.suite_device(b"\x52\x03abc\x12\x0235\x0a\x06test-A\x18\x01"))
        self.assertEqual(("test-B", "36"), D.suite_device(b"\x0a\x06test-B\x12\x0236\x2a\x09arm64-v8a"))

    def test_duplicate_missing_or_truncated_utp_identity_fails(self):
        good = b"\x0a\x03ABC\x12\x0236"
        for invalid in (b"", good[:-1], good + b"\x12\x0235", b"\x0a\x03ABC", b"\x08\x01\x12\x0236", b"\x0a\xff"):
            with self.subTest(invalid=invalid), self.assertRaises(D.P.Failed):
                D.suite_device(invalid)

    def test_optional_new_combination_never_substitutes_for_old_api27_contract(self):
        pairs = (("v1", 36), ("v2", 27), ("v2", 35), ("v2", 36.0), ("v3", 36))
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            for version, api in pairs:
                body = {"format": "dual-physical-admission/" + version, "apis": {"A": 35, "B": api},
                        "serials": SYNTHETIC_SERIALS, "phases": list(D.PHASES)}
                file = root / "admission.json"; file.write_text(json.dumps(body))
                with self.subTest(version=version, api=api), self.assertRaisesRegex(D.P.Failed, "Wrong fixed dual scope"):
                    D.load_admission(file, D.sha(file), root)

    def test_only_approved_host_and_test_can_differ_between_frozen_q04a_and_launch(self):
        running = D.sha(Path(D.__file__))
        old = {D.HOST_SOURCE: "a"*64, "tools/tests/test_dual_physical_protocol.py": "b"*64,
               D.DRIVER_SOURCE: "c"*64, "tools/run_android_tests.py": "d"*64}
        new = old | {D.HOST_SOURCE: running, "tools/tests/test_dual_physical_protocol.py": "e"*64}
        D.require_driver_only_delta(old, new)
        with_finder_metadata = old | {"tools/.DS_Store": "1"*64}
        D.require_driver_only_delta(with_finder_metadata, new)
        with self.assertRaises(D.P.Failed):
            D.require_driver_only_delta(with_finder_metadata, new | {"tools/.DS_Store": "2"*64})
        for edit in (dict(new, extra="f"*64), {k:v for k,v in new.items() if k != D.DRIVER_SOURCE},
                     new | {D.DRIVER_SOURCE: "f"*64}, new | {"tools/run_android_tests.py":"f"*64},
                     new | {D.HOST_SOURCE: "f"*64}, new | {"tools/.DS_Store": "1"*64}):
            with self.subTest(keys=edit.keys()), self.assertRaises(D.P.Failed):
                D.require_driver_only_delta(old, edit)
