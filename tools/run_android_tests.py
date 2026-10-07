"""Build once, discover on a physical device, and run ALL tests in three sequential shards.

No filters, retries, parallel devices, raised per-case deadlines, or host Android tests.
Reports from prior runs are moved aside, never accepted as current evidence.
"""

import contextlib
import fcntl
import hashlib
import json
import os
import signal
import stat
import subprocess
import sys
import time
import uuid
from pathlib import Path

from tools.check_android_coverage import validate as validate_coverage
from tools.check_android_results import read_cases
from tools.check_android_warnings import find_growth, load_budget, parse_warnings

SHARDS = 3
SHARD_TIMEOUT_SECONDS = 1500
TARGET = "com.dayforge.testbed"
TEST_PACKAGE = TARGET + ".test"
COMPONENT = TEST_PACKAGE + "/com.dayforge.HiltTestRunner"
RESULTS = "outputs/androidTest-results/connected/deviceTest"
COVERAGE_INPUT = "outputs/code_coverage/deviceTestAndroidTest/connected"
COVERAGE_REPORT = "reports/coverage/androidTest/deviceTest/connected"


def discover(output: str) -> set[tuple[str, str]]:
    """Require complete paired log-only runner events, including parameterized names."""
    bundle = {}
    started = None
    cases = set()
    total = None
    final = []
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line[24:].partition("=")
            if separator and key in ("class", "test", "current", "numtests"):
                if key in bundle:
                    raise ValueError("Duplicate discovery event field")
                bundle[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.partition(": ")[2])
            identity = (bundle["class"], bundle["test"])
            current, declared = int(bundle["current"]), int(bundle["numtests"])
            if not all(value.strip() for value in identity) or declared <= 0:
                raise ValueError("Empty discovery identity or count")
            if total is not None and total != declared:
                raise ValueError("Discovery count changed")
            total = declared
            if code == 1 and started is None:
                if current != len(cases) + 1 or identity in cases:
                    raise ValueError("Duplicate or out-of-order discovery")
                started = identity
            elif code == 0 and identity == started and current == len(cases) + 1:
                cases.add(identity)
                started = None
            else:
                raise ValueError("Incomplete, skipped or failed discovery event")
            bundle = {}
        elif line.startswith("INSTRUMENTATION_CODE: "):
            final.append(int(line.partition(": ")[2]))
    if final != [-1] or bundle or started or not cases or len(cases) != total:
        raise ValueError("Discovery did not finish the complete nonempty suite")
    return cases


def digest(path: Path) -> str:
    if path.is_symlink() or not path.is_file() or path.stat().st_size == 0:
        raise ValueError(f"Missing, empty or linked artifact: {path}")
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def validate_shards(expected: set[tuple[str, str]], directories: list[Path]) -> int:
    if len(directories) != SHARDS or not expected:
        raise ValueError("All fixed shards and full discovery are required")
    actual = set()
    for directory in directories:
        cases, errors = read_cases(directory)
        if errors:
            raise ValueError("; ".join(errors))
        if actual & cases:
            raise ValueError("A testcase occurred in multiple shards")
        actual.update(cases)
    if actual != expected:
        raise ValueError(
            f"Discovery mismatch: {len(expected - actual)} missing, {len(actual - expected)} unexpected"
        )
    return len(actual)


def check_global_arguments(repo: Path) -> None:
    # A log-only actual run can otherwise look like passing tests. Reject external
    # argument injection; explicit runner arguments below are the only allowed ones.
    text = "\n".join(key + "=" + value for key, value in os.environ.items())
    for path in (
        repo / "android/gradle.properties",
        Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
        / "gradle.properties",
    ):
        if path.exists():
            text += "\n" + "\n".join(
                line
                for line in path.read_text().splitlines()
                if not line.lstrip().startswith(("#", "!"))
            )
    if "android.testInstrumentationRunnerArguments" in text:
        raise ValueError(
            "External instrumentation arguments are not allowed by the full-suite gate"
        )


