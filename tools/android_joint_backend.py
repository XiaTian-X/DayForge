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


JOINT_CLASS = "com.dayforge.data.repository.RealBackendJointTest"
JOINT_MATERIAL_CLASS = "com.dayforge.data.repository.RealBackendMaterialJointTest"


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
def backend_for_cases(gate, expected):
    if not any(name in {JOINT_CLASS, JOINT_MATERIAL_CLASS} for name, _ in expected):
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
