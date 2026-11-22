#!/usr/bin/env python3
"""Candidate host protocol. No build/install/uninstall/clear operation exists here.

Requires the complete, separately reviewed v20 driver and safety gates before execution.
Only the already authorized testbed packages can be addressed. An intentional phase-1
process death is NOT a JUnit pass; phase-2 real assertions plus host proof decide a case.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid

TARGET = "com.dayforge.testbed"
TEST = TARGET + ".test"
COMPONENT = TEST + "/com.dayforge.CountCommandProcessDriver"
DRIVER = "count-command-process/v1"
GROUPS = {
    "count": ("pre_t0_end", "post_t0_end_pre_return", "pre_t1_end", "post_t1_end_pre_return"),
    "metric": ("post_metric_t0_end", "post_metric_t1_end_pre_return", "post_t2_end_pre_return"),
    "replica-final": ("file_repair", "final_ds_forward"),
    "retained": ("post_generation_persist_pre_return", "post_retained_enroll_end_pre_return",
                 "post_independent_t0_end_pre_return", "post_independent_t1_end_pre_return", "post_independent_t2_end_pre_return"),
}
EXPECTED = {
    "pre_t0_end": "not_admitted_unchanged",
    "post_t0_end_pre_return": "same_count_applied_once",
    "pre_t1_end": "same_count_applied_once",
    "post_t1_end_pre_return": "count_already_committed",
    "post_metric_t0_end": "same_metric_group_applied_once",
    "post_metric_t1_end_pre_return": "metric_group_already_committed",
    "post_t2_end_pre_return": "delivery_committed_results_retained",
    "post_generation_persist_pre_return": "same_generation_retained_no_command",
    "post_retained_enroll_end_pre_return": "retained_enrollment_committed_once",
    "post_independent_t0_end_pre_return": "same_independent_value_applied_once",
    "post_independent_t1_end_pre_return": "independent_value_already_committed",
    "post_independent_t2_end_pre_return": "independent_delivery_already_committed",
}
CHECKS = frozenset({"same_original_identity", "full_native_terminal", "original_business_oracle",
    "no_duplicate_effects", "fresh_authority", "known_reference_and_pending_directory", "required_child_state"})
RETAINED_CHECKS = frozenset({"same_generation_and_authority", "old_native_partition", "actual_persistent_stage",
    "command_identity_or_absence", "single_effects", "fresh_authority", "terminal_retry_read_only"})
RETAINED_SOURCES = frozenset({
    "android/app/src/androidTest/java/com/dayforge/data/repository/CountCommandRetainedHostScenario.kt",
    "android/app/src/androidTest/java/com/dayforge/data/local/LegacySessionGenerationFixture.kt",
    "android/app/src/androidTest/java/com/dayforge/data/local/LegacySessionNativeInitializationProbe.kt",
    "android/app/src/androidTest/java/com/dayforge/data/local/DatabaseEpochTestRooms.kt",
    "android/app/src/androidTest/java/com/dayforge/di/DatabaseEpochTestModule.kt",
})
RETAINED_ORDINARY_CLASSES = frozenset({
    'com.dayforge.data.local.LegacySessionGenerationObservationTest',
    'com.dayforge.data.local.LegacySessionGenerationEntrypointTest',
    'com.dayforge.data.local.RetainedSessionCountJourneyTest',
    'com.dayforge.data.local.RetainedLocalObligationConsumerTest',
    'com.dayforge.data.local.LegacySessionGenerationMigrationTest',
    'com.dayforge.data.local.LegacySessionGenerationPersistenceTest',
    'com.dayforge.data.local.LegacySessionGenerationHostTest',
    'com.dayforge.data.local.CountCommandMigrationTest',
    'com.dayforge.data.repository.CountCommandRetainedEnrollmentTest',
    'com.dayforge.data.repository.CountCommandRetainedPartitionTest',
    'com.dayforge.data.repository.CountCommandRetainedRootTest',
    'com.dayforge.data.repository.CountCommandRetainedNativeBoundaryTest',
    'com.dayforge.data.repository.CountCommandRetainedRawBoundaryTest',
    'com.dayforge.data.repository.IndependentMetricNativeBoundaryTest',
    'com.dayforge.data.repository.IndependentMetricCompatibilityTest',
    'com.dayforge.data.repository.IndependentMetricCommandTest',
    'com.dayforge.data.repository.IndependentMetricRecoveryContractTest',
})
PER_PHASE_HOST_SECONDS = 180
EXPECTED_ABORT_SECONDS = 30
ORDINARY_TEST_TIMEOUT_MS = 150000  # Same existing Android runner budget; never relaxed here.

class Failed(RuntimeError):
    pass

def require(condition, message):
    if not condition:
        raise Failed(message)

def save(path: Path, body):
    raw = (json.dumps(body, indent=2, sort_keys=True) + "\n").encode()
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("xb") as target:
        target.write(raw)
        target.flush()
        os.fsync(target.fileno())
    temporary.replace(path)

def sha(path: Path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

DRIVER_SOURCE = "android/app/src/androidTest/java/com/dayforge/CountCommandProcessDriver.kt"
METRIC_DRIVER_SOURCES = frozenset({
    "android/app/src/androidTest/java/com/dayforge/data/repository/CountCommandHostScenario.kt",
    "android/app/src/androidTest/java/com/dayforge/data/repository/CountCommandProcessNativeSpy.kt",
    "android/app/src/androidTest/java/com/dayforge/data/repository/CountCommandProcessProtocol.kt",
})
ADMISSION_BUILD_INPUTS = {"android/app/build.gradle.kts", "android/build.gradle.kts",
                          "android/gradle.properties", "android/gradle/libs.versions.toml"}

def admitted_file(root: Path, name):
    require(isinstance(name, str) and name and not Path(name).is_absolute(), "Admission path must be relative")
    path = (root / name).resolve()
    require(path.is_relative_to(root) and path.is_file(), "Admission file escapes or is missing")
    return path

def pinned_json(path: Path, expected_sha: str, message: str):
    raw = path.read_bytes()
    require(hashlib.sha256(raw).hexdigest() == expected_sha, message)
    return json.loads(raw)

def load_admission(record: Path, pinned_sha: str, root: Path, serial: str, driver_sha: str, group: str = "count"):
    """Check a separately reviewed build record; never infer source provenance from stable APKs.

    The parent build admission owns source-to-artifact provenance. These exact hashes and
    ordinary result checks enforce that record, not cryptographic compiler attestation.
    """
    require(re.fullmatch(r"[0-9a-f]{64}", pinned_sha), "Pinned admission digest required")
    body = pinned_json(record, pinned_sha, "Admission record differs from independent admission")
    admission_format = "replica-final-build-admission/v1" if group == "replica-final" else "count-command-build-admission/v1"
    require(body.get("format") == admission_format, "Unsupported build admission")
    require(body.get("serial") == serial and body.get("packages") == [TARGET, TEST], "Wrong admitted device or packages")
    require(body.get("host_script_sha256") == sha(Path(__file__)), "Host script is not the admitted implementation")
    require(group in GROUPS, "Unknown complete host group")
    # An old reviewed count-only record never grants the newly implemented metric phases.
    # This declaration is covered by the independent external admission digest, not CLI trust.
    declared = body.get("host_groups", {"count": list(GROUPS["count"])})
    require(isinstance(declared, dict) and declared and all(
        name in GROUPS and type(cases) is list and cases == list(GROUPS[name])
        for name, cases in declared.items()), "Malformed or incomplete admitted host groups")
    require(group in declared, "Requested host group was not independently admitted")
    root = root.resolve()
    source = body["sources"]
    source_file = admitted_file(root, source["path"])
    files = pinned_json(source_file, source["sha256"], "Source manifest changed")["files"]
    require(isinstance(files, dict) and files, "Empty source admission")
    if group == "metric":
        require(METRIC_DRIVER_SOURCES.issubset(files), "Metric implementation sources omitted from admission")
    if group == "retained":
        require((RETAINED_SOURCES | METRIC_DRIVER_SOURCES).issubset(files), "Retained implementation sources omitted from admission")
    if group == "replica-final":
        require(REPLICA_SOURCES.issubset(files), "Replica implementation sources omitted from admission")
    current = {str(p.relative_to(root)) for folder in ("android/app/src", "android/app/schemas")
               for p in (root / folder).rglob("*") if p.is_file() and p.name != ".DS_Store"}
    require((current | ADMISSION_BUILD_INPUTS).issubset(files), "App source, schema or build input omitted from admission")
    driver_source = REPLICA_DRIVER_SOURCE if group == "replica-final" else DRIVER_SOURCE
    require(body.get("driver_source") == driver_source and files.get(driver_source) == driver_sha ==
            body.get("driver_source_sha256"), "Android driver differs from reviewed source")
    for name, digest in files.items():
        require(re.fullmatch(r"[0-9a-f]{64}", digest) and sha(admitted_file(root, name)) == digest,
                "Admitted source changed: " + name)
    ordinary = body.get("ordinary_results")
    require(isinstance(ordinary, list) and ordinary, "Ordinary safety evidence required")
    ordinary_classes = set()
    for item in ordinary:
        result_file = admitted_file(root, item["path"])
        result = pinned_json(result_file, item["sha256"], "Ordinary result changed")
        require(all(type(result.get(key)) is int for key in
                    ("exit_code", "failures", "cases", "expected_test_methods")), "Malformed ordinary result counters")
        require(result.get("validated_success") is True and result.get("exit_code") == 0 and
                result.get("failures") == 0 and result.get("cases", 0) > 0 and
                result.get("cases") == result.get("expected_test_methods") and
                result.get("source_drift") == [] and result.get("new_sources") == [] and
                result.get("validation_errors") == [], "Ordinary safety gate did not pass completely")
        classes, expected_classes = result.get("classes"), result.get("expected_class_methods")
        require(isinstance(classes, dict) and isinstance(expected_classes, dict) and classes and
                set(classes) == set(expected_classes), "Ordinary complete-class identities differ")
        ordinary_classes.update(classes)
        for name, row in classes.items():
            require(isinstance(row, dict) and all(type(row.get(key)) is int for key in
                    ("cases", "failures", "skipped")) and type(expected_classes[name]) is int and
                    expected_classes[name] > 0 and row["cases"] == expected_classes[name] and
                    row["failures"] == 0 and row["skipped"] == 0, "Incomplete or skipped ordinary class: " + name)
        require(sum(expected_classes.values()) == result["cases"], "Ordinary class total differs")
        old_sources = admitted_file(root, str((result_file.parent / result["manifest"]).relative_to(root)))
        require(pinned_json(old_sources, item["sources_sha256"], "Ordinary source manifest changed")["files"] == files,
                "Ordinary gate used different sources")
    if group == "retained":
        require(RETAINED_ORDINARY_CLASSES.issubset(ordinary_classes), "Retained complete ordinary safety classes omitted")
    artifacts = body.get("artifacts")
    require(isinstance(artifacts, dict) and set(artifacts) == {TARGET, TEST}, "Both exact APK packages required")
    expected = {}
    for package, items in artifacts.items():
        require(isinstance(items, list) and items, "Empty admitted APK set")
        paths = [item["path"] for item in items]
        require(len(paths) == len(set(paths)), "Duplicate admitted APK path")
        for item in items:
            require(item["path"].endswith(".apk") and re.fullmatch(r"[0-9a-f]{64}", item["sha256"]) and
                    sha(admitted_file(root, item["path"])) == item["sha256"], "Admitted APK changed")
        expected[package] = sorted(item["sha256"] for item in items)
    return expected

def require_admitted_image(actual, expected):
    require(set(actual) == set(expected) and all(
        sorted(item["sha256"] for item in actual[package]) == expected[package] for package in expected),
        "Installed APK set differs from the independently admitted build")

class Host:
    def __init__(self, adb, serial, output, total):
        self.adb = [adb, "-s", serial]
        self.output = output
        self.deadline = time.monotonic() + total
        self.running = None
        self.stage_deadline = None

    def remaining(self, ceiling=20):
        limit = min(self.deadline, self.stage_deadline) if self.stage_deadline is not None else self.deadline
        left = limit - time.monotonic()
        require(left > 0, "Predeclared complete-group or absolute-stage budget exhausted")
        return min(ceiling, left)

    def pause(self, seconds):
        time.sleep(self.remaining(seconds))

    def call(self, *args, check=True):
        result = subprocess.run(self.adb + list(args), stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=self.remaining())
        if check:
            require(result.returncode == 0, "Fixed adb operation failed: " + str(args[:3]))
        return result

    def text(self, *args):
        return self.call(*args).stdout.decode("utf-8", "strict").strip()

    def installed_image(self):
        packages = {}
        for package in (TARGET, TEST):
            paths = self.text("shell", "pm", "path", package).splitlines()
            require(paths and all(p.startswith("package:/data/app/") for p in paths), "Missing/unexpected installed test APK")
            items = []
            for entry in paths:
                path = entry.removeprefix("package:")
                require(re.fullmatch(r"/data/app/[A-Za-z0-9_./+=~-]+\.apk", path), "Unsafe APK path")
                digest = self.text("shell", "sha256sum", path).split()[0]
                require(re.fullmatch(r"[0-9a-f]{64}", digest), "Missing installed APK digest")
                items.append({"path": path, "sha256": digest})
            packages[package] = sorted(items, key=lambda x: x["path"])
        return packages

    def pids(self):
        result = self.call("shell", "pidof", TARGET, check=False)
        require(result.returncode in (0, 1), "Cannot read target PID")
        value = result.stdout.decode().strip()
        require(not value or re.fullmatch(r"[1-9][0-9]*(?: [1-9][0-9]*)*", value), "Malformed target PID result")
        return {int(pid) for pid in value.split()}

    def app_json(self, run, case, filename):
        # Entire path comes from UUID + fixed case/filename inventory. No arbitrary app file read.
        require(case in EXPECTED and filename in ("checkpoint.json", "terminal.json", "checkpoint-left.json", "prepare-terminal.json"), "Unknown evidence path")
        relative = f"files/dayforge-command-process-recovery/{run}/{case}/{filename}"
        result = self.call("shell", "run-as", TARGET, "cat", relative, check=False)
        if result.returncode != 0:
            # Missing fixed marker is allowed; transport/permission/I/O failure is not
            # evidence that the prepare phase never left its checkpoint.
            require(result.returncode == 1 and not result.stdout and
                b"No such file or directory" in result.stderr,
                "Cannot establish absence of fixed app evidence")
            return None
        require(len(result.stdout) <= 16384, "Evidence protocol exceeded its fixed metadata bound")
        try:
            return json.loads(result.stdout)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise Failed("Non-atomic/malformed evidence file") from error

    def begin(self, log: Path, run, case, phase, nonce):
        require(self.running is None, "Only one instrumentation process is allowed")
        handle = log.open("xb")
        command = self.adb + ["shell", "am", "instrument", "-w", "-r", "-e", "timeout_msec", str(ORDINARY_TEST_TIMEOUT_MS), "-e", "df_command_run", run,
            "-e", "df_command_case", case, "-e", "df_command_phase", phase,
            "-e", "df_command_nonce", nonce, COMPONENT]
        self.running = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=handle, stderr=subprocess.STDOUT)
        return self.running, handle

    def phase1(self, run, case, nonce, folder):
        began = time.monotonic()
        deadline = min(self.deadline, began + PER_PHASE_HOST_SECONDS)
        self.stage_deadline = deadline
        process, handle = self.begin(folder / "prepare-instrumentation.txt", run, case, "prepare", nonce)
        checkpoint = None
        try:
            while time.monotonic() < deadline:
                checkpoint = self.app_json(run, case, "checkpoint.json")
                if checkpoint is not None:
                    break
                require(process.poll() is None, "Preparation terminated before the exact checkpoint; preserve failure")
                elapsed = time.monotonic() - began
                # Sparse bounded evidence checks; never scan complete logcat or print unchanged status.
                self.pause(2 if elapsed < 10 else 5 if elapsed < 60 else 8)
            require(checkpoint is not None, "Checkpoint not reached within predeclared host phase budget")
            pid = validate_checkpoint(checkpoint, run, case, nonce)
            require(self.pids() == {pid}, "Checkpoint PID is not the sole target process")
            cmdline = self.call("shell", "run-as", TARGET, "cat", f"/proc/{pid}/cmdline").stdout.rstrip(b"\x00")
            require(cmdline == TARGET.encode(), "Refuse to signal any other process")
            require(process.poll() is None, "Instrumentation ended before host interruption")
            validate_prepare_output((folder / "prepare-instrumentation.txt").read_text(errors="replace"), after_signal=False)
            self.require_checkpoint_not_left(run, case, nonce, pid, folder)
            save(folder / "checkpoint.json", checkpoint)
            interrupted_at = time.time()
            # run-as uses only the isolated debug app's UID; never root/shell-kill another UID.
            self.call("shell", "run-as", TARGET, "kill", "-9", str(pid))
            wait_end = min(self.deadline, time.monotonic() + EXPECTED_ABORT_SECONDS)
            self.stage_deadline = wait_end
            while process.poll() is None and time.monotonic() < wait_end:
                self.pause(0.5)
            require(process.poll() is not None, "Expected instrumentation interruption did not terminate")
            require(pid not in self.pids(), "The original target PID survived the signal")
            # Drain the completed adb output before classifying. A normal/failed driver
            # terminal cannot be relabeled as an expected kill just because its PID died.
            validate_prepare_output((folder / "prepare-instrumentation.txt").read_text(errors="replace"), after_signal=True)
            self.require_checkpoint_not_left(run, case, nonce, pid, folder)
            save(folder / "host-kill-proof.json", {"format": 1, "pid": pid, "target": TARGET,
                "checkpoint_verified": True, "signal": "run-as SIGKILL", "interrupted_at_unix": interrupted_at,
                "instrumentation_exit_code": process.returncode, "status": "EXPECTED_INTERRUPTION_NOT_JUNIT_PASS"})
            self.remaining()  # Post-check host evidence/fsync work against the same abort deadline.
            return pid
        finally:
            self.stage_deadline = None
            if process.poll() is not None:
                self.running = None
            handle.close()

    def require_checkpoint_not_left(self, run, case, nonce, pid, folder):
        for filename in ("checkpoint-left.json", "prepare-terminal.json"):
            value = self.app_json(run, case, filename)
            if value is not None:
                # Preserve even malformed/wrong-identity reports as failures, not absence.
                save(folder / filename, value)
                require(isinstance(value, dict) and value.get("run") == run and value.get("case") == case and
                    value.get("nonce") == nonce and value.get("pid") == pid,
                    "Stale/malformed prepare termination marker")
                raise Failed("Preparation left its native checkpoint or emitted a terminal before expected interruption")

    def phase2(self, run, case, nonce, old_pid, folder):
        phase_deadline = min(self.deadline, time.monotonic() + PER_PHASE_HOST_SECONDS)
        self.stage_deadline = phase_deadline
        process, handle = self.begin(folder / "recovery-instrumentation.txt", run, case, "recover", nonce)
        try:
            pid_deadline = min(phase_deadline, time.monotonic() + 30)
            self.stage_deadline = pid_deadline
            new_pid = None
            while time.monotonic() < pid_deadline:
                observed = self.pids()
                if len(observed) == 1 and old_pid not in observed:
                    candidate = next(iter(observed))
                    cmdline = self.call("shell", "run-as", TARGET, "cat", f"/proc/{candidate}/cmdline").stdout.rstrip(b"\x00")
                    require(cmdline == TARGET.encode(), "Recovery PID is not the isolated target process")
                    new_pid = candidate
                    break
                require(process.poll() is None, "Recovery completed before a fresh target PID could be independently confirmed")
                self.pause(0.5)
            require(new_pid is not None, "No new isolated target PID appeared")
            self.stage_deadline = phase_deadline
            save(folder / "recovery-pid-proof.json", {"old_pid": old_pid, "new_pid": new_pid,
                "target": TARGET, "cmdline_verified": True})
            process.wait(timeout=self.remaining(PER_PHASE_HOST_SECONDS))
            require(process.returncode == 0, "Recovery instrumentation failed")
        finally:
            if process.poll() is not None:
                self.running = None
            handle.close()
        log = (folder / "recovery-instrumentation.txt").read_text(errors="replace")
        validate_driver_report(log, run, case, nonce, new_pid)
        terminal = self.app_json(run, case, "terminal.json")
        require(isinstance(terminal, dict) and terminal.get("format") == 1 and terminal.get("run") == run and
            terminal.get("case") == case and terminal.get("nonce") == nonce and terminal.get("old_pid") == old_pid and
            type(terminal.get("pid")) is int and terminal["pid"] > 0 and terminal["pid"] != old_pid and terminal["pid"] == new_pid and
            terminal.get("target_package") == TARGET and terminal.get("result") == EXPECTED[case],
            "Recovery terminal is missing, stale or did not come from a fresh target PID")
        checks = terminal.get("assertions")
        require(isinstance(checks, dict) and checks.keys() == (RETAINED_CHECKS if case in GROUPS["retained"] else CHECKS) and all(value is True for value in checks.values()),
            "Complete recovery/original oracle assertions were not confirmed")
        save(folder / "terminal.json", terminal)
        self.remaining()  # Includes terminal assertion evidence; no fresh180s after PID discovery.
        self.stage_deadline = None

    def stop_host_subprocess(self):
        # Ending the host subprocess is not a completed case or proof of an app kill. Its raw
        # failure files remain; do not erase app data/Keystore, retry, or manufacture terminal.
        process = self.running
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=5)
        self.running = None


def validate_checkpoint(checkpoint, run, case, nonce):
    require(isinstance(checkpoint, dict) and checkpoint.get("format") == 1 and
        checkpoint.get("run") == run and checkpoint.get("case") == case and checkpoint.get("nonce") == nonce and
        checkpoint.get("stage") == case and checkpoint.get("target_package") == TARGET and
        checkpoint.get("process_name") == TARGET and checkpoint.get("expected_interruption_is_not_junit_pass") is True,
        "Wrong checkpoint identity")
    if case == "post_generation_persist_pre_return":
        require(checkpoint.get("metadata_commit_observed") is True and type(checkpoint.get("control_native_ends")) is int and
            checkpoint["control_native_ends"] == 1 and isinstance(checkpoint.get("generation"), str) and
            str(uuid.UUID(checkpoint["generation"])) == checkpoint["generation"] and
            isinstance(checkpoint.get("preferences_hash"), str) and
            re.fullmatch(r"[0-9a-f]{64}", checkpoint["preferences_hash"]), "Wrong metadata persistence checkpoint")
        require("outer_commit_boundary_observed" not in checkpoint and "reference" not in checkpoint,
                "Metadata checkpoint must not pretend to be a command SQL commit")
    else:
        require(type(checkpoint.get("native_fault_hits")) is int and checkpoint["native_fault_hits"] == 1 and
            checkpoint.get("outer_commit_boundary_observed") is True, "Unconfirmed native checkpoint")
        if case == "post_retained_enroll_end_pre_return":
            require(type(checkpoint.get("legacy_count")) is int and checkpoint["legacy_count"] == 1 and
                type(checkpoint.get("revision")) is int and checkpoint["revision"] == 2 and
                all(isinstance(checkpoint.get(k), str) and str(uuid.UUID(checkpoint[k])) == checkpoint[k]
                    for k in ("storage_id", "dataset_id")) and
                isinstance(checkpoint.get("legacy_hash"), str) and re.fullmatch(r"[0-9a-f]{64}", checkpoint["legacy_hash"]),
                "Wrong retained enrollment checkpoint")
    pid = checkpoint.get("pid")
    require(type(pid) is int and pid > 0, "Malformed checkpoint PID")
    return pid


def validate_prepare_output(log, after_signal):
    """A retained checkpoint is not proof that a terminal/timeout never occurred."""
    require(re.search(r"^INSTRUMENTATION_(?:RESULT|STATUS): df_command_", log, re.M) is None,
        "Prepare emitted a dedicated driver terminal/status, not an expected interruption")
    require("df_command_result=" not in log and "OK (" not in log and "FAILURES!!!" not in log,
        "Prepare completed/failed normally before host classification")
    codes = re.findall(r"^INSTRUMENTATION_CODE: (-?[0-9]+)\s*$", log, re.M)
    if not after_signal:
        require(not codes and not any(marker in log for marker in
            ("INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED", "shortMsg=")),
            "Prepare already reached an instrumentation terminal before SIGKILL")
        return
    # Target death can legitimately produce Android's own cancellation/crash report.
    # This is different from our driver.finish Bundle and is never a JUnit pass.
    require(len(codes) <= 1 and all(code == "0" for code in codes),
        "Prepare has a success/duplicate/unrecognized normal instrumentation terminal")
    if codes:
        require(any(marker in log for marker in ("Process crashed", "Process killed", "INSTRUMENTATION_ABORTED")),
            "Unexplained ordinary instrumentation completion cannot become expected interruption")
    require("INSTRUMENTATION_FAILED" not in log,
        "Instrumentation startup/driver failure is not the requested process interruption")


def validate_driver_report(log, run, case, nonce, pid):
    """Dedicated component result, deliberately not a JUnit pass parser."""
    codes = re.findall(r"^INSTRUMENTATION_CODE: (-?[0-9]+)\s*$", log, re.M)
    require(codes == ["-1"] and not any(value in log for value in (
        "FAILURES!!!", "INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED")),
        "Recovery driver did not finish exactly one successful instrumentation phase")
    expected = {"df_command_driver": DRIVER, "df_command_run": run, "df_command_case": case,
        "df_command_phase": "recover", "df_command_nonce": nonce, "df_command_pid": str(pid),
        "df_command_phase_budget_ms": str(ORDINARY_TEST_TIMEOUT_MS), "df_command_result": "PASS",
        "df_command_assertion_groups": "7"}
    for key, value in expected.items():
        found = re.findall(r"^INSTRUMENTATION_RESULT: " + re.escape(key) + r"=([^\r\n]*)$", log, re.M)
        require(found == [value], "Missing/duplicate/stale dedicated driver field: " + key)


REPLICA_DRIVER = "replica-final-process/v1"
REPLICA_COMPONENT = TEST + "/com.dayforge.ReplicaCompletionProcessDriver"
REPLICA_DRIVER_SOURCE = "android/app/src/androidTest/java/com/dayforge/ReplicaCompletionProcessDriver.kt"
REPLICA_SOURCES = frozenset({REPLICA_DRIVER_SOURCE, "android/app/src/androidTest/AndroidManifest.xml",
    "android/app/src/androidTest/java/com/dayforge/data/repository/ReplicaCompletionHostScenario.kt",
    "android/app/src/androidTest/java/com/dayforge/data/repository/ReplicaCompletionProcessProtocol.kt",
    "android/app/src/androidTest/java/com/dayforge/data/repository/ReplicaCompletionProcessPeer.kt"})
REPLICA_PHASES = ("prepare_bound", "actual_fault", "fresh_complete")
REPLICA_FAULTS = {"file_repair": "IMAGE_HASH", "final_ds_forward": "FINAL_DATASTORE_WRITE_FAILURE"}
REPLICA_CHECKS = frozenset({"same_recovery_and_namespace", "closed_sql_and_durable_stage",
    "original_archive_and_seed", "fresh_material_proof", "fresh_authority_and_server_state",
    "completed_witness_and_pointer", "original_business_projection", "ordinary_access_restored_without_writes"})


def validate_replica_base(body, run, case, nonce, phase, pid):
    require(case in REPLICA_FAULTS and phase in REPLICA_PHASES and type(pid) is int and pid > 0,
            "Invalid fixed replica identity")
    require(isinstance(body, dict) and type(body.get("format")) is int and body["format"] == 1 and
        body.get("family") == REPLICA_DRIVER and body.get("run") == run and body.get("case") == case and
        body.get("nonce") == nonce and body.get("phase") == phase and type(body.get("pid")) is int and
        body["pid"] == pid and body.get("target_package") == TARGET and body.get("process_name") == TARGET and
        type(body.get("phase_budget_ms")) is int and body["phase_budget_ms"] == ORDINARY_TEST_TIMEOUT_MS,
        "Missing/stale replica phase identity")


def validate_replica_checkpoint(body, run, case, nonce, phase, pid, previous=None):
    validate_replica_base(body, run, case, nonce, phase, pid)
    require(phase in REPLICA_PHASES[:2] and body.get("stage") ==
        ("bound_closed" if phase == "prepare_bound" else "fault_closed"), "Wrong replica checkpoint stage")
    require(all(body.get(name) is True for name in ("resources_closed", "no_unknown_graph_boundary",
        "expected_interruption_is_not_junit_pass")), "Replica checkpoint did not close known resources")
    for name in ("seed_sha256", "archive_sha256", "stage_sha256"):
        require(isinstance(body.get(name), str) and re.fullmatch(r"[0-9a-f]{64}", body[name]),
                "Malformed replica evidence digest: " + name)
    recovery = body.get("recovery_id")
    require(isinstance(recovery, str) and re.fullmatch(r"[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}", recovery),
            "Malformed recovery identity")
    if phase == "actual_fault":
        require(previous is not None, "Fault checkpoint has no bound predecessor")
        bound, digest = previous
        require(pid != bound["pid"] and type(body.get("prepare_pid")) is int and body["prepare_pid"] == bound["pid"] and
            body.get("prepare_checkpoint_sha256") == digest, "Fault phase is not linked to original prepared process")
        require(all(body.get(name) == bound.get(name) for name in ("seed_sha256", "archive_sha256", "recovery_id")),
                "Fault phase changed original replica identity")
        require(body.get("fault_code") == REPLICA_FAULTS[case] and type(body.get("injection_hits")) is int and
            body["injection_hits"] == 1 and body.get("exact_fault_cause_confirmed") is True and
            body.get("release_fate") == "CLOSED", "Original fixed replica fault was not reached")


def validate_replica_terminal(body, run, case, nonce, pid, prepared, fault):
    validate_replica_base(body, run, case, nonce, "fresh_complete", pid)
    bound, bound_hash = prepared; failed, fault_hash = fault
    require(len({pid, bound["pid"], failed["pid"]}) == 3 and
        type(body.get("prepare_pid")) is int and body["prepare_pid"] == bound["pid"] and
        type(body.get("fault_pid")) is int and body["fault_pid"] == failed["pid"] and
        body.get("prepare_checkpoint_sha256") == bound_hash and body.get("fault_checkpoint_sha256") == fault_hash,
        "Replica terminal lacks the exact three-process/checkpoint chain")
    require(body.get("stage") == "terminal" and body.get("result") == "same_replica_completed_and_usable" and
        body.get("resources_closed") is True and all(body.get(name) == bound.get(name)
            for name in ("seed_sha256", "archive_sha256", "recovery_id")), "Replica terminal changed original identity or lacks safe close")
    assertions = body.get("assertions")
    require(isinstance(assertions, dict) and assertions.keys() == REPLICA_CHECKS and
        all(value is True for value in assertions.values()), "All eight fixed replica assertions are required")


def validate_replica_interruption(log, after_signal):
    require(re.search(r"^INSTRUMENTATION_(?:RESULT|STATUS): df_replica_", log, re.M) is None and
        "df_replica_result=" not in log, "Replica checkpoint phase emitted a driver terminal")
    # Reuse only Android's abort syntax, never the COUNT identity or seven-assertion oracle.
    validate_prepare_output(log, after_signal)


def validate_replica_driver(log, run, case, nonce, pid):
    require(re.findall(r"^INSTRUMENTATION_CODE: (-?[0-9]+)\s*$", log, re.M) == ["-1"] and
        not any(value in log for value in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED")) and
        re.search(r"^INSTRUMENTATION_RESULT: df_replica_(?:failure|terminal_marker_failure)", log, re.M) is None,
        "Replica driver did not report one successful final phase")
    expected = {"df_replica_driver": REPLICA_DRIVER, "df_replica_run": run, "df_replica_case": case,
        "df_replica_nonce": nonce, "df_replica_phase": "fresh_complete", "df_replica_pid": str(pid),
        "df_replica_phase_budget_ms": str(ORDINARY_TEST_TIMEOUT_MS), "df_replica_result": "PASS",
        "df_replica_assertion_groups": "8"}
    for key, value in expected.items():
        require(re.findall(r"^INSTRUMENTATION_RESULT: " + key + r"=([^\r\n]*)$", log, re.M) == [value],
                "Missing/duplicate replica driver result: " + key)


class ReplicaHost(Host):
    """Fixed three-phase transport; no scenario logic, authority or generic oracle."""
    def evidence(self, run, case, phase, filename):
        require(str(uuid.UUID(run)) == run and case in REPLICA_FAULTS and phase in REPLICA_PHASES and
            filename in ("checkpoint.json", "checkpoint-left.json", "driver-terminal.json", "terminal.json"),
            "Unknown replica evidence path")
        path = f"files/dayforge-replica-final-process/{run}/{case}/{phase}/{filename}"
        result = self.call("shell", "run-as", TARGET, "cat", path, check=False)
        if result.returncode != 0:
            require(result.returncode == 1 and not result.stdout and b"No such file or directory" in result.stderr,
                    "Cannot establish absence of replica evidence")
            return None
        raw = result.stdout
        require(0 < len(raw) <= 16384, "Replica evidence exceeded fixed metadata bound")
        try:
            value = json.loads(raw)
        except (UnicodeDecodeError, json.JSONDecodeError) as failure:
            raise Failed("Malformed replica evidence") from failure
        return value, raw

    def capture(self, folder, filename, evidence):
        require(evidence is not None, "Required replica evidence is absent")
        body, raw = evidence
        path = folder / filename
        if path.exists():
            require(path.read_bytes() == raw, "Replica evidence changed after observation")
        else:
            with path.open("xb") as target:
                target.write(raw); target.flush(); os.fsync(target.fileno())
        return body, hashlib.sha256(raw).hexdigest()

    def begin_replica(self, folder, run, case, phase, nonce):
        require(self.running is None, "Only one instrumentation process is allowed")
        command = self.adb + ["shell", "am", "instrument", "-w", "-r", "-e", "timeout_msec", "150000",
            "-e", "df_replica_run", run, "-e", "df_replica_case", case, "-e", "df_replica_phase", phase,
            "-e", "df_replica_nonce", nonce, REPLICA_COMPONENT]
        save(folder / "command.json", {"argv": command, "started_at_unix": time.time()})
        handle = (folder / "instrumentation.txt").open("xb")
        try:
            self.running = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=handle, stderr=subprocess.STDOUT)
        except BaseException:
            handle.close(); raise
        return self.running, handle

    def fresh_pid(self, process, old_pids):
        phase_deadline = self.stage_deadline
        self.stage_deadline = min(phase_deadline, time.monotonic() + 30)
        try:
            while time.monotonic() < self.stage_deadline:
                observed = self.pids()
                if len(observed) == 1 and not observed.intersection(old_pids):
                    pid = next(iter(observed))
                    self.exact_pid(pid)
                    return pid
                require(process.poll() is None, "Replica phase terminated before independent PID observation")
                self.pause(0.5)
            raise Failed("No fresh isolated replica PID appeared")
        finally:
            self.stage_deadline = phase_deadline

    def exact_pid(self, pid):
        require(self.pids() == {pid}, "Replica phase PID is not the sole target process")
        require(self.call("shell", "run-as", TARGET, "cat", f"/proc/{pid}/cmdline").stdout.rstrip(b"\x00") == TARGET.encode(),
                "Refuse to address a different process")

    def not_left(self, run, case, nonce, phase, pid, folder):
        for filename in ("checkpoint-left.json", "driver-terminal.json"):
            found = self.evidence(run, case, phase, filename)
            if found is not None:
                self.capture(folder, filename, found)
                validate_replica_base(found[0], run, case, nonce, phase, pid)
                raise Failed("Replica checkpoint left or driver terminated; cannot classify as expected kill")

    def checkpoint_phase(self, run, case, nonce, phase, folder, previous=None):
        folder.mkdir()
        began = time.monotonic(); self.stage_deadline = min(self.deadline, began + PER_PHASE_HOST_SECONDS)
        process, handle = self.begin_replica(folder, run, case, phase, nonce)
        pid = None
        try:
            pid = self.fresh_pid(process, set() if previous is None else {previous[0]["pid"]})
            save(folder / "pid-proof.json", {"pid": pid, "target": TARGET, "cmdline_verified": True})
            found = None
            while time.monotonic() < self.stage_deadline:
                found = self.evidence(run, case, phase, "checkpoint.json")
                if found is not None:
                    break
                require(process.poll() is None, "Replica phase terminated before its closed checkpoint")
                elapsed = time.monotonic() - began
                self.pause(2 if elapsed < 10 else 5 if elapsed < 60 else 8)
            require(found is not None, "Replica checkpoint absent at phase deadline")
            checkpoint = self.capture(folder, "checkpoint.json", found)
            validate_replica_checkpoint(checkpoint[0], run, case, nonce, phase, pid, previous)
            self.exact_pid(pid)
            require(process.poll() is None, "Replica instrumentation ended before exact PID interruption")
            validate_replica_interruption((folder / "instrumentation.txt").read_text(errors="replace"), False)
            self.not_left(run, case, nonce, phase, pid, folder)
            signal_at = time.time()
            self.call("shell", "run-as", TARGET, "kill", "-9", str(pid))
            self.stage_deadline = min(self.deadline, time.monotonic() + EXPECTED_ABORT_SECONDS)
            while process.poll() is None:
                self.pause(0.5)
            require(pid not in self.pids(), "Replica checkpoint process survived SIGKILL")
            validate_replica_interruption((folder / "instrumentation.txt").read_text(errors="replace"), True)
            self.not_left(run, case, nonce, phase, pid, folder)
            self.capture(folder, "checkpoint.json", self.evidence(run, case, phase, "checkpoint.json"))
            save(folder / "host-kill-proof.json", {"format": 1, "pid": pid, "target": TARGET, "phase": phase,
                "signal": "run-as SIGKILL", "interrupted_at_unix": signal_at, "instrumentation_exit_code": process.returncode,
                "checkpoint_sha256": checkpoint[1], "status": "EXPECTED_CLOSED_CHECKPOINT_INTERRUPTION_NOT_JUNIT_PASS"})
            self.remaining()
            return checkpoint
        finally:
            save(folder / "host-phase-status.json", {"pid": pid, "instrumentation_exit_code": process.poll(),
                "host_process_pid": process.pid, "host_elapsed_seconds": time.monotonic() - began})
            if process.poll() is not None:
                self.running = None
            handle.close(); self.stage_deadline = None

    def final_phase(self, run, case, nonce, folder, prepared, fault):
        folder.mkdir()
        began = time.monotonic(); self.stage_deadline = min(self.deadline, began + PER_PHASE_HOST_SECONDS)
        process, handle = self.begin_replica(folder, run, case, "fresh_complete", nonce)
        pid = None
        try:
            pid = self.fresh_pid(process, {prepared[0]["pid"], fault[0]["pid"]})
            save(folder / "pid-proof.json", {"pid": pid, "prepare_pid": prepared[0]["pid"],
                "fault_pid": fault[0]["pid"], "target": TARGET, "cmdline_verified": True})
            process.wait(timeout=self.remaining(PER_PHASE_HOST_SECONDS))
            require(process.returncode == 0, "Replica final instrumentation transport failed")
            validate_replica_driver((folder / "instrumentation.txt").read_text(errors="replace"), run, case, nonce, pid)
            terminal = self.capture(folder, "terminal.json", self.evidence(run, case, "fresh_complete", "terminal.json"))
            validate_replica_terminal(terminal[0], run, case, nonce, pid, prepared, fault)
            driver, _ = self.capture(folder, "driver-terminal.json",
                self.evidence(run, case, "fresh_complete", "driver-terminal.json"))
            validate_replica_base(driver, run, case, nonce, "fresh_complete", pid)
            require(driver.get("stage") == "driver_terminal" and driver.get("result") == "PASS" and
                driver.get("may_classify_as_expected_kill") is False and
                self.evidence(run, case, "fresh_complete", "checkpoint-left.json") is None,
                "Replica final driver failed or left a checkpoint")
            for phase, old in (("prepare_bound", prepared), ("actual_fault", fault)):
                self.not_left(run, case, nonce, phase, old[0]["pid"], folder.parent / phase)
                self.capture(folder.parent / phase, "checkpoint.json", self.evidence(run, case, phase, "checkpoint.json"))
            self.remaining()
            return terminal
        finally:
            save(folder / "host-phase-status.json", {"pid": pid, "instrumentation_exit_code": process.poll(),
                "host_process_pid": process.pid, "host_elapsed_seconds": time.monotonic() - began})
            if process.poll() is not None:
                self.running = None
            handle.close(); self.stage_deadline = None


def run_replica_final(args):
    require(re.fullmatch(r"[A-Za-z0-9_.:-]+", args.serial), "Unsafe serial")
    require(2700 <= args.budget_seconds <= 10800 and not args.output.exists(), "Fixed complete replica budget/output required")
    began = time.monotonic()
    expected = load_admission(args.admission, args.admission_sha256, args.source_root,
        args.serial, args.admitted_driver_sha256, "replica-final")
    args.output.mkdir(parents=True)
    run = str(uuid.uuid4()); nonce = uuid.uuid4().hex
    host = ReplicaHost(args.adb, args.serial, args.output, args.budget_seconds); host.deadline = began + args.budget_seconds
    plan = {"format": 1, "family": REPLICA_DRIVER, "run": run, "nonce": nonce, "group": "replica-final",
        "cases": list(REPLICA_FAULTS), "phases": list(REPLICA_PHASES), "instrumentation_component": REPLICA_COMPONENT,
        "phase_budget_ms": 150000, "host_phase_seconds": 180, "host_abort_seconds": 30,
        "budget_seconds": args.budget_seconds, "absolute_stage_deadlines": True, "junit_result": False,
        "no_install_or_uninstall_or_clear": True, "build_admission_sha256": args.admission_sha256,
        "host_script_sha256": sha(Path(__file__)), "status": "RUNNING", "completed": []}
    save(args.output / "plan.json", plan)
    try:
        require(host.text("get-state") == "device" and not args.serial.startswith("emulator-") and
            host.text("shell", "getprop", "ro.kernel.qemu") not in ("1", "true"), "Physical authorized target required")
        require(not host.pids(), "Replica group requires no pre-existing target process; do not kill unknown work")
        listed = host.text("shell", "pm", "list", "instrumentation", TARGET).splitlines()
        for component in (REPLICA_COMPONENT, COMPONENT, TEST + "/com.dayforge.HiltTestRunner"):
            require(listed.count(f"instrumentation:{component} (target={TARGET})") == 1, "Required fixed instrumentation missing")
        save(args.output / "instrumentation-components.json", {"installed": listed})
        before = host.installed_image(); save(args.output / "installed-apks-before.json", before)
        require_admitted_image(before, expected)
        for case in REPLICA_FAULTS:
            folder = args.output / case; folder.mkdir()
            prepared = fault = terminal = None
            for phase in REPLICA_PHASES:
                require(load_admission(args.admission, args.admission_sha256, args.source_root, args.serial,
                    args.admitted_driver_sha256, "replica-final") == expected, "Replica source/build admission changed")
                require(host.installed_image() == before, "APK changed between replica phases")
                if phase == "prepare_bound":
                    prepared = host.checkpoint_phase(run, case, nonce, phase, folder / phase)
                elif phase == "actual_fault":
                    fault = host.checkpoint_phase(run, case, nonce, phase, folder / phase, prepared)
                else:
                    terminal = host.final_phase(run, case, nonce, folder / phase, prepared, fault)
            require(host.installed_image() == before and load_admission(args.admission, args.admission_sha256,
                args.source_root, args.serial, args.admitted_driver_sha256, "replica-final") == expected,
                "Replica source/APK changed during final assertions")
            save(folder / "case-result.json", {"case": case, "status": "PASSED_THREE_PHASE_REPLICA_FUNCTIONAL_CHAIN",
                "prepare_pid": prepared[0]["pid"], "fault_pid": fault[0]["pid"], "fresh_pid": terminal[0]["pid"],
                "prepare_checkpoint_sha256": prepared[1], "fault_checkpoint_sha256": fault[1], "terminal_sha256": terminal[1],
                "not_original_510_junit_or_performance_pass": True})
            plan["completed"].append(case)
            print(json.dumps({"event": "replica_case_complete", "case": case}), flush=True)
        plan["status"] = "PASSED_COMPLETE_DECLARED_REPLICA_GROUP"
    except BaseException as failure:
        plan["status"] = "FAILED_PRESERVED"; plan["error_type"] = type(failure).__name__; plan["error"] = str(failure)
        raise
    finally:
        host.stop_host_subprocess()
        save(args.output / "result.json", plan)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--group", choices=GROUPS, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--budget-seconds", type=int, default=2700)
    parser.add_argument("--admitted-driver-sha256", required=True,
        help="Reviewed fixed Android driver source digest; checked against actual file and complete source freeze")
    parser.add_argument("--admission", type=Path, required=True)
    parser.add_argument("--admission-sha256", required=True)
    parser.add_argument("--source-root", type=Path, required=True)
    args = parser.parse_args()
    if args.group == "replica-final":
        return run_replica_final(args)
    require(re.fullmatch(r"[A-Za-z0-9_.:-]+", args.serial), "Unsafe serial")
    require(re.fullmatch(r"[0-9a-f]{64}", args.admitted_driver_sha256), "Reviewed driver digest required")
    require(2700 <= args.budget_seconds <= 10800, "Whole-group budget must be predeclared within existing upper range")
    require(not args.output.exists(), "Never overwrite old evidence")
    admission_started = time.monotonic()
    expected_apks = load_admission(args.admission, args.admission_sha256, args.source_root,
        args.serial, args.admitted_driver_sha256, args.group)
    args.output.mkdir(parents=True)
    run = str(uuid.uuid4()); nonce = uuid.uuid4().hex
    host = Host(args.adb, args.serial, args.output, args.budget_seconds)
    host.deadline = admission_started + args.budget_seconds
    manifest = {"format": 1, "run": run, "nonce": nonce, "group": args.group, "cases": list(GROUPS[args.group]),
        "driver": DRIVER, "instrumentation_component": COMPONENT, "junit_result": False, "driver_review_sha256": args.admitted_driver_sha256,
        "build_admission_sha256": args.admission_sha256, "host_script_sha256": sha(Path(__file__)),
        "packages": [TARGET, TEST], "budget_seconds": args.budget_seconds,
        "ordinary_test_timeout_ms": ORDINARY_TEST_TIMEOUT_MS,
        "host_prepare_seconds": PER_PHASE_HOST_SECONDS, "host_expected_abort_seconds": EXPECTED_ABORT_SECONDS,
        "host_recovery_seconds": PER_PHASE_HOST_SECONDS, "absolute_stage_deadlines": True,
        "no_install_or_uninstall": True,
        "status": "RUNNING", "completed": []}
    save(args.output / "plan.json", manifest)
    try:
        require(host.text("get-state") == "device", "Physical authorized target unavailable")
        require(not args.serial.startswith("emulator-"), "Emulators are not allowed")
        require(host.text("shell", "getprop", "ro.kernel.qemu") not in ("1", "true"), "Emulator runtime is not allowed")
        listed = host.text("shell", "pm", "list", "instrumentation", TARGET).splitlines()
        expected_component = f"instrumentation:{COMPONENT} (target={TARGET})"
        original_component = f"instrumentation:{TEST}/com.dayforge.HiltTestRunner (target={TARGET})"
        require(listed.count(expected_component) == 1 and listed.count(original_component) == 1,
            "Dedicated driver and unchanged ordinary runner must both be installed for the fixed testbed target")
        save(args.output / "instrumentation-components.json", {"target": TARGET,
            "driver": expected_component, "ordinary_runner": original_component})
        before = host.installed_image(); save(args.output / "installed-apks-before.json", before)
        require_admitted_image(before, expected_apks)
        for case in GROUPS[args.group]:
            require(load_admission(args.admission, args.admission_sha256, args.source_root,
                args.serial, args.admitted_driver_sha256, args.group) == expected_apks, "Local build admission changed")
            require(host.installed_image() == before, "Package changed before preparation")
            folder = args.output / case; folder.mkdir()
            old_pid = host.phase1(run, case, nonce, folder)
            require(host.installed_image() == before, "Package changed before recovery")
            host.phase2(run, case, nonce, old_pid, folder)
            require(host.installed_image() == before, "Package changed between recovery phases")
            require(load_admission(args.admission, args.admission_sha256, args.source_root,
                args.serial, args.admitted_driver_sha256, args.group) == expected_apks, "Local build admission changed during recovery")
            save(folder / "case-result.json", {"status": "PASSED_WITH_EXPECTED_PREPARATION_INTERRUPTION", "case": case,
                "prepare_log_sha256": sha(folder / "prepare-instrumentation.txt"),
                "recovery_log_sha256": sha(folder / "recovery-instrumentation.txt"),
                "checkpoint_sha256": sha(folder / "checkpoint.json"), "terminal_sha256": sha(folder / "terminal.json")})
            manifest["completed"].append(case)
            print(json.dumps({"event": "case_complete", "case": case}), flush=True)
        manifest["status"] = "PASSED_COMPLETE_DECLARED_GROUP"
    except BaseException as error:
        manifest["status"] = "FAILED_PRESERVED"
        manifest["error_type"] = type(error).__name__
        manifest["error"] = str(error)
        raise
    finally:
        host.stop_host_subprocess()
        save(args.output / "result.json", manifest)

if __name__ == "__main__":
    main()
