#!/usr/bin/env python3
"""Fixed nine-phase/two-physical-installation acceptance. Never emits JUnit/P1/full PASS.

No build, arbitrary device selection, retry, database upload, credential import or general
scenario DSL. Both isolated packages are installed once; later phases preserve all data.
"""
from __future__ import annotations

import argparse
import base64
import contextlib
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import sqlite3
import subprocess
import time
import uuid

from tools import run_command_process_gates as P
from tools.android_joint_backend import JOINT_CLASS, backend_for_cases
from tools.run_android_tests import check_global_arguments, verification_lock

TARGET, TEST = P.TARGET, P.TEST
COMPONENT = TEST + "/com.dayforge.DualPhysicalDriver"
APIS = {"A": 35, "B": 27}
PHASES = ("A1", "B1", "B2", "B3", "A2", "B4", "A3", "B5", "A4")
METHODS = frozenset({
    "realMixedCoreFactsKeepGoalMetricLinksAndBothCountModes",
    "realOnceUndoAndOfflineChallengeRestartRetainOriginalFacts",
    "realDurableCountCommandRequiredMetricAckReplayAndColdDelivery",
    "realForwardTimerContinuesAcrossActualSync", "realCountdownTimerContinuesAcrossActualSync",
})
SHARED = frozenset({"account", "server", "epoch", "installation_a", "device_a", "installation_b", "device_b",
                    "habit", "metric", "link", "count_fact", "metric_fact", "count_operation", "metric_operation", "new_metric"})
ADDED = {"A1": {"account", "server", "epoch", "installation_a", "device_a", "habit", "metric", "link"},
         "B1": {"installation_b", "device_b"}, "B2": {"count_fact", "count_operation"},
         "B3": {"metric_fact", "metric_operation"}, "B5": {"new_metric"}}
DRIVER_SOURCE = "android/app/src/androidTest/java/com/dayforge/DualPhysicalDriver.kt"
SCENARIO_SOURCE = "android/app/src/androidTest/java/com/dayforge/data/repository/DualPhysicalScenario.kt"
HOST_SOURCE = "tools/run_dual_physical_acceptance.py"
TOTAL_SECONDS, PHASE_HOST_SECONDS, FINAL_CLEANUP_SECONDS = 4500, 180, 120
require = P.require
sha = P.sha
save = P.save


def pinned(root, item):
    path = P.admitted_file(root, item["path"])
    return path, P.pinned_json(path, item["sha256"], "Pinned dual acceptance input changed")


def admitted_serials(body):
    """Explicit local admission only; no public device values or discovery fallback."""
    serials = body.get("serials")
    require(isinstance(serials, dict) and serials.keys() == APIS.keys(), "Wrong physical serial binding roles")
    require(all(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,255}", value)
                and not value.startswith("emulator-") for value in serials.values()), "Invalid physical serial binding")
    require(len(set(serials.values())) == 2, "Distinct physical serial bindings required")
    return {role: serials[role] for role in APIS}


def validate_ordinary(root, item, sources, artifacts, *, api27_serial):
    """Existing whole-batch mapping, independent device attribution pinned in admission.

    This is not a P1 loader call or compiler attestation. The external reviewed admission
    owns original discovery/XML/UTP/coverage/cleanup provenance and actual API27 serial.
    """
    require(item.get("serial") == api27_serial and item.get("api") == 27, "API27 physical prerequisite missing")
    result_path, result = pinned(root, item)
    require(result.get("format") == "ordinary-whole-batch-mapping/v1", "Whole ordinary batch mapping required")
    require(result.get("validated_success") is True and result.get("exit_code") == 0
            and result.get("source_drift") == [] and result.get("new_sources") == []
            and result.get("validation_errors") == [], "Ordinary batch is not an intact success")
    require(all(type(result.get(k)) is int for k in ("cases", "expected_test_methods", "failures", "exit_code")), "Non-integer ordinary counters")
    classes, expected = result["classes"], result["expected_class_methods"]
    require(classes.keys() == expected.keys() and expected.get(JOINT_CLASS) == len(METHODS), "Complete Joint5 omitted")
    for name, row in classes.items():
        require(type(expected[name]) is int and expected[name] > 0 and type(row["cases"]) is int
                and row["cases"] == expected[name] and type(row["failures"]) is int and type(row["skipped"]) is int and row["failures"] == row["skipped"] == 0, "Incomplete ordinary class")
    require(type(result["cases"]) is int and result["cases"] == result["expected_test_methods"] == sum(expected.values())
            and result["failures"] == 0, "Whole ordinary batch counters differ")
    source_file = P.admitted_file(root, str((result_path.parent / result["manifest"]).relative_to(root)))
    require(P.pinned_json(source_file, item["sources_sha256"], "Ordinary source pin changed")["files"] == sources,
            "API27 prerequisite uses different complete source")
    for key in ("independent_review", "independent_checks"):
        record = result[key]
        file = P.admitted_file(root, str((result_path.parent / record["path"]).relative_to(root)))
        require(sha(file) == record["sha256"], "Independent ordinary review changed")
    original = result["original_summary"]
    original_path = P.admitted_file(root, str((result_path.parent / original["path"]).relative_to(root)))
    summary = P.pinned_json(original_path, original["sha256"], "Original ordinary summary changed")
    require(summary.get("validated_success") is True and summary.get("exit_code") == 0 and summary.get("failures") == 0
            and summary.get("skipped") == 0, "Original whole ordinary batch failed")
    require(summary.get("run") == result["run_id"] and len(summary["cases"]) == result["cases"], "Original run/whole case count differs")
    identities = {(row["class"], row["method"]) for row in summary["cases"]}
    require(len(identities) == len(summary["cases"]) and all(
        all(type(row[k]) is int and row[k] == 0 for k in ("failures", "errors", "skipped")) for row in summary["cases"]), "Original batch has duplicate/failed/skipped cases")
    exact = {row["method"] for row in summary["cases"] if row["class"] == JOINT_CLASS}
    require(exact == METHODS, "API27 Joint identities differ")
    index = result["originals_index"]
    index_path = P.admitted_file(root, str((result_path.parent / index["path"]).relative_to(root)))
    leaves = P.pinned_json(index_path, index["sha256"], "Original complete outcome index changed")
    require(isinstance(leaves, dict) and leaves, "Original raw outcome omitted")
    for name, digest in leaves.items():
        original_leaf = P.admitted_file(root, str((index_path.parent / name).relative_to(root)))
        require(sha(original_leaf) == digest, "Original raw evidence changed")
    require(set(summary["apk_sha256"].values()) == {entry["sha256"] for rows in artifacts.values() for entry in rows},
            "Ordinary APK differs from dual APK")
    # Actual device evidence is required and externally reviewed, not inferred from a filename.
    device_path, device = pinned(root, item["device_evidence"])
    require(device == {"serial": api27_serial, "api": 27, "run_id": result["run_id"],
                       "summary_sha256": original["sha256"], "independent_review_sha256": result["independent_review"]["sha256"]},
            "API27 evidence is not bound to this reviewed whole batch")
    return {"run": result["run_id"], "device_evidence": sha(device_path), "result": sha(result_path)}


