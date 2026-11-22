"""Run one part of the hosted Android build. No device is installed or exercised."""
import argparse
from contextlib import contextmanager
import json
import os
import re
from pathlib import Path
import signal
import subprocess
import threading
import time

from tools.run_android_tests import Gate, check_global_arguments, digest, verification_lock


TASKS = {
    "app": ("lintDebug", "assembleDebug"),
    "instrumentation": ("assembleDeviceTest", "assembleDeviceTestAndroidTest"),
}
JVM_CLASSES = {
    "org.gradle.launcher.daemon.bootstrap.GradleDaemon": "gradle",
    "org.jetbrains.kotlin.daemon.KotlinCompileDaemon": "kotlin",
    "worker.org.gradle.process.internal.worker.GradleWorkerMain": "gradle-worker",
}


def kotlin_startup_heap(args):
    """Read only a standalone JVM -Xmx before the Kotlin daemon entry point."""
    entrypoint = "org.jetbrains.kotlin.daemon.KotlinCompileDaemon"
    if entrypoint not in args:
        return None
    return next((arg for arg in reversed(args[:args.index(entrypoint)])
                 if re.fullmatch(r"-Xmx[1-9][0-9]*[kKmMgG]?", arg)), None)


def snapshot():
    """Read only public JVM counters and memory accounting on the hosted Linux runner."""
    mem = Path("/proc/meminfo").read_text().splitlines()
    available = next(int(row.split()[1]) for row in mem if row.startswith("MemAvailable:"))
    sample = {"elapsed_monotonic_s": round(time.monotonic(), 2), "host_available_kib": available, "jvms": []}
    for group in Path("/proc/self/cgroup").read_text().splitlines():
        if group.startswith("0::"):
            cgroup = Path("/sys/fs/cgroup") / group[3:].lstrip("/")
            for name in ("memory.current", "memory.max", "memory.events", "cpu.stat"):
                source = cgroup / name
                if source.is_file():
                    sample[name] = source.read_text().strip()
    for proc in Path("/proc").glob("[0-9]*"):
        try:
            if (proc / "comm").read_text().strip() != "java":
                continue
            # Archive identifiers and counters only. JVM arguments may contain credentials.
            args = (proc / "cmdline").read_bytes().decode(errors="replace").split("\0")
            role = next((label for main, label in JVM_CLASSES.items() if main in args), None)
            if not role:
                continue
            stat = (proc / "stat").read_text().rsplit(")", 1)[1].split()
            cpu_seconds = (int(stat[11]) + int(stat[12])) / os.sysconf("SC_CLK_TCK")
            status = (proc / "status").read_text().splitlines()
            rss = next(int(row.split()[1]) for row in status if row.startswith("VmRSS:"))
            jvm = {"role": role, "pid": int(proc.name), "cpu_seconds": round(cpu_seconds, 2), "rss_kib": rss}
            sample["jvms"].append(jvm)
            if role == "kotlin":
                jvm["startup_xmx"] = kotlin_startup_heap(args)
                stats = subprocess.run(["jstat", "-gcutil", proc.name], capture_output=True, text=True, timeout=3)
                rows = stats.stdout.strip().splitlines()
                if stats.returncode == 0 and len(rows) == 2:
                    columns = dict(zip(rows[0].split(), rows[1].split()))
                    jvm["gc"] = {k: columns[k] for k in ("FGC", "FGCT", "GCT", "O") if k in columns}
        except (FileNotFoundError, ProcessLookupError, PermissionError, StopIteration, subprocess.TimeoutExpired):
            # The process may exit during sampling. The build result comes from Gradle.
            continue
    return sample


@contextmanager
def resource_samples(gate, lane):
    if lane == "app":
        yield
        return
    stop = threading.Event()

    def collect():
        with (gate.run / "resources.jsonl").open("x") as out:
            while True:
                try:
                    sample = snapshot()
                    log = gate.run / "build.log"
                    if log.exists():
                        with log.open("rb") as build_log:
                            build_log.seek(max(0, log.stat().st_size - 16_384))
                            tasks = re.findall(rb"^> Task (:[\w:.-]+)", build_log.read(), re.MULTILINE)
                        if tasks:
                            sample["last_reported_task"] = tasks[-1].decode("ascii")
                except (OSError, ValueError, StopIteration) as error:
                    sample = {"sample_error": type(error).__name__}
                out.write(json.dumps(sample) + "\n")
                out.flush()
                jvms = ", ".join(
                    f"{j['role']}[{j['pid']}] cpu_s={j['cpu_seconds']} rss_kib={j['rss_kib']}"
                    + (f" xmx={j['startup_xmx']}" if j.get("startup_xmx") else "")
                    for j in sample.get("jvms", ())
                )
                print(f"Build sample: last_reported_task={sample.get('last_reported_task', 'unknown')} "
                      f"host_available_kib={sample.get('host_available_kib', 'unknown')} "
                      f"jvms={jvms or 'none observed'}", flush=True)
                if stop.wait(180):
                    return

    thread = threading.Thread(target=collect, daemon=True)
    thread.start()
    try:
        yield
    finally:
        stop.set()
        thread.join()


def build(gate, lane):
    check_global_arguments(gate.repo)
    tasks = TASKS[lane]
    options = ["-Pkotlin.daemon.jvmargs=-Xmx6144m"] if lane == "instrumentation" else []
    budget = {"mode": "hosted_build", "lane": lane, "tasks": tasks, "gradle_options": options,
              "command_timeout_seconds": gate.build_timeout_seconds,
              "checkout_sha": os.environ.get("GITHUB_SHA"), "run_id": os.environ.get("GITHUB_RUN_ID")}
    (gate.run / "build-budget.json").write_text(json.dumps(budget, indent=2) + "\n")
    # A single invocation per runner preserves the existing per-build warning gate.
    with resource_samples(gate, lane):
        gate.execute("build", ["./gradlew", "--no-daemon", *options, *tasks], gate.build_timeout_seconds)
    gate.check_warnings()
    if lane == "instrumentation":
        outputs = gate.apks()
    else:
        apk_dir = gate.build / "outputs/apk/debug"
        metadata = json.loads((apk_dir / "output-metadata.json").read_text())
        element, = metadata["elements"]
        name = element["outputFile"]
        if metadata["applicationId"] != "com.dayforge" or Path(name).name != name or not name.endswith(".apk"):
            raise ValueError("Unexpected debug APK metadata")
        outputs = [apk_dir / name, gate.build / "reports/lint-results-debug.xml"]
    manifest = {str(p.relative_to(gate.build)): digest(p) for p in outputs}
    (gate.run / "outputs.json").write_text(json.dumps(manifest, indent=2) + "\n")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("lane", choices=TASKS)
    args = p.parse_args()
    with verification_lock():
        build(Gate(Path(__file__).resolve().parents[2]), args.lane)


if __name__ == "__main__":
    def interrupt(_signum, _frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, interrupt)
    main()
