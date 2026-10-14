"""Real, owned temporary FastAPI peer for the explicitly discovered joint class."""

import ast
import contextlib
import json
import os
import secrets
import signal
import subprocess
import time
from urllib.request import urlopen
from uuid import UUID


JOINT_CLASS = "com.dayforge.data.repository.RealBackendJointTest"
JOINT_MATERIAL_CLASS = "com.dayforge.data.repository.RealBackendMaterialJointTest"
JOINT_SCHEDULER_CLASS = "com.dayforge.data.repository.RealBackendSchedulerJointTest"
JOINT_RECOVERY_CLASS = (
    "com.dayforge.data.repository.RealBackendReplicaRecoveryJointTest"
)


def fixture_environment(repo):
    # Read field names without importing/constructing production Settings. An
    # import here could load a developer's .env or fail on deployment flags.
    backend = repo / "backend"
    tree = ast.parse((backend / "src/config.py").read_text())
    settings = next(
        item
        for item in tree.body
        if isinstance(item, ast.ClassDef) and item.name == "Settings"
    )
    names = {
        item.target.id.casefold()
        for item in settings.body
        if isinstance(item, ast.AnnAssign) and isinstance(item.target, ast.Name)
    }
    if not {"database_url", "jwt_secret_key", "environment"} <= names:
        raise ValueError("Cannot isolate backend settings for joint fixture")
    environment = {
        name: value
        for name, value in os.environ.items()
        if name.casefold() not in names
        and name.casefold() != "web_concurrency"
        and name.upper() != "UV_ENV_FILE"
        and not name.upper().startswith("UVICORN_")
    }
    environment.update(
        PYTHONPATH=str(backend),
        ENVIRONMENT="development",
        JWT_SECRET_KEY=secrets.token_urlsafe(48),
        UV_PROJECT_ENVIRONMENT=str(backend / ".venv"),
    )
    return environment


@contextlib.contextmanager
def _ordinary_backend_for_cases(gate, expected):
    if not any(
        name in {JOINT_CLASS, JOINT_MATERIAL_CLASS, JOINT_SCHEDULER_CLASS}
        for name, _ in expected
    ):
        yield []
        return
    directory = gate.run / "joint-backend"
    log = gate.run / "joint-backend-process.log"
    with log.open("x") as output:
        process = subprocess.Popen(
            [
                "uv",
                "run",
                "--frozen",
                "--no-env-file",
                "--project",
                str(gate.repo / "backend"),
                "python",
                "-m",
                "tests.android_joint_server",
                "--directory",
                str(directory),
            ],
            cwd=gate.run,
            env=fixture_environment(gate.repo),
            stdout=output,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        port = None
        reversed_port = False
        completed = False
        try:
            deadline = time.monotonic() + 45
            while not (directory / "ready.json").exists():
                if process.poll() is not None:
                    raise ValueError(
                        f"Joint backend exited before readiness; see {log}"
                    )
                if time.monotonic() >= deadline:
                    raise ValueError(f"Joint backend readiness timeout; see {log}")
                time.sleep(0.05)
            ready = json.loads((directory / "ready.json").read_text())
            url = ready["url"]
            prefix = "http://127.0.0.1:"
            if not isinstance(url, str) or not url.startswith(prefix):
                raise ValueError("Joint backend must be the owned loopback endpoint")
            port = int(url.removeprefix(prefix))
            if not 1024 <= port <= 65535 or url != prefix + str(port):
                raise ValueError("Invalid joint backend port")
            with urlopen(url + "/api/v2/system/identity", timeout=5) as response:
                identity = json.load(response)
                if response.status != 200 or identity["protocol_version"] != 5:
                    raise ValueError("Joint backend identity is not actual v5")
            # Never replace another reverse mapping or remove-all on the phone.
            gate.adb_command(
                "joint-reverse", "reverse", "--no-rebind", f"tcp:{port}", f"tcp:{port}"
            )
            reversed_port = True
            print(
                "Owned real FastAPI backend ready for isolated physical joint tests.",
                flush=True,
            )
            yield [
                f"-Pandroid.testInstrumentationRunnerArguments.dayforgeJointPort={port}"
            ]
            completed = True
        finally:
            try:
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=10)
                    raise ValueError(
                        f"Joint backend did not shut down normally; see {log}"
                    )
            finally:
                if reversed_port:
                    gate.adb_command(
                        "joint-reverse-remove", "reverse", "--remove", f"tcp:{port}"
                    )
        if process.returncode != 0:
            raise ValueError(f"Joint backend failed during shutdown; see {log}")
        if completed:
            proof = json.loads((directory / "complete.json").read_text())
            if (
                proof["valid"] is not True
                or proof["active_timer_count"] != 0
                or proof["protocol_version"] != 5
            ):
                raise ValueError("Joint backend terminal database proof is missing")
            gate.evidence["joint_backend"] = proof


def _loopback_port(url):
    prefix = "http://127.0.0.1:"
    if not isinstance(url, str) or not url.startswith(prefix):
        raise ValueError("Recovery peer must be the owned loopback endpoint")
    port = int(url.removeprefix(prefix))
    if not 1024 <= port <= 65535 or url != prefix + str(port):
        raise ValueError("Invalid recovery peer port")
    return port