def suite_device(blob):
    """Read the singleton serial and SDK strings in the retained UTP AndroidDeviceInfo."""
    from io import BytesIO
    stream = BytesIO(blob)

    def varint():
        value = 0
        for shift in range(0, 70, 7):
            byte = stream.read(1)
            require(bool(byte), "Truncated UTP device identity")
            value |= (byte[0] & 127) << shift
            if byte[0] < 128:
                return value
        raise P.Failed("Invalid UTP device identity")

    fields = {}
    while stream.tell() < len(blob):
        tag = varint()
        number, wire = tag >> 3, tag & 7
        require(number > 0 and wire in (0, 1, 2, 5), "Invalid UTP field")
        if wire == 0:
            varint()
            require(number not in (1, 2), "Invalid UTP identity type")
            continue
        size = varint() if wire == 2 else (8 if wire == 1 else 4)
        require(size <= len(blob) - stream.tell(), "Truncated UTP device field")
        data = stream.read(size)
        if number in (1, 2):
            require(wire == 2 and number not in fields, "Duplicate or invalid UTP identity")
            fields[number] = data.decode("ascii")
    require(fields.keys() == {1, 2}, "Missing UTP identity")
    return fields[1], fields[2]


def validate_current_joint(root, role, item, admission):
    from tools.check_android_results import read_cases
    from tools.run_android_tests import discover

    serial, api = admission["serials"][role], admission["apis"][role]
    expected = {(JOINT_CLASS, method) for method in METHODS}
    pre = pinned(root, item["admission"])[1]
    terminal = pinned(root, item["terminal"])[1]
    review = pinned(root, item["review"])[1]
    run = review["run"]
    require(isinstance(run, str) and re.fullmatch(r"[0-9a-f]{32}", run), "Invalid Q04A run id")
    run_root = root / "android/app/build/reports/dayforge-device" / run
    names = terminal.get("raw_runs")
    require(isinstance(names, list) and len(names) == 1 and Path(names[0]).name == run,
            "Q04A terminal run differs")
    apk = {package: rows[0]["sha256"] for package, rows in admission["artifacts"].items()}
    require(pre["apk_sha256"] == apk and pre["profile"] == review["profile"] == admission["ordinary_profile"]["sha256"]
            and pre["source"] == review["source"] == admission["q04a_sources"]["sha256"]
            and pre["backend_source"] == admission["backend_sources"]["sha256"], "Q04A input provenance differs")
    require(pre["device"]["api"] == terminal["device_post"]["api"] == str(api)
            and terminal["device_post"]["model"] == pre["device"]["model"], "Q04A device/API changed")
    require(type(terminal["gate_exit"]) is int and terminal["gate_exit"] == 0 and review["clean"] is True
            and terminal["device_post"]["packages"] == [] and terminal["device_post"]["reverse"] == []
            and terminal["device_post"]["pids"] == {TARGET: "", TEST: ""}, "Q04A cleanup/exit incomplete")
    require(review["status"] == "TARGETED_PASS" and review["cases"] == len(METHODS)
            and pre["classes"] == review["classes"] == [JOINT_CLASS] and pre["case_timeout_ms"] == 150000
            and sorted(pre["expected_methods"]) == sorted(review["scope"]) == sorted(METHODS), "Wrong Q04A scope")
    index_path, leaves = pinned(root, item["originals_index"])
    require(isinstance(leaves, dict) and {"targeted.json", "discovery.log", "targeted.log",
            "joint-backend/complete.json", "joint-backend/joint.sqlite", "final-stop-testbed.log",
            "final-packages-tests.log", "final-packages-testbed.log"}.issubset(leaves), "Incomplete Q04A archive")
    for name, expected_hash in leaves.items():
        require(isinstance(name, str) and not Path(name).is_absolute() and ".." not in Path(name).parts,
                "Invalid Q04A archive leaf")
        original = P.admitted_file(root, str((index_path.parent / name).relative_to(root)))
        require(sha(original) == expected_hash, "Q04A archive changed")
    def original(name):
        require(name in leaves, "Unindexed Q04A outcome")
        return index_path.parent / name

    native = json.loads(original("targeted.json").read_text())
    require(native["status"] == "targeted_complete" and native["scope"] == "targeted"
            and native["selected_classes"] == [JOINT_CLASS] and native["cases"] == len(expected)
            and native["apk_sha256"] == [apk[TARGET], apk[TEST]] and
            sorted(native["discovered_cases"]) == sorted(map(list, expected)), "Wrong Q04A execution")
    require(native["discovery_sha256"] == sha(original("discovery.log"))
            and discover(original("discovery.log").read_text()) == expected, "Q04A discovery differs")
    raw_target = P.admitted_file(root, str((run_root / "targeted.json").relative_to(root)))
    require(sha(raw_target) == leaves["targeted.json"], "Actual UTP run differs from archived Q04A")
    reports = native["test_reports_sha256"]
    require(reports == review["xml"] and len(reports) == 1, "Wrong Q04A report count")
    xml, xml_hash = next(iter(reports.items()))
    require(Path(xml).parent == Path("targeted-results") and re.fullmatch(r"TEST-.+-_app-\.xml", Path(xml).name)
            and sha(original(xml)) == xml_hash and sha(run_root / xml) == xml_hash, "Actual Q04A XML differs")
    identities, errors = read_cases(run_root / "targeted-results")
    require(identities == expected and not errors, "Actual Q04A XML has failures or missing cases")
    coverage = [digest for name, digest in leaves.items() if name.startswith("targeted-coverage/") and name.endswith(".ec")]
    require(len(coverage) == 1 and native["coverage_inputs_sha256"] == review["coverage"] == coverage,
            "Q04A coverage differs")
    peer = json.loads(original("joint-backend/complete.json").read_text())
    require(peer.get("valid") is True and peer == native["joint_backend"] == review["joint_backend"],
            "Q04A peer terminal differs")
    require(original("final-packages-tests.log").read_bytes().strip() == b"" and
            original("final-packages-testbed.log").read_bytes().strip() == b"", "Q04A packages survived cleanup")
    folder = Path(xml).stem.removeprefix("TEST-").removesuffix("-_app-")
    utp = run_root / "targeted-results" / folder
    info = P.admitted_file(root, str((utp / "device-info.pb").relative_to(root)))
    suite = P.admitted_file(root, str((utp / "test-result.textproto").relative_to(root)))
    require(sha(info) == item["device_info_sha256"] and sha(suite) == item["suite_sha256"] and
            suite_device(info.read_bytes()) == (serial, str(api)), "Actual Q04A UTP device differs")
    text = suite.read_text()
    metadata = re.findall(r"(?ms)^test_suite_meta_data \{\n(.*?)^\}$", text)
    require(len(metadata) == 1 and re.findall(r'^    id: "([^"]+)"$', metadata[0], re.M) == [serial] and
            re.findall(r"^test_status: (\w+)$", text, re.M) == ["PASSED"] and
            re.findall(r"^  test_status: (\w+)$", text, re.M) == ["PASSED"] * len(expected) and
            sorted(re.findall(r'^    test_method: "([^"]+)"$', text, re.M)) == sorted(METHODS)
            and re.findall(r'^    test_package: "([^"]+)"$', text, re.M) == [JOINT_CLASS.rsplit(".", 1)[0]] * len(expected)
            and re.findall(r'^    test_class: "([^"]+)"$', text, re.M) == [JOINT_CLASS.rsplit(".", 1)[1]] * len(expected),
            "Actual Q04A UTP suite differs")
    return {"run": run, "api": api, "serial": serial, "index": sha(index_path), "device_info": sha(info), "suite": sha(suite)}


