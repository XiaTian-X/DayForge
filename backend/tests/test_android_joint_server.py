"""Actual host/uv/Uvicorn shutdown, not Android or USB behavior evidence."""

import json
import os
from pathlib import Path
import secrets
import signal
import subprocess
import time

import pytest
from urllib.request import urlopen

from scripts.sync_acceptance_fixture import AcceptanceClient
from src.config import Settings
from tests.account_fixtures import TEST_ACCOUNT_PASSWORD


@pytest.mark.parametrize("stop_signal", [signal.SIGTERM, signal.SIGINT])
def test_owned_joint_host_stops_with_terminal_proof_even_with_repeated_signals(
    tmp_path, stop_signal
):
    backend = Path(__file__).resolve().parents[1]
    names = {name.casefold() for name in Settings.model_fields}
    environment = {
        name: value
        for name, value in os.environ.items()
        if name.casefold() not in names
        and name.upper() != "UV_ENV_FILE"
        and not name.upper().startswith("UVICORN_")
        and name.casefold() != "web_concurrency"
    }
    environment.update(
        PYTHONPATH=str(backend),
        ENVIRONMENT="development",
        JWT_SECRET_KEY=secrets.token_urlsafe(48),
        UV_PROJECT_ENVIRONMENT=str(backend / ".venv"),
    )
    directory = tmp_path / "joint-backend"
    with (tmp_path / "process.log").open("x") as output:
        process = subprocess.Popen(
            [
                "uv",
                "run",
                "--frozen",
                "--no-env-file",
                "--project",
                str(backend),
                "python",
                "-m",
                "tests.android_joint_server",
                "--directory",
                str(directory),
            ],
            cwd=tmp_path,
            env=environment,
            stdout=output,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            deadline = time.monotonic() + 45
            while not (directory / "ready.json").exists():
                assert process.poll() is None
                assert time.monotonic() < deadline
                time.sleep(0.05)
            url = json.loads((directory / "ready.json").read_text())["url"]
            assert url.startswith("http://127.0.0.1:")
            with urlopen(url + "/api/v2/system/identity", timeout=5) as response:
                assert response.status == 200
                assert json.load(response)["protocol_version"] == 5
            client = AcceptanceClient(url)
            logged_in = client.login("joint-core", TEST_ACCOUNT_PASSWORD)
            me = client.request(
                "GET",
                "/api/v1/auth/users/me",
                token=logged_in["access_token"],
            )
            assert me["public_id"] == logged_in["user_id"]
            # Exercise both the group and wrapper relay, as the physical gate
            # does. Do not turn forced SIGKILL into a successful shutdown.
            os.killpg(process.pid, stop_signal)
            if process.poll() is None:
                process.send_signal(stop_signal)
            assert process.wait(timeout=15) == 0
            proof = json.loads((directory / "complete.json").read_text())
            assert proof["valid"] is True
            assert proof["active_timer_count"] == 0
            assert proof["protocol_version"] == 5
            assert proof["row_counts"]["users"] == 9
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=10)