def _recovery_identity(ready):
    for key in (
        "installation_id",
        "account_id",
        "server_instance_id",
        "device_id",
        "source_epoch",
        "restored_epoch",
    ):
        value = ready[key]
        if not isinstance(value, str) or str(UUID(value)) != value:
            raise ValueError("Recovery peer identity must be a canonical UUID")
    if ready["source_epoch"] == ready["restored_epoch"]:
        raise ValueError("Recovery peer did not rotate the restored epoch")
    for flag in ("same_signing_key", "old_jwt_rejected", "wrong_owner_denied"):
        if ready[flag] is not True:
            raise ValueError("Recovery peer setup proof is incomplete")


@contextlib.contextmanager
def _recovery_backend_for_cases(gate, expected):
    if not any(name == JOINT_RECOVERY_CLASS for name, _ in expected):
        yield []
        return
    directory = gate.run / "recovery-backend"
    log = gate.run / "recovery-backend-process.log"
    with log.open("x") as output:
        process = subprocess.Popen(
            [
                "uv",
                "run",
                "--frozen",
                "--no-env-file",
                "--project",
                str(gate.repo / "backend"),
                "python",
                "-m",
                "tests.android_replica_recovery_server",
                "--directory",
                str(directory),
            ],
            cwd=gate.run,
            env=fixture_environment(gate.repo),
            stdout=output,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        owned_ports = []
        completed = False
        try:
            deadline = time.monotonic() + 90
            while not (directory / "ready.json").exists():
                if process.poll() is not None:
                    raise ValueError(
                        f"Recovery backend exited before readiness; see {log}"
                    )
                if time.monotonic() >= deadline:
                    raise ValueError(f"Recovery backend readiness timeout; see {log}")
                time.sleep(0.05)
            ready = json.loads((directory / "ready.json").read_text())
            _recovery_identity(ready)
            ports = [
                _loopback_port(ready[name + "_url"]) for name in ("source", "restored")
            ]
            if ports[0] == ports[1]:
                raise ValueError("Recovery peers require distinct owned ports")
            for name, port in zip(("source", "restored"), ports):
                with urlopen(
                    ready[name + "_url"] + "/api/v2/system/identity", timeout=5
                ) as response:
                    identity = json.load(response)
                    if (
                        response.status != 200
                        or identity["protocol_version"] != 5
                        or identity["server_instance_id"] != ready["server_instance_id"]
                        or identity["sync_epoch"] != ready[name + "_epoch"]
                    ):
                        raise ValueError("Recovery peer HTTP identity mismatch")
                gate.adb_command(
                    "recovery-reverse-" + name,
                    "reverse",
                    "--no-rebind",
                    f"tcp:{port}",
                    f"tcp:{port}",
                )
                owned_ports.append((name, port))
            print(
                "Owned stopped-backup/restore FastAPI peers ready for physical recovery tests.",
                flush=True,
            )
            yield [
                f"-Pandroid.testInstrumentationRunnerArguments.dayforgeRecoverySourcePort={ports[0]}",
                f"-Pandroid.testInstrumentationRunnerArguments.dayforgeRecoveryRestoredPort={ports[1]}",
                "-Pandroid.testInstrumentationRunnerArguments.dayforgeRecoveryInstallationId="
                + ready["installation_id"],
            ]
            completed = True
        finally:
            try:
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=10)
                    raise ValueError(
                        f"Recovery backend did not shut down normally; see {log}"
                    )
            finally:
                failures = []
                for name, port in reversed(owned_ports):
                    try:
                        gate.adb_command(
                            "recovery-reverse-remove-" + name,
                            "reverse",
                            "--remove",
                            f"tcp:{port}",
                        )
                    except Exception as error:
                        failures.append(error)
                if failures:
                    raise ValueError("Recovery reverse cleanup failed") from failures[0]
        if process.returncode != 0:
            raise ValueError(f"Recovery backend failed during shutdown; see {log}")
        if completed:
            proof = json.loads((directory / "complete.json").read_text())
            if (
                any(proof.get(key) != value for key, value in ready.items())
                or proof.get("backup_stopped") is not True
            ):
                raise ValueError("Recovery terminal identity proof is missing")
            for name in ("source", "restored"):
                inspected = proof[name]
                if (
                    inspected["valid"] is not True
                    or inspected["active_timer_count"] != 0
                    or inspected["protocol_version"] != 5
                    or inspected["server_instance_id"] != ready["server_instance_id"]
                    or inspected["sync_epoch"] != ready[name + "_epoch"]
                    or inspected["device_id"] != ready["device_id"]
                    or inspected["post_recovery_metric_count"]
                    != (1 if name == "restored" else 0)
                    or inspected["post_recovery_metric_name"]
                    != ("Recovery new metric" if name == "restored" else None)
                ):
                    raise ValueError("Recovery terminal database proof is missing")
            gate.evidence["recovery_backend"] = proof


@contextlib.contextmanager
def backend_for_cases(gate, expected):
    with contextlib.ExitStack() as stack:
        ordinary = stack.enter_context(_ordinary_backend_for_cases(gate, expected))
        recovery = stack.enter_context(_recovery_backend_for_cases(gate, expected))
        yield ordinary + recovery