@contextlib.contextmanager
def verification_lock():
    # Stable across worktrees and devices; never unlink a lock inode still in use.
    path = Path("/tmp") / f"dayforge-android-verification-{os.getuid()}.lock"
    fd = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(fd)
        if (
            not stat.S_ISREG(info.st_mode)
            or info.st_uid != os.getuid()
            or info.st_nlink != 1
            or info.st_mode & 0o022
        ):
            raise ValueError("Unsafe Android verification lock")
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise ValueError(
                "Another DayForge Android build/device run is active"
            ) from error
        yield
    finally:
        os.close(fd)


def command(
    argv: list[str], cwd: Path, log: Path, timeout: int, echo: bool = True
) -> None:
    """Bound the owned process; cancellation waits for termination before returning."""
    with (
        log.open("x", encoding="utf-8") as output,
        log.open(encoding="utf-8", errors="replace") as reader,
    ):
        process = subprocess.Popen(
            argv,
            cwd=cwd,
            stdout=output,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            deadline = time.monotonic() + timeout
            while True:
                try:
                    code = process.wait(
                        timeout=min(1, max(0.001, deadline - time.monotonic()))
                    )
                except subprocess.TimeoutExpired:
                    code = None
                chunk = reader.read()
                if echo:
                    print(chunk, end="", flush=True)
                if code is not None:
                    if code:
                        raise ValueError(f"Command failed ({code}); see {log}")
                    return
                if time.monotonic() >= deadline:
                    raise ValueError(f"Command exceeded {timeout}s; see {log}")
        finally:
            if process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    process.wait(timeout=10)


class Gate:
    def __init__(self, repo: Path):
        self.repo = repo
        self.android = repo / "android"
        self.build = self.android / "app/build"
        self.run = self.build / "reports/dayforge-device" / uuid.uuid4().hex
        for parent in (self.run.parent, *self.run.parents):
            if parent.is_symlink():
                raise ValueError("Linked verification output root")
            if parent == repo:
                break
        self.run.mkdir(parents=True, exist_ok=False)
        self.logs = []
        self.serial = os.environ.get("ANDROID_SERIAL", "")
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        self.adb = str(Path(sdk) / "platform-tools/adb") if sdk else "adb"
        self.owned_device = False
        self.evidence = None

    def execute(self, name: str, argv: list[str], timeout: int = 120) -> Path:
        log = self.run / (name + ".log")
        self.logs.append(log)
        command(argv, self.android, log, timeout, echo=name != "discovery")
        return log

    def adb_command(self, name: str, *args: str, timeout: int = 120) -> Path:
        if not self.serial:
            raise ValueError("Physical-device serial required")
        return self.execute(name, [self.adb, "-s", self.serial, *args], timeout)

    def rotate(self, relative: str, name: str) -> Path:
        path = self.build / relative
        # Only these fixed generated roots may be moved, never arbitrary input paths.
        if relative not in (RESULTS, COVERAGE_INPUT, COVERAGE_REPORT):
            raise ValueError("Unknown generated artifact root")
        for parent in (path, *path.parents):
            if parent == self.repo:
                break
            if parent.is_symlink():
                raise ValueError("Linked generated artifact root")
        target = self.run / name
        if path.exists():
            if not path.is_dir():
                raise ValueError("Generated artifact root is not a directory")
            path.rename(target)
        return target

    def apks(self) -> list[Path]:
        paths = []
        for relative, application in (
            ("outputs/apk/deviceTest", TARGET),
            ("outputs/apk/androidTest/deviceTest", TEST_PACKAGE),
        ):
            directory = self.build / relative
            metadata = json.loads((directory / "output-metadata.json").read_text())
            elements = metadata["elements"]
            if metadata["applicationId"] != application or len(elements) != 1:
                raise ValueError("Only the isolated testbed APKs may be installed")
            name = elements[0]["outputFile"]
            if Path(name).name != name or not name.endswith(".apk"):
                raise ValueError("Invalid APK output name")
            path = directory / name
            digest(path)
            paths.append(path)
        return paths

    def run_gate(self, build_only: bool = False) -> None:
        check_global_arguments(self.repo)
        self.execute(
            "build",
            [
                "./gradlew",
                "--no-daemon",
                "lintDebug",
                "assembleDebug",
                "assembleDeviceTest",
                "assembleDeviceTestAndroidTest",
            ],
            1200,
        )
        self.check_warnings()
        if build_only:
            self.check_warnings()
            print("Android build/static checks passed; physical-device tests NOT RUN.")
            return
        self.rotate(RESULTS, "prior-results")
        self.rotate(COVERAGE_INPUT, "prior-coverage-input")
        self.rotate(COVERAGE_REPORT, "prior-coverage-report")
        apks = self.apks()
        hashes = [digest(path) for path in apks]
        self.owned_device = True
        self.cleanup(prefix="initial")
        for index, path in enumerate(apks):
            self.adb_command(f"install-{index}", "install", "-r", str(path))
        log = self.adb_command(
            "discovery",
            "shell",
            "am",
            "instrument",
            "-w",
            "-r",
            "-e",
            "log",
            "true",
            "-e",
            "timeout_msec",
            "150000",
            COMPONENT,
            timeout=150,
        )
        expected = discover(log.read_text())
        self.cleanup(prefix="discovery")
        print(
            f"Discovered {len(expected)} cases; running {SHARDS} fixed sequential shards.",
            flush=True,
        )
        reports, inputs = [], []
        for index in range(SHARDS):
            self.execute(
                f"shard-{index}",
                [
                    "./gradlew",
                    "--no-daemon",
                    "connectedDeviceTestAndroidTest",
                    f"-Pandroid.testInstrumentationRunnerArguments.numShards={SHARDS}",
                    f"-Pandroid.testInstrumentationRunnerArguments.shardIndex={index}",
                    "-Pandroid.testInstrumentationRunnerArguments.log=false",
                    "-Pandroid.testInstrumentationRunnerArguments.timeout_msec=150000",
                ],
                SHARD_TIMEOUT_SECONDS,
            )
            if hashes != [digest(path) for path in apks]:
                raise ValueError("APKs changed during verification")
            reports.append(self.rotate(RESULTS, f"shard-{index}-results"))
            cases, errors = read_cases(reports[-1])
            if errors or not cases or not cases <= expected:
                raise ValueError("; ".join(errors) or "Empty shard")
            source = self.rotate(COVERAGE_INPUT, f"shard-{index}-coverage")
            files = sorted(source.rglob("*.ec"))
            if len(files) != 1:
                raise ValueError(
                    "Exactly one physical-device coverage file is required per shard"
                )
            digest(files[0])
            inputs.append(files[0])
            print(
                f"Shard {index + 1}/{SHARDS}: {len(cases)} completed cases.", flush=True
            )
        count = validate_shards(expected, reports)
        destination = self.build / COVERAGE_INPUT
        destination.mkdir(parents=True, exist_ok=False)
        for index, source in enumerate(inputs):
            # The locked AGP reads all files recursively from CODE_COVERAGE. All
            # fresh shard files must reach the existing report task; no new plugin.
            (destination / f"shard-{index}.ec").write_bytes(source.read_bytes())
        self.execute(
            "coverage",
            [
                "./gradlew",
                "--no-daemon",
                "createDeviceTestCoverageReport",
                "-x",
                "connectedDeviceTestAndroidTest",
            ],
            300,
        )
        errors = validate_coverage(self.build / COVERAGE_REPORT / "report.xml")
        if errors:
            raise ValueError("; ".join(errors))
        if hashes != [digest(path) for path in apks]:
            raise ValueError("APKs changed during coverage reporting")
        self.check_warnings()
        self.evidence = {
            "format": 1,
            "status": "complete",
            "shards": SHARDS,
            "cases": count,
            "apk_sha256": hashes,
            "discovery_sha256": digest(log),
            "coverage_inputs_sha256": [digest(path) for path in inputs],
            "coverage_report_sha256": digest(
                self.build / COVERAGE_REPORT / "report.xml"
            ),
            "test_reports_sha256": [
                {
                    str(path.relative_to(self.run)): digest(path)
                    for path in directory.glob("TEST-*.xml")
                }
                for directory in reports
            ],
            "discovered_cases": sorted(expected),
        }

    def finish(self) -> None:
        if self.evidence is not None:
            (self.run / "complete.json").write_text(
                json.dumps(self.evidence, indent=2) + "\n"
            )
            print(
                f"Full physical Android gate passed: {self.evidence['cases']} cases, all {SHARDS} shards and coverage. Evidence: {self.run}"
            )

    def check_warnings(self) -> None:
        combined = self.run / "compiler.log"
        combined.write_text(
            "\n".join(log.read_text(errors="replace") for log in self.logs)
        )
        budget = load_budget(self.repo / "android/compiler-warning-budget.json")
        # Evaluate each invocation independently; recompiling the same source in
        # a second invocation must not inflate an existing per-build budget.
        for log in self.logs:
            growth = find_growth(
                parse_warnings(log.read_text(errors="replace").splitlines()), budget
            )
            if growth:
                raise ValueError(
                    f"Android compiler/resource warning budget exceeded: {growth}"
                )

    def cleanup(self, prefix: str = "final") -> None:
        if self.owned_device:
            # Only the two testbed package identities, after the process has joined.
            errors = []
            try:
                self.adb_command(
                    prefix + "-stop-testbed", "shell", "am", "force-stop", TARGET
                )
            except (OSError, ValueError) as error:
                errors.append(str(error))
            for name, package in (("tests", TEST_PACKAGE), ("testbed", TARGET)):
                try:
                    listing = (
                        self.adb_command(
                            prefix + "-packages-" + name,
                            "shell",
                            "pm",
                            "list",
                            "packages",
                            package,
                        )
                        .read_text()
                        .splitlines()
                    )
                    if any(not line.startswith("package:") for line in listing):
                        raise ValueError("Cannot confirm testbed package cleanup state")
                    if "package:" + package in listing:
                        self.adb_command(
                            prefix + "-uninstall-" + name, "uninstall", package
                        )
                except (OSError, ValueError) as error:
                    errors.append(str(error))
            if errors:
                raise ValueError("Testbed cleanup failed: " + "; ".join(errors))


def main() -> int:
    if sys.argv[1:] not in ([], ["--build-only"]):
        print(
            "Usage: python -m tools.run_android_tests [--build-only]", file=sys.stderr
        )
        return 2
    gate = None

    def interrupt(_signum, _frame):
        raise KeyboardInterrupt

    previous = signal.signal(signal.SIGTERM, interrupt)
    try:
        with verification_lock():
            if not sys.argv[1:]:
                os.environ["ANDROID_SERIAL"] = subprocess.check_output(
                    [sys.executable, "-m", "tools.check_android_device"],
                    text=True,
                    timeout=30,
                ).strip()
            gate = Gate(Path(__file__).resolve().parents[1])
            try:
                gate.run_gate(build_only=bool(sys.argv[1:]))
            except BaseException as primary:
                try:
                    gate.cleanup()
                except (OSError, ValueError, KeyError, KeyboardInterrupt) as cleanup:
                    primary.add_note(f"Testbed cleanup also failed: {cleanup}")
                raise
            else:
                gate.cleanup()
            gate.finish()
        return 0
    except (
        OSError,
        ValueError,
        KeyError,
        subprocess.SubprocessError,
        KeyboardInterrupt,
    ) as error:
        print(
            f"Android gate failed: {error}. Evidence: {gate.run if gate else 'not started'}",
            file=sys.stderr,
        )
        for note in getattr(error, "__notes__", ()):
            print(note, file=sys.stderr)
        return 1
    finally:
        signal.signal(signal.SIGTERM, previous)


if __name__ == "__main__":
    raise SystemExit(main())