def require_driver_only_delta(old, files):
    # The historical Q04A capture included Finder metadata. A clean Git
    # checkout omits it. All Android, backend and verification inputs remain bound.
    omitted = old.keys() - files.keys()
    require(omitted <= {"tools/.DS_Store"} and not (files.keys() - old.keys())
            and {name for name in files if old[name] != files[name]}.issubset(
        {HOST_SOURCE, "tools/tests/test_dual_physical_protocol.py"}), "Changes outside dual acceptance host/test")
    require(files[HOST_SOURCE] == sha(Path(__file__)), "Running host differs from new source freeze")


def current_joints(root, body, files):
    require_driver_only_delta(pinned(root, body["q04a_sources"])[1]["files"], files)
    profile = pinned(root, body["ordinary_profile"])[1]
    require(profile["name"] == "ordinary-covered-deviceTest-Hilt" and profile["variant"] == "deviceTest"
            and profile["runner"] == TEST + "/com.dayforge.HiltTestRunner" and profile["coverage"] is True
            and profile["apk_sha256"] == {p: rows[0]["sha256"] for p, rows in body["artifacts"].items()},
            "Wrong Q04A build profile")
    joints = body["joint_prerequisites"]
    require(type(joints) is dict and joints.keys() == APIS.keys(), "Both Q04A prerequisites required")
    out = {role: validate_current_joint(root, role, joints[role], body) for role in APIS}
    require(out["A"]["run"] != out["B"]["run"], "Distinct Q04A runs required")
    return out


def load_admission(path, digest, root):
    body = P.pinned_json(path, digest, "Reviewed dual admission changed")
    legacy = body.get("format") == "dual-physical-admission/v1" and body.get("apis") == APIS
    modern = body.get("format") == "dual-physical-admission/v2" and body.get("apis") == {"A": 35, "B": 36}
    require((legacy or modern) and all(type(n) is int for n in body["apis"].values())
            and body.get("phases") == list(PHASES), "Wrong fixed dual scope")
    serials = admitted_serials(body)
    require(body.get("profile") == "ordinary-covered-widget-isolated" and body.get("packages") == [TARGET, TEST], "Wrong profile/packages")
    require(body.get("host_sha256") == sha(Path(__file__)), "Host implementation changed")
    require(body.get("total_seconds") == TOTAL_SECONDS and body.get("device_phase_ms") == 150000
            and body.get("host_phase_seconds") == PHASE_HOST_SECONDS, "Phase budget changed")
    _, manifest = pinned(root, body["sources"])
    files = manifest["files"]
    require(isinstance(files, dict) and files, "Empty source admission")
    required = {str(p.relative_to(root)) for folder in ("android/app/src", "android/app/schemas")
                for p in (root / folder).rglob("*") if p.is_file() and p.suffix not in (".pyc",) and "__pycache__" not in p.parts and p.name != ".DS_Store"}
    required |= P.ADMISSION_BUILD_INPUTS | {DRIVER_SOURCE, SCENARIO_SOURCE, HOST_SOURCE, "tools/android_joint_backend.py",
                "tools/run_command_process_gates.py", "tools/run_android_tests.py"}
    require(required.issubset(files), "Full app/tool source omitted")
    for name, digest in files.items():
        require(re.fullmatch(r"[a-f0-9]{64}", digest) and sha(P.admitted_file(root, name)) == digest, "Frozen source changed")
    # Backend source is separately frozen; never rewrite the ordinary Android manifest
    # or claim its result included unrelated backend source files.
    _, backend = pinned(root, body["backend_sources"])
    backend_files = backend["files"]
    backend_required = {str(p.relative_to(root)) for folder in ("backend/src", "backend/tests")
                        for p in (root / folder).rglob("*") if p.is_file() and p.suffix != ".pyc"
                        and "__pycache__" not in p.parts and p.name != ".DS_Store"}
    backend_required |= {"backend/uv.lock", "backend/pyproject.toml"}
    require(backend_required.issubset(backend_files), "Owned backend/fixture source omitted")
    for name, digest in backend_files.items():
        require(re.fullmatch(r"[a-f0-9]{64}", digest) and sha(P.admitted_file(root, name)) == digest, "Backend source changed")
    artifacts = body["artifacts"]
    require(artifacts.keys() == {TARGET, TEST}, "Both APK packages required")
    for rows in artifacts.values():
        require(isinstance(rows, list) and len(rows) == 1, "Only current single-APK ordinary build supported")
        for row in rows:
            require(row["path"].endswith(".apk") and sha(P.admitted_file(root, row["path"])) == row["sha256"], "Local APK changed")
    ordinary = (validate_ordinary(root, body["api27_joint"], files, artifacts, api27_serial=serials["B"])
                if legacy else current_joints(root, body, files))
    return body, ordinary


