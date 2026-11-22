import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from tools.check_android_profile_ledger import Invalid, decode_json, ledger, pinned


class ProfileLedgerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.a = "com.dayforge.FirstTest"
        self.b = "com.dayforge.SecondTest"
        self.ids = [(self.a, "one"), (self.a, "two"), (self.b, "parameter[0]"), (self.b, "parameter[1]")]
        self.source = self.put("source.json", {"files": {"synthetic.kt": "1" * 64}})
        self.config = self.put("profile.json", {"variant": "synthetic-deviceTest", "runner": "SyntheticRunner",
                                                "coverage": True, "apk_sha256": {"app": "2" * 64, "test": "3" * 64}})
        log = self.put("full.log", self.discovery(self.ids))
        capture = self.put("full-capture.json", {"scope": "whole-suite", "source_manifest_sha256": self.source["sha256"],
                           "profile_config_sha256": self.config["sha256"], "discovery_sha256": log["sha256"]})
        self.plan = {"format": 1, "source_manifest": self.source, "profiles": {"ordinary": self.config},
                     "discoveries": [{"profile": "ordinary", "scope": "whole-suite", "log": log, "capture": capture}],
                     "units": [self.unit("first", [self.a]), self.unit("second", [self.b])]}

    def put(self, name, value):
        data = value.encode() if isinstance(value, str) else json.dumps(value).encode()
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return {"path": name, "sha256": hashlib.sha256(data).hexdigest()}

    def plan_pin(self):
        return self.put("plan.json", self.plan)

    def unit(self, name, classes, **changes):
        return {"id": name, "classes": classes, "profile": "ordinary", "kind": "safety", "primary": True,
                "same_process": False, "reason": "Explicit synthetic host fixture; not Android evidence",
                "budgets": {"method_seconds": 150, "batch_seconds": 2700}, **changes}

    @staticmethod
    def discovery(ids):
        lines = []
        for index, (cls, method) in enumerate(ids, 1):
            for code in [1, 0]:
                lines += [f"INSTRUMENTATION_STATUS: {key}={value}" for key, value in
                          [("class", cls), ("test", method), ("current", index), ("numtests", len(ids))]]
                lines += [f"INSTRUMENTATION_STATUS_CODE: {code}"]
        return "\n".join(lines + ["INSTRUMENTATION_CODE: -1", ""])

    def run_record(self, name, ids, statuses=None, **capture_changes):
        statuses = statuses or {}
        counts = {"tests": len(ids), "failures": 0, "errors": 0, "skipped": 0}
        suite = ET.Element("testsuite")
        for cls, method in ids:
            case = ET.SubElement(suite, "testcase", classname=cls, name=method, time="0.01")
            status = statuses.get((cls, method))
            if status:
                counts[{"failure": "failures", "error": "errors", "skipped": "skipped"}[status]] += 1
                ET.SubElement(case, status).text = "Original synthetic refusal; never converted to pass"
        suite.attrib.update({k: str(v) for k, v in counts.items()})
        discovery = self.put(name + "/discovery.log", self.discovery([v for v in self.ids if v[0] in
            next(u["classes"] for u in self.plan["units"] if u["id"] == name)]))
        reports = [self.put(name + "/TEST-original.xml", ET.tostring(suite, encoding="unicode"))]
        run_id = hashlib.sha256(name.encode()).hexdigest()[:32]
        capture = {"run_id": run_id, "unit": name, "discovery": discovery, "reports": reports,
                   "source_manifest_sha256": self.source["sha256"], "profile_config_sha256": self.config["sha256"],
                   "exit_code": 1 if statuses else 0, "cleanup_completed": True, "process_ids": [123], **capture_changes}
        return {"unit": name, "run_id": run_id, "capture": self.put(name + "/capture.json", capture),
                "discovery": discovery, "reports": reports}

    def rebind(self, run):
        # Explicitly bind a synthetic malformed XML to reach its parser, not an earlier hash mismatch.
        value = json.loads((self.root / run["capture"]["path"]).read_text())
        value.update(discovery=run["discovery"], reports=run["reports"])
        run["capture"] = self.put(run["capture"]["path"], value)

    def evaluate(self, runs=None):
        plan = self.plan_pin()
        result = self.put("results.json", {"format": 1, "plan_sha256": plan["sha256"], "runs": runs}) if runs is not None else None
        return ledger(self.root, plan, result)

    def test_missing_runtime_is_not_success_or_current_discovery_guess(self):
        value = self.evaluate()
        self.assertEqual(4, value["declared_discovered_methods"])
        self.assertFalse(value["all_xml_pass"])
        self.assertFalse(value["is_full_gate_success"])
        self.assertFalse(value["is_p1_admission"])
        self.assertTrue(all(u["issues"] == ["NOT_RUN"] for u in value["units"]))

    def test_complete_parameterized_identities_keep_strict_pass_separate_from_acceptance(self):
        value = self.evaluate([self.run_record("first", self.ids[:2]), self.run_record("second", self.ids[2:])])
        self.assertTrue(value["all_xml_pass"])
        self.assertTrue(value["all_declared_primary_identities_reported"])
        self.assertFalse(value["is_p1_admission"])
        self.assertFalse(value["is_full_gate_success"])

    def test_unassigned_class_and_duplicate_primary_class_are_rejected(self):
        self.plan["units"].pop()
        with self.assertRaises(Invalid): self.evaluate()
        self.plan["units"] += [self.unit("second", [self.a, self.b])]
        with self.assertRaises(Invalid): self.evaluate()

    def test_additional_full_class_unit_is_explicit_and_cannot_replace_primary(self):
        self.plan["units"] += [self.unit("joint", [self.a, self.b], primary=False, same_process=True)]
        value = self.evaluate([self.run_record("first", self.ids[:2]), self.run_record("second", self.ids[2:])])
        self.assertTrue(value["all_declared_primary_identities_reported"])
        self.assertFalse(value["all_xml_pass"])
        self.assertEqual(["NOT_RUN"], value["units"][-1]["issues"])

    def test_diagnostic_failure_is_never_waived_or_promoted_to_pass(self):
        self.plan["units"][1]["kind"] = "diagnostic"
        value = self.evaluate([self.run_record("first", self.ids[:2]),
                               self.run_record("second", self.ids[2:], {self.ids[2]: "failure"})])
        self.assertTrue(value["all_declared_primary_identities_reported"])
        self.assertFalse(value["all_xml_pass"])
        self.assertFalse(value["performance_waivers_evaluated"])
        self.assertEqual("FAIL", value["units"][1]["cases"][0]["state"])
        self.assertIsNotNone(value["units"][1]["cases"][0]["failure_sha256"])

    def test_errors_skips_and_incomplete_method_sets_stay_visible(self):
        run = self.run_record("first", self.ids[:1], {self.ids[0]: "error"})
        value = self.evaluate([run, self.run_record("second", self.ids[2:], {self.ids[3]: "skipped"})])
        self.assertIn("INCOMPLETE_METHOD_SET", value["units"][0]["issues"])
        self.assertEqual("NOT_REPORTED", value["units"][0]["cases"][1]["state"])
        self.assertEqual("SKIPPED", value["units"][1]["cases"][1]["state"])
        self.assertFalse(value["all_xml_pass"])

    def test_zero_exit_cannot_hide_failure_and_unclear_cleanup_or_multiple_processes(self):
        self.plan["units"][0]["same_process"] = True
        value = self.evaluate([self.run_record("first", self.ids[:2], {self.ids[0]: "failure"},
                                              exit_code=0, cleanup_completed=False, process_ids=[123, 124])])
        self.assertFalse(value["all_xml_pass"])
        self.assertIn("CLEANUP_NOT_PROVEN", value["units"][0]["issues"])
        self.assertIn("SAME_PROCESS_CONTRACT_NOT_MET", value["units"][0]["issues"])

    def test_run_cannot_use_another_source_or_profile(self):
        for key in ["source_manifest_sha256", "profile_config_sha256"]:
            with self.subTest(key=key), self.assertRaises(Invalid):
                self.evaluate([self.run_record("first", self.ids[:2], **{key: "0" * 64})])

    def test_full_discovery_capture_must_bind_real_log_source_and_scope(self):
        capture = self.plan["discoveries"][0]["capture"]
        data = json.loads((self.root / capture["path"]).read_text())
        data["scope"] = "selected-classes"
        self.plan["discoveries"][0]["capture"] = self.put(capture["path"], data)
        with self.assertRaises(Invalid): self.evaluate()

    def test_filtered_or_other_run_discovery_is_rejected(self):
        run = self.run_record("first", self.ids[:2])
        run["discovery"] = self.put("first/discovery.log", self.discovery(self.ids[:1]))
        self.rebind(run)
        with self.assertRaises(Invalid): self.evaluate([run])

    def test_xml_counter_lie_or_unpinned_report_cannot_make_a_success(self):
        run = self.run_record("first", self.ids[:2], {self.ids[0]: "failure"})
        p = run["reports"][0]["path"]
        s = (self.root / p).read_text().replace('failures="1"', 'failures="0"')
        run["reports"][0] = self.put(p, s)
        self.rebind(run)
        with self.assertRaises(Invalid): self.evaluate([run])
        run = self.run_record("first", self.ids[:2])
        self.put("first/TEST-unpinned.xml", (self.root / p).read_text())
        with self.assertRaises(Invalid): self.evaluate([run])

    def test_changed_artifact_and_duplicate_json_keys_are_rejected(self):
        pin = self.plan_pin()
        (self.root / pin["path"]).write_text("{}")
        with self.assertRaises(Invalid): ledger(self.root, pin)
        with self.assertRaises(Invalid): decode_json('{"format":1,"format":2}')

    def test_path_escape_and_symlink_are_rejected(self):
        with self.assertRaises(Invalid): pinned(self.root, {"path": "../outside", "sha256": "0" * 64})
        target = self.root / "original.txt"
        target.write_text("original")
        (self.root / "alias.txt").symlink_to(target)
        with self.assertRaises(Invalid): pinned(self.root, {"path": "alias.txt", "sha256": hashlib.sha256(b"original").hexdigest()})

    def test_undiscovered_profile_or_automatic_name_classification_is_not_allowed(self):
        self.plan["units"][0]["kind"] = "cost-name-means-waived"
        with self.assertRaises(Invalid): self.evaluate()
        self.plan["units"][0]["kind"] = "safety"
        self.plan["profiles"]["worker"] = self.config
        with self.assertRaises(Invalid): self.evaluate()

    def test_duplicate_run_attempts_cannot_be_combined(self):
        run = self.run_record("first", self.ids[:2])
        with self.assertRaises(Invalid): self.evaluate([run, run])


    def test_report_cannot_contain_methods_from_another_unit(self):
        run = self.run_record("first", self.ids)
        with self.assertRaises(Invalid): self.evaluate([run])

    def test_negative_or_nonfinite_duration_never_counts_as_completed(self):
        for value in ["-1", "nan", "inf"]:
            with self.subTest(value=value):
                run = self.run_record("first", self.ids[:2])
                path = run["reports"][0]["path"]
                xml = (self.root / path).read_text().replace('time="0.01"', f'time="{value}"', 1)
                run["reports"][0] = self.put(path, xml)
                self.rebind(run)
                with self.assertRaises(Invalid): self.evaluate([run])

    def test_result_collection_cannot_reclassify_with_a_different_plan_digest(self):
        plan = self.plan_pin()
        results = self.put("results.json", {"format": 1, "plan_sha256": "0" * 64, "runs": []})
        with self.assertRaises(Invalid): ledger(self.root, plan, results)


    def test_capture_must_name_the_same_run_and_unit(self):
        for field in ["run_id", "unit"]:
            with self.subTest(field=field), self.assertRaises(Invalid):
                self.evaluate([self.run_record("first", self.ids[:2], **{field: "another-run-or-unit"})])

    def test_same_source_old_pass_xml_cannot_replace_current_failed_capture(self):
        run = self.run_record("first", self.ids[:2], {self.ids[0]: "failure"})
        suite = ET.parse(self.root / run["reports"][0]["path"]).getroot()
        suite.set("failures", "0")
        for case in suite.findall("testcase"):
            for failure in case.findall("failure"):
                case.remove(failure)
        run["reports"] = [self.put("old/TEST-old.xml", ET.tostring(suite, encoding="unicode"))]
        with self.assertRaises(Invalid): self.evaluate([run])

    def test_self_consistent_declared_scope_never_proves_actual_full_discovery(self):
        self.ids = self.ids[:2]
        self.plan["units"] = self.plan["units"][:1]
        item = self.plan["discoveries"][0]
        item["log"] = self.put("narrow.log", self.discovery(self.ids))
        item["capture"] = self.put("narrow-capture.json", {"scope": "whole-suite",
            "source_manifest_sha256": self.source["sha256"], "profile_config_sha256": self.config["sha256"],
            "discovery_sha256": item["log"]["sha256"]})
        value = self.evaluate([self.run_record("first", self.ids)])
        self.assertTrue(value["all_declared_primary_identities_reported"])
        self.assertFalse(value["discovery_scope_independently_verified"])
        self.assertFalse(value["is_full_gate_success"])
        self.assertFalse(value["is_p1_admission"])


if __name__ == "__main__":
    unittest.main()
