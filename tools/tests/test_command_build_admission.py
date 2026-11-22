"""Host-only negative admission cases. No adb, APK build or Android execution occurs."""
import importlib.util
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location("admission_protocol", Path(__file__).parents[1] / "run_command_process_gates.py")
P = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(P)


class BuildAdmission(unittest.TestCase):
    def setUp(self):
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        sources = {P.DRIVER_SOURCE: "fixed synthetic Kotlin driver", "android/app/src/main/Fact.kt": "fixed fact source"}
        sources.update({name: "fixed build input" for name in P.ADMISSION_BUILD_INPUTS})
        self.files = {}
        for name, value in sources.items():
            self.write(name, value.encode())
            self.files[name] = P.sha(self.root / name)
        self.json("evidence/source.json", {"files": self.files})
        self.json("evidence/ordinary.json", {"validated_success": True, "exit_code": 0, "failures": 0,
            "cases": 2, "expected_test_methods": 2, "source_drift": [], "new_sources": [],
            "classes": {"fixed.SafetyTest": {"cases": 2, "failures": 0, "skipped": 0}},
            "expected_class_methods": {"fixed.SafetyTest": 2},
            "validation_errors": [], "manifest": "source.json"})
        self.artifacts = {}
        for package, filename in [(P.TARGET, "app.apk"), (P.TEST, "test.apk")]:
            self.write(filename, ("synthetic bytes " + package).encode())
            self.artifacts[package] = [{"path": filename, "sha256": P.sha(self.root / filename)}]
        self.body = {"format": "count-command-build-admission/v1", "serial": "fixed-physical",
            "packages": [P.TARGET, P.TEST], "host_script_sha256": P.sha(Path(P.__file__)),
            "sources": {"path": "evidence/source.json", "sha256": P.sha(self.root / "evidence/source.json")},
            "driver_source": P.DRIVER_SOURCE, "driver_source_sha256": self.files[P.DRIVER_SOURCE],
            "ordinary_results": [{"path": "evidence/ordinary.json", "sha256": P.sha(self.root / "evidence/ordinary.json"),
                "sources_sha256": P.sha(self.root / "evidence/source.json")}], "artifacts": self.artifacts}
        self.record = self.root / "admission.json"
        self.pin()

    def write(self, name, value):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(value)

    def json(self, name, value):
        self.write(name, json.dumps(value, sort_keys=True).encode())

    def pin(self):
        self.json("admission.json", self.body)
        self.digest = P.sha(self.record)

    def load(self, **changed):
        args = dict(record=self.record, pinned_sha=self.digest, root=self.root,
            serial="fixed-physical", driver_sha=self.files[P.DRIVER_SOURCE])
        args.update(changed)
        with mock.patch.object(P.subprocess, "run", side_effect=AssertionError("Admission must not execute adb")):
            return P.load_admission(**args)

    def admit_metric_sources(self):
        for name in P.METRIC_DRIVER_SOURCES:
            self.write(name, ("synthetic test-only source: " + name).encode())
            self.files[name] = P.sha(self.root / name)
        self.json("evidence/source.json", {"files": self.files})
        digest = P.sha(self.root / "evidence/source.json")
        self.body["sources"]["sha256"] = digest
        self.body["ordinary_results"][0]["sources_sha256"] = digest
        self.body["host_groups"] = {name: list(cases) for name, cases in P.GROUPS.items()}
        self.pin()

    def test_same_reviewed_sources_safety_and_exact_installed_pair(self):
        expected = self.load()
        actual = {package: [{"path": "/data/app/fixed/base.apk", "sha256": rows[0]["sha256"]}]
                  for package, rows in self.artifacts.items()}
        P.require_admitted_image(actual, expected)

    def test_changed_record_cannot_reuse_old_external_pin(self):
        self.body["serial"] = "different-physical"
        self.json("admission.json", self.body)
        with self.assertRaises(P.Failed): self.load()

    def test_fixed_driver_digest_is_checked_not_just_well_formed(self):
        with self.assertRaises(P.Failed): self.load(driver_sha="f" * 64)

    def test_different_host_implementation_is_rejected(self):
        self.body["host_script_sha256"] = "f" * 64
        self.pin()
        with self.assertRaises(P.Failed): self.load()

    def test_changed_main_source_and_added_unadmitted_source_are_rejected(self):
        path = self.root / "android/app/src/main/Fact.kt"
        before = path.read_bytes()
        path.write_bytes(before + b" different behavior")
        with self.assertRaises(P.Failed): self.load()
        path.write_bytes(before)
        self.write("android/app/src/main/Additional.kt", b"new source")
        with self.assertRaises(P.Failed): self.load()

    def test_changed_local_apk_is_rejected_before_any_device_action(self):
        self.write("app.apk", b"different compiled implementation")
        with self.assertRaises(P.Failed): self.load()

    def test_failed_ordinary_gate_is_not_build_admission(self):
        p = self.root / "evidence/ordinary.json"
        body = json.loads(p.read_text()); body["failures"] = 1
        self.json("evidence/ordinary.json", body)
        self.body["ordinary_results"][0]["sha256"] = P.sha(p)
        self.pin()
        with self.assertRaises(P.Failed): self.load()

    def test_boolean_case_count_cannot_masquerade_as_one_pass(self):
        p = self.root / "evidence/ordinary.json"
        body = json.loads(p.read_text()); body["cases"] = True; body["expected_test_methods"] = 1
        self.json("evidence/ordinary.json", body)
        self.body["ordinary_results"][0]["sha256"] = P.sha(p)
        self.pin()
        with self.assertRaises(P.Failed): self.load()

    def test_ordinary_pass_for_different_source_is_rejected(self):
        old = dict(self.files); old[P.DRIVER_SOURCE] = "f" * 64
        self.json("evidence/old-source.json", {"files": old})
        p = self.root / "evidence/ordinary.json"
        body = json.loads(p.read_text()); body["manifest"] = "old-source.json"
        self.json("evidence/ordinary.json", body)
        row = self.body["ordinary_results"][0]
        row["sha256"] = P.sha(p); row["sources_sha256"] = P.sha(self.root / "evidence/old-source.json")
        self.pin()
        with self.assertRaises(P.Failed): self.load()

    def test_contradictory_success_cannot_hide_skipped_or_missing_class(self):
        p = self.root / "evidence/ordinary.json"
        original = json.loads(p.read_text())
        for change in ["skip", "missing", "total"]:
            body = json.loads(json.dumps(original))
            if change == "skip": body["classes"]["fixed.SafetyTest"]["skipped"] = 1
            elif change == "missing": body["classes"] = {}
            else: body["cases"] = body["expected_test_methods"] = 3
            self.json("evidence/ordinary.json", body)
            self.body["ordinary_results"][0]["sha256"] = P.sha(p)
            self.pin()
            with self.subTest(change=change), self.assertRaises(P.Failed): self.load()

    def test_parsed_admission_is_the_exact_bytes_that_passed_its_pin(self):
        record = mock.Mock(wraps=self.record)
        record.read_text.side_effect = AssertionError("A second read must not replace the pinned document")
        expected = self.load(record=record)
        self.assertEqual(set(expected), {P.TARGET, P.TEST})
        record.read_bytes.assert_called_once()
        record.read_text.assert_not_called()

    def test_matching_digest_outside_workspace_is_not_an_admitted_file(self):
        self.body["sources"]["path"] = str(self.root / "evidence/source.json")
        self.pin()
        with self.assertRaises(P.Failed): self.load()

    def test_stable_wrong_installed_build_or_extra_split_is_rejected(self):
        expected = self.load()
        actual = {package: [{"path": "/data/app/fixed/base.apk", "sha256": rows[0]["sha256"]}]
                  for package, rows in self.artifacts.items()}
        actual[P.TARGET][0]["sha256"] = "f" * 64
        with self.assertRaises(P.Failed): P.require_admitted_image(actual, expected)
        actual[P.TARGET][0]["sha256"] = expected[P.TARGET][0]
        actual[P.TARGET].append(dict(actual[P.TARGET][0]))
        with self.assertRaises(P.Failed): P.require_admitted_image(actual, expected)


    def test_old_count_admission_does_not_admit_metric_by_cli_only(self):
        self.load(group="count")
        with self.assertRaises(P.Failed): self.load(group="metric")
        self.body["host_groups"] = {"count": list(P.GROUPS["count"])}
        self.pin()
        with self.assertRaises(P.Failed): self.load(group="metric")

    def test_exact_metric_three_cases_require_all_bound_sources_and_keep_count(self):
        self.admit_metric_sources()
        self.assertEqual(self.load(group="metric"), self.load(group="count"))
        self.assertEqual(P.GROUPS["metric"], ("post_metric_t0_end", "post_metric_t1_end_pre_return", "post_t2_end_pre_return"))
        self.assertEqual(P.ORDINARY_TEST_TIMEOUT_MS, 150000)
        self.assertEqual((P.PER_PHASE_HOST_SECONDS, P.EXPECTED_ABORT_SECONDS), (180, 30))

    def test_incomplete_duplicate_or_unknown_host_group_cannot_be_admitted(self):
        self.admit_metric_sources()
        damaged = [None, {}, [], {"metric": []}, {"metric": list(P.GROUPS["metric"])[:-1]},
                   {"metric": list(reversed(P.GROUPS["metric"]))},
                   {"metric": [P.GROUPS["metric"][0]] * 3}, {"other": list(P.GROUPS["metric"])}]
        for value in damaged:
            self.body["host_groups"] = value; self.pin()
            with self.subTest(value=value), self.assertRaises(P.Failed): self.load(group="metric")

    def test_declared_metric_group_cannot_omit_its_actual_source_files(self):
        self.body["host_groups"] = {"metric": list(P.GROUPS["metric"])}
        self.pin()
        with self.assertRaises(P.Failed): self.load(group="metric")

    def test_group_declaration_cannot_change_after_external_pin(self):
        self.admit_metric_sources()
        self.body["host_groups"]["metric"] = list(P.GROUPS["metric"])[:-1]
        self.json("admission.json", self.body)
        with self.assertRaises(P.Failed): self.load(group="metric")

    def test_metric_group_keeps_exact_source_and_apk_rejection_before_device(self):
        self.admit_metric_sources()
        path = self.root / sorted(P.METRIC_DRIVER_SOURCES)[0]
        before = path.read_bytes(); path.write_bytes(before + b" changed implementation")
        with self.assertRaises(P.Failed): self.load(group="metric")
        path.write_bytes(before)
        self.write("test.apk", b"changed Android test implementation")
        with self.assertRaises(P.Failed): self.load(group="metric")


if __name__ == "__main__":
    unittest.main()