def result_bundle(text):
    result, codes = {}, []
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_RESULT: "):
            key, sep, value = line[len("INSTRUMENTATION_RESULT: "):].partition("=")
            require(bool(sep) and key not in result, "Duplicate/malformed instrumentation result")
            result[key] = value
        elif line.startswith("INSTRUMENTATION_CODE: "):
            codes.append(int(line.split(": ", 1)[1]))
    require(codes == [-1] and result.get("df_dual_result") == "PASS", "Phase did not complete successfully")
    return result


WITNESS_HASHES = frozenset({"source_sha256", "wire_sha256", "origin_row_sha256", "transmission_row_sha256", "result_sha256"})
WITNESS_FIELDS = WITNESS_HASHES | {"operation_id", "device_id", "entity_type", "entity_uuid", "action", "replay_status"}


def witness_scope(shared):
    return {
        ("plan_node", shared["habit"]): shared["device_a"],
        ("metric", shared["metric"]): shared["device_a"],
        ("activity_metric_link", shared["link"]): shared["device_a"],
        ("activity_event", shared["count_fact"]): shared["device_b"],
        ("metric_observation", shared["metric_fact"]): shared["device_b"],
        ("metric", shared["new_metric"]): shared["device_b"],
    }


def validate_witness_shape(witness):
    require(isinstance(witness, dict) and witness.keys() == WITNESS_FIELDS, "Incomplete actual-ACK request witness")
    require(witness["action"] == "upsert" and witness["replay_status"] == "already_applied", "No exact real server replay")
    for field in ("operation_id", "device_id", "entity_uuid"):
        value = witness[field]
        require(isinstance(value, str) and str(uuid.UUID(value)) == value, "Malformed request identity")
    for field in WITNESS_HASHES:
        require(isinstance(witness[field], str) and re.fullmatch(r"[a-f0-9]{64}", witness[field]), "Missing retained client hash")


def validate_phase_witnesses(witnesses, phase, shared, ends):
    require(isinstance(witnesses, list), "Missing phase request witnesses")
    if phase == "A1": expected = {("plan_node", shared["habit"]), ("metric", shared["metric"]), ("activity_metric_link", shared["link"])}
    elif phase == "B4": expected = {("activity_event", shared["count_fact"]), ("metric_observation", shared["metric_fact"])}
    elif phase == "B5": expected = {("metric", shared["new_metric"])}
    else: expected = set()
    require(len(witnesses) == len(expected), "Phase ACK witness count differs")
    for witness in witnesses:
        validate_witness_shape(witness)
        require(witness["device_id"] == shared["device_" + phase[0].lower()], "Witness belongs to the other installation")
    require({(w["entity_type"], w["entity_uuid"]) for w in witnesses} == expected, "Actual ACK entity set differs")
    operations = [w["operation_id"] for w in witnesses]
    require(len(set(operations)) == len(operations), "Repeated request witness")
    native = [op for end in ends if end["kind"] == "ACK" for op in end["accepted"]]
    require(set(native) == set(operations) and len(native) == len(operations), "Witness lacks its actual local ACK END")
    if phase == "B4":
        require({w["operation_id"]: w["entity_uuid"] for w in witnesses} == {
            shared["count_operation"]: shared["count_fact"], shared["metric_operation"]: shared["metric_fact"]}, "Original B request IDs changed")


def validate_report(report, bundle, phase, run, nonce, previous, prior_shared, pid):
    require(report.get("format") == "dual-physical-phase/v1" and report.get("status") == "PASS"
            and report.get("owners_closed") is True, "Missing known-close phase proof")
    expected = {"run": run, "nonce": nonce, "phase": phase, "previous": previous}
    for key, value in expected.items():
        require(report.get(key) == value and bundle.get("df_dual_" + key) == value, "Phase identity chain differs")
    require(bundle.get("df_dual_driver") == "dual-physical/v1" and report.get("role") == phase[0]
            and report.get("pid") == pid and bundle.get("df_dual_pid") == str(pid), "Wrong driver/physical PID")
    require(report.get("widget_scope") == "isolated", "Undeclared background scope")
    shared = report["shared"]
    require(shared.keys() == prior_shared.keys() | ADDED.get(phase, set()) and set(shared) <= SHARED, "Unexpected shared state transition")
    require(all(shared[k] == v for k, v in prior_shared.items()), "Shared identity was rewritten")
    for value in shared.values(): require(isinstance(value, str) and str(uuid.UUID(value)) == value, "Malformed public identity")
    role = phase[0].lower()
    require(report["installation"] == shared["installation_" + role] and report["device"] == shared["device_" + role], "Local registration differs")
    if "device_b" in shared:
        require(shared["device_a"] != shared["device_b"] and shared["installation_a"] != shared["installation_b"], "Not two installations")
    checks = report.get("checks", {})
    require(checks.keys() == {"fresh_authority", "same_installation", "original_domain", "actual_end", "exact_effects", "known_close"}
            and all(value is True for value in checks.values()), "Incomplete phase assertions")
    if phase == "B2":
        require(report.get("offline_transport") in {"java.net.ConnectException", "java.net.SocketTimeoutException", "java.net.NoRouteToHostException"}, "Offline phase lacks actual transport failure")
    ends = report["native"]
    require(isinstance(ends, list) and len(ends) <= 128 and all(type(e["sequence"]) is int and e["end"] >= e["begin"] for e in ends), "Malformed actual-END observations")
    if phase in ("B2", "B3", "B4"):
        expected_counts = {"B2": (1, 1, 0, 0), "B3": (1, 0, 1, 1), "B4": (0, 0, 0, 0)}[phase]
        require(tuple(sum(e["kind"] == k for e in ends) for k in ("T0", "COUNT_T1", "METRIC_T1", "T2")) == expected_counts, "Original command boundary count differs")
        actual = [item for e in ends if e["kind"] == "ACK" for item in e["accepted"]]
        expected_ops = {shared["count_operation"], shared["metric_operation"]} if phase == "B4" else set()
        require(set(actual) == expected_ops and len(actual) == len(expected_ops), "Unexpected ACK or repeated acceptance")
    validate_phase_witnesses(report.get("request_witnesses"), phase, shared, ends)
    return shared


def validate_failure_report(report, *, phase, run, nonce, previous, pid):
    require(report.keys() == {"format", "run", "phase", "nonce", "pid", "previous", "failure"}
            and report["format"] == "dual-physical-failure/v1", "Unexpected failure report fields")
    require((report["run"], report["phase"], report["nonce"], report["previous"], report["pid"]) ==
            (run, phase, nonce, previous, pid), "Failure belongs to another phase/process")
    total = 0
    def symbol(value, limit):
        return isinstance(value, str) and len(value) <= limit and re.fullmatch(r"[A-Za-z0-9_.$<>/-]+", value)
    def node(value, depth=0):
        nonlocal total
        total += 1
        require(total <= 650 and depth <= 8 and isinstance(value, dict) and symbol(value.get("type"), 256), "Malformed bounded failure node")
        if "repeated" in value or "truncated" in value:
            marker = "repeated" if "repeated" in value else "truncated"
            require(value.keys() == {"type", marker} and value[marker] is True, "Malformed failure truncation")
            return
        require(value.keys() == {"type", "frame_count", "frames", "frames_truncated", "cause", "suppressed_count", "suppressed", "suppressed_truncated"},
                "Failure diagnostic must never carry message/body/credentials")
        frames, suppressed = value["frames"], value["suppressed"]
        require(isinstance(frames, list) and len(frames) <= 48 and type(value["frame_count"]) is int and value["frame_count"] >= len(frames)
                and value["frames_truncated"] is (value["frame_count"] > 48), "Invalid frame count")
        for frame in frames:
            require(frame.keys() == {"class", "method", "file", "line"} and symbol(frame["class"], 256) and symbol(frame["method"], 128)
                    and (frame["file"] is None or symbol(frame["file"], 96)) and type(frame["line"]) is int, "Invalid safe stack frame")
        require(isinstance(suppressed, list) and len(suppressed) <= 24 and type(value["suppressed_count"]) is int
                and value["suppressed_count"] >= len(suppressed)
                and value["suppressed_truncated"] is (value["suppressed_count"] > 24), "Invalid suppressed count")
        if value["cause"] is not None: node(value["cause"], depth + 1)
        for child in suppressed: node(child, depth + 1)
    node(report["failure"])
    return report["failure"]["type"]


class Device(P.Host):
    def __init__(self, adb, role, serial, output, total):
        require(role in APIS, "Unknown physical device role")
        super().__init__(adb, serial, output, total)
        self.role, self.serial, self.counter = role, serial, 0
        self.owned = False
        self.output.mkdir()
        self.seen_pids = set()
        self.route = None
        self.pending_failure = None

    def call(self, *args, check=True):
        self.counter += 1
        file = self.output / f"command-{self.counter:04d}"
        timeout = self.remaining(120)
        save(file.with_suffix(".json"), {"argv": self.adb + list(args), "timeout": timeout})
        try:
            result = subprocess.run(self.adb + list(args), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, timeout=timeout)
        except (subprocess.TimeoutExpired, OSError) as failure:
            # TimeoutExpired may contain the only available original bytes. Preserve them
            # before rethrowing THIS exception; no retry, replacement or deadline increase.
            raw_out, raw_err = getattr(failure, "stdout", None), getattr(failure, "stderr", None)
            def raw(value):
                return value.encode("utf-8") if isinstance(value, str) else value if value is not None else b""
            try:
                file.with_suffix(".stdout").write_bytes(raw(raw_out))
                file.with_suffix(".stderr").write_bytes(raw(raw_err))
                save(file.with_suffix(".result.json"), {"exit_code": None, "timed_out": isinstance(failure, subprocess.TimeoutExpired),
                     "error_type": type(failure).__name__, "stdout_available": raw_out is not None,
                     "stderr_available": raw_err is not None, "timeout_seconds": timeout})
            except BaseException as diagnostic:
                failure.add_note("DIAGNOSTIC_PERSISTENCE_FAILED:" + type(diagnostic).__name__)
                raise failure from diagnostic
            raise
        file.with_suffix(".stdout").write_bytes(result.stdout); file.with_suffix(".stderr").write_bytes(result.stderr)
        save(file.with_suffix(".result.json"), {"exit_code": result.returncode, "timed_out": False, "error_type": None,
             "stdout_available": True, "stderr_available": True, "timeout_seconds": timeout})
        require(not check or result.returncode == 0, "Fixed adb command failed; original output retained")
        return result

    def present(self, package):
        require(package in (TARGET, TEST), "Foreign package")
        rows = self.text("shell", "pm", "list", "packages", package).splitlines()
        require(all(row.startswith("package:") for row in rows), "Cannot inspect package presence")
        return "package:" + package in rows

    def stopped(self):
        self.call("shell", "am", "force-stop", TARGET)
        require(not self.pids(), "Isolated process did not stop")

    def image(self, artifacts):
        expected = {package: [row["sha256"] for row in rows] for package, rows in artifacts.items()}
        actual = self.installed_image(); P.require_admitted_image(actual, expected)
        return actual

    def collect_failure(self, folder, phase, run, nonce, previous, pid):
        # Read-only forensic collection, never retries the operation. A second collection
        # after known package stop uses the already reserved final-cleanup budget only.
        number = len(list(folder.glob("failure-collection-*.json"))) + 1
        status = {"attempt": number, "phase": phase, "pid": pid, "valid": False}
        try:
            raw = self.call("shell", "run-as", TARGET, "cat", f"files/dayforge-dual-physical/{run}/{self.role}/{phase}-failure.json", check=False)
            status["read_exit_code"] = raw.returncode
            if raw.returncode == 0:
                require(0 < len(raw.stdout) <= 1_048_576, "Oversized/empty failure evidence")
                (folder / f"failure-original-{number}.json").write_bytes(raw.stdout)
                body = json.loads(raw.stdout)
                status["primary_type"] = validate_failure_report(body, phase=phase, run=run, nonce=nonce, previous=previous, pid=pid)
                status["sha256"] = hashlib.sha256(raw.stdout).hexdigest()
                lines = (folder / "instrumentation.log").read_text(errors="replace").splitlines()
                hashes = [line.split("=", 1)[1] for line in lines if line.startswith("INSTRUMENTATION_RESULT: df_dual_failure_sha256=")]
                require(len(hashes) <= 1 and (not hashes or hashes == [status["sha256"]]), "Driver/failure artifact mismatch")
                status["driver_hash_bound"] = bool(hashes)
                status["valid"] = True
            else:
                status["missing_or_read_failed"] = True
            self.call("logcat", "-d", f"--pid={pid}", "-v", "threadtime")
        except BaseException as diagnostic:
            status["collection_error_type"] = type(diagnostic).__name__
        finally:
            save(folder / f"failure-collection-{number}.json", status)
        if status["valid"]: self.pending_failure = None
        return status["valid"]

    def phase(self, phase, run, nonce, previous, shared, port, artifacts):
        self.stage_deadline = time.monotonic() + PHASE_HOST_SECONDS
        self.stopped()  # Previous phase has already proved known close; never clears disk/keys.
        before = self.image(artifacts)
        folder = self.output / phase; folder.mkdir()
        argv = self.adb + ["shell", "am", "instrument", "-w", "-r", "-e", "timeout_msec", "150000",
            "-e", "df_dual_run", run, "-e", "df_dual_nonce", nonce, "-e", "df_dual_phase", phase,
            "-e", "df_dual_previous", previous, "-e", "dayforgeJointPort", str(port),
            "-e", "df_dual_shared", base64.b64encode(json.dumps(shared, sort_keys=True).encode()).decode(), COMPONENT]
        save(folder / "argv.json", {"argv": argv, "device_budget_ms": 150000, "host_seconds": PHASE_HOST_SECONDS})
        process = None
        pid = None
        primary = None
        completed = False
        try:
            with (folder / "instrumentation.log").open("wb") as log:
                process = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
                pid = None
                while pid is None:
                    require(process.poll() is None, "Instrumentation ended before physical PID proof")
                    ids = self.pids()
                    if ids:
                        require(len(ids) == 1, "More than one isolated app PID")
                        pid = next(iter(ids))
                        require(pid not in self.seen_pids, "Phase did not use a fresh isolated process")
                        require(self.call("shell", "run-as", TARGET, "cat", f"/proc/{pid}/cmdline").stdout.rstrip(b"\0") == TARGET.encode(), "PID is not isolated target")
                    else:
                        self.pause(0.25)
                self.seen_pids.add(pid); save(folder / "pid.json", {"serial": self.serial, "pid": pid})
                require(process.wait(timeout=self.remaining(PHASE_HOST_SECONDS)) == 0, "Instrumentation host failed")
            bundle = result_bundle((folder / "instrumentation.log").read_text(errors="replace"))
            raw = self.call("shell", "run-as", TARGET, "cat", f"files/dayforge-dual-physical/{run}/{self.role}/{phase}.json").stdout
            require(0 < len(raw) <= 1_048_576 and hashlib.sha256(raw).hexdigest() == bundle.get("df_dual_report_sha256"), "Phase report missing/different")
            (folder / "report.json").write_bytes(raw)
            report = json.loads(raw); shared = validate_report(report, bundle, phase, run, nonce, previous, shared, pid)
            require(self.image(artifacts) == before, "Installed APK changed during phase")
            # Only this isolated PID's logs, never whole-device personal logs.
            self.call("logcat", "-d", f"--pid={pid}", "-v", "threadtime")
            completed = True
            return report, hashlib.sha256(raw).hexdigest(), shared
        except BaseException as failure:
            primary = failure
            raise
        finally:
            cleanup_failure = None
            try:
                if process is not None and process.poll() is None:
                    os.killpg(process.pid, signal.SIGTERM)
                    try: process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL); process.wait(timeout=5)
                if not completed and pid is not None:
                    self.pending_failure = (folder, phase, run, nonce, previous, pid)
                    self.collect_failure(*self.pending_failure)
            except BaseException as failure:
                cleanup_failure = failure
                save(folder / "host-failure-collection-error.json", {"type": type(failure).__name__})
            finally:
                self.stage_deadline = None
            if cleanup_failure is not None:
                if primary is None: raise cleanup_failure
                primary.add_note("PHASE_FORENSIC_FAILURE:" + type(cleanup_failure).__name__)


class BackendOwner:
    """Narrow adapter for the unchanged owned real backend context manager."""
    def __init__(self, root, output, devices):
        self.repo, self.run, self.devices, self.evidence = root, output, devices, {}

    def adb_command(self, name, *args):
        require(name in ("joint-reverse", "joint-reverse-remove"), "Unexpected backend device command")
        for device in self.devices.values():
            if name == "joint-reverse":
                require(args[:2] == ("reverse", "--no-rebind") and args[2] == args[3] and device.route is None, "Wrong reverse acquisition")
                device.call(*args); device.route = args[2]
            elif device.route is not None:
                require(args == ("reverse", "--remove", device.route), "Foreign reverse cleanup")
                device.call(*args); device.route = None


def terminal_database(path, shared, witnesses):
    """Read only AFTER the sole real server stopped and inspect_database completed."""
    require(isinstance(witnesses, list) and len(witnesses) == 6, "All six actual ACK witnesses required")
    scope = witness_scope(shared)
    require(len(scope) == 6, "Six business identities must be distinct")
    for witness in witnesses:
        validate_witness_shape(witness)
        require(scope.get((witness["entity_type"], witness["entity_uuid"])) == witness["device_id"], "Wrong definition/fact source device")
    require({(w["entity_type"], w["entity_uuid"]) for w in witnesses} == scope.keys(), "Missing actual definition/fact ACK")
    by_operation = {w["operation_id"]: w for w in witnesses}
    require(len(by_operation) == 6, "Duplicate operation witness")
    matched = []
    with contextlib.closing(sqlite3.connect(f"file:{path}?mode=ro", uri=True)) as db:
        user = db.execute("SELECT id,public_id FROM users WHERE username=?", ("joint-count-command",)).fetchone()
        require(user is not None and user[1] == shared["account"], "Wrong terminal owner")
        devices = db.execute("SELECT id,public_id,installation_id,registered_protocol_version,structural_edit_enabled,revoked_at FROM client_devices WHERE user_id=?", (user[0],)).fetchall()
        require(len(devices) == 2 and {r[1] for r in devices} == {shared["device_a"], shared["device_b"]}, "Not exactly two real registrations")
        by_id = {r[1]: r for r in devices}
        for role in ("a", "b"):
            row = by_id[shared["device_" + role]]
            require(row[2] == shared["installation_" + role] and row[3] == 5 and not row[4] and row[5] is None, "Registration/policy differs")
        primary = db.execute("SELECT primary_editor_device_id FROM user_sync_policies WHERE user_id=?", (user[0],)).fetchone()
        require(primary == (by_id[shared["device_b"]][0],), "Explicit primary handoff not durable")
        operations = db.execute("SELECT operation_id,device_id,entity_type,entity_uuid,action,status,request_hash FROM sync_operations WHERE user_id=? ORDER BY operation_id", (user[0],)).fetchall()
        require(len(operations) == 6 and {r[0] for r in operations} == by_operation.keys(), "Actual server operation set differs from all six ACKs")
        for row in operations:
            witness = by_operation[row[0]]
            require(row[1:5] == (by_id[witness["device_id"]][0], witness["entity_type"], witness["entity_uuid"], witness["action"])
                    and row[5] == "applied" and re.fullmatch(r"[a-f0-9]{64}", row[6]), "Original request/device/entity/action differs")
            # Different contracts: whole original wire versus server canonical per-operation
            # hash. Exact frozen replay on the REAL backend proved already_applied for all6;
            # never assert these digest strings are equal or invent a client canonicalizer.
            matched.append({"operation_id": row[0], "device_id": witness["device_id"], "entity_type": row[2],
                "entity_uuid": row[3], "action": row[4], "wire_sha256": witness["wire_sha256"], "server_request_sha256": row[6],
                "verification": "actual_frozen_wire_replay_already_applied"})
        events = db.execute("SELECT public_id,event_type,value FROM activity_events WHERE owner_user_id=?", (user[0],)).fetchall()
        values = db.execute("SELECT public_id,value FROM metric_observations WHERE owner_user_id=?", (user[0],)).fetchall()
        require(events == [(shared["count_fact"], "count_delta", 1)] and values == [(shared["metric_fact"], 23.625)], "Terminal facts not exact once")
        metrics = db.execute("SELECT public_id FROM tracked_metrics WHERE owner_user_id=?", (user[0],)).fetchall()
        require({r[0] for r in metrics} == {shared["metric"], shared["new_metric"]}, "Primary metric definitions differ")
        links = db.execute("SELECT l.public_id,n.public_id,m.public_id,l.prompt_on_complete,l.is_active FROM activity_metric_links_v2 l "
            "JOIN plan_nodes n ON n.id=l.activity_node_id JOIN tracked_metrics m ON m.id=l.metric_id WHERE l.owner_user_id=?", (user[0],)).fetchall()
        require(links == [(shared["link"], shared["habit"], shared["metric"], 1, 1)], "Acknowledged link definition differs")
    return {"two_devices": True, "exact_operations": 6, "request_witnesses": matched, "count_facts": 1, "metric_facts": 1, "primary": shared["device_b"], "database_sha256": sha(path)}


def run(args):
    root = args.source_root.resolve()
    admission, prerequisite = load_admission(args.admission.resolve(), args.admission_sha256, root)
    serials = admitted_serials(admission)
    check_global_arguments(root)
    require(args.output.resolve().is_relative_to(root / "android/app/build/reports/dayforge-dual"), "Output must use the isolated generated report root")
    require(not args.output.exists() and not args.output.is_symlink(), "Output must be fresh")
    require(all(not parent.is_symlink() for parent in args.output.parents), "Linked report parent")
    args.output.mkdir(parents=True)
    output = args.output.resolve()
    save(output / "admission-copy.json", admission)
    save(output / "plan.json", {"phases": PHASES, "serials": serials, "device_ms": 150000, "host_phase_seconds": PHASE_HOST_SECONDS, "whole_seconds": TOTAL_SECONDS, ("api27_joint" if admission["apis"] == APIS else "joint_prerequisites"): prerequisite, "apis": admission["apis"]})
    adb = str(Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT", "")) / "platform-tools/adb") if os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") else "adb"
    whole_deadline = time.monotonic() + TOTAL_SECONDS
    devices = {role: Device(adb, role, serial, output / role, TOTAL_SECONDS - FINAL_CLEANUP_SECONDS) for role, serial in serials.items()}
    for device in devices.values(): device.deadline = whole_deadline - FINAL_CLEANUP_SECONDS
    run_id, nonce = str(uuid.uuid4()), uuid.uuid4().hex
    previous = {"A": "none", "B": "none"}; shared = {}; reports = []; success = False; errors = []
    owner = BackendOwner(root, output, devices)
    primary_error = None
    try:
        for role, device in devices.items():
            require(device.text("get-state") == "device", "Physical device not authorized/online")
            for prop in ("ro.kernel.qemu", "ro.boot.qemu"): require(device.text("shell", "getprop", prop) != "1", "Emulator forbidden")
            require(device.text("shell", "getprop", "ro.build.version.sdk") == str(admission["apis"][role]), "Admitted API changed")
            day = device.text("shell", "date", "+%F")
            clock = device.text("shell", "date", "+%H:%M:%S")
            h, m, s = map(int, clock.split(":")); require(86400 - (h * 3600 + m * 60 + s) > TOTAL_SECONDS, "Insufficient business-day margin")
            save(device.output / "device.json", {"serial": device.serial, "api": admission["apis"][role], "date": day,
                 "zone": device.text("shell", "getprop", "persist.sys.timezone"), "clock": clock})
            device.owned = True
            device.stopped()
            for package in (TEST, TARGET):
                if device.present(package): device.call("uninstall", package)
            for package in (TARGET, TEST): device.call("install", str(P.admitted_file(root, admission["artifacts"][package][0]["path"])))
            device.image(admission["artifacts"])
        states = [json.loads((d.output / "device.json").read_text()) for d in devices.values()]
        require(states[0]["date"] == states[1]["date"] and states[0]["zone"] == states[1]["zone"] and states[0]["zone"], "Same-day/timezone fixture requires matching actual device settings")
        with backend_for_cases(owner, {(JOINT_CLASS, "dualPhysicalOwnedBackend")}) as arguments:
            require(len(arguments) == 1 and arguments[0].startswith("-Pandroid.testInstrumentationRunnerArguments.dayforgeJointPort="), "Wrong backend route argument")
            port = int(arguments[0].split("=", 1)[1]); require(1024 <= port <= 65535, "Bad owned endpoint")
            for phase in PHASES:
                load_admission(args.admission.resolve(), args.admission_sha256, root)
                if phase == "B2":
                    devices["B"].stopped()  # B1 completed known-close; no old TCP socket can survive.
                    require(devices["B"].route == f"tcp:{port}", "Missing owned B route")
                    devices["B"].call("reverse", "--remove", f"tcp:{port}"); devices["B"].route = None
                elif phase == "B4":
                    require(devices["B"].route is None, "B was not offline")
                    devices["B"].call("reverse", "--no-rebind", f"tcp:{port}", f"tcp:{port}"); devices["B"].route = f"tcp:{port}"
                device = devices[phase[0]]
                report, digest, shared = device.phase(phase, run_id, nonce, previous[phase[0]], shared, port, admission["artifacts"])
                if reports:
                    require(report["date"] == reports[0]["date"] and report["zone"] == reports[0]["zone"], "Business day changed")
                previous[phase[0]] = digest; reports.append(report)
                save(output / "progress.json", {"completed": [r["phase"] for r in reports], "report_hashes": previous})
                print(f"Dual physical phase {phase} completed with known close", flush=True)
        require(owner.evidence.get("joint_backend", {}).get("valid") is True, "Owned backend terminal inspection missing")
        proof = terminal_database(output / "joint-backend/joint.sqlite", shared,
            [witness for report in reports for witness in report["request_witnesses"]])
        load_admission(args.admission.resolve(), args.admission_sha256, root)
        for device in devices.values(): device.image(admission["artifacts"])
        save(output / "server-proof.json", proof)
        success = True
    except BaseException as error:
        primary_error = error
        errors.append(type(error).__name__ + ": " + str(error))
        raise
    finally:
        cleanup = {}
        # Reserved inside the original whole4500, never extra device/phase success time.
        for device in devices.values():
            device.stage_deadline = None
            device.deadline = whole_deadline
        for role, device in devices.items():
            if not device.owned:
                cleanup[role] = "not_acquired"
                continue
            try:
                device.stopped()  # On failure stop only the isolated app, retain DB/DS/keys.
                if device.pending_failure is not None:
                    device.collect_failure(*device.pending_failure)
                if device.route is not None:
                    device.call("reverse", "--remove", device.route); device.route = None
                if success:
                    for package in (TEST, TARGET): device.call("uninstall", package)
                    for package in (TEST, TARGET): require(not device.present(package), "Final isolated package still present")
                cleanup[role] = "removed_after_success" if success else "stopped_state_retained_after_failure"
            except BaseException as failure:
                success = False; errors.append("cleanup " + role + ": " + type(failure).__name__); cleanup[role] = "FAILED"
        save(output / "result.json", {"format": "dual-physical-result/v1", "success": success, "run": run_id, "serials": serials, "apis": admission["apis"],
             "completed_phases": [r["phase"] for r in reports], "errors": errors, "cleanup": cleanup,
             "scope": "two physical testbed installations, owned USB-loopback FastAPI; not NAS/WiFi, P1/P2, full JUnit or frame/performance acceptance"})
        leaves = {str(p.relative_to(output)): sha(p) for p in sorted(output.rglob("*")) if p.is_file() and p.name != "files.json"}
        save(output / "files.json", leaves)
        if not success and primary_error is None:
            require(False, "Dual physical acceptance cleanup incomplete; preserve all original evidence")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", required=True, type=Path)
    parser.add_argument("--admission", required=True, type=Path)
    parser.add_argument("--admission-sha256", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    with verification_lock(): run(args)


if __name__ == "__main__":
    main()
