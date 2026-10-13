"""Actual host/uv/Uvicorn shutdown, not Android or USB behavior evidence."""

import asyncio
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
from tests.android_replica_recovery_server import USERNAME
from tests.test_android_replica_recovery_server import write_new_metric


@pytest.mark.parametrize("stop_signal", [signal.SIGTERM, signal.SIGINT])
@pytest.mark.parametrize(
    "host", ["android_joint_server", "android_replica_recovery_server"]
)
def test_owned_joint_host_stops_with_terminal_proof_even_with_repeated_signals(
    tmp_path, stop_signal, host
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
                "-c",
                "import os, runpy; "
                f"runpy.run_module('tests.{host}', run_name='__main__'); "
                # Reproduce a wrapper relay arriving AFTER serve has returned but
                # before Python exits. The CLI must still finish normally, not
                # restore SIGINT's default KeyboardInterrupt in this window.
                f"os.kill(os.getpid(), {int(stop_signal)})",
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
            ready = json.loads((directory / "ready.json").read_text())
            recovery = host == "android_replica_recovery_server"
            url = ready["restored_url" if recovery else "url"]
            assert url.startswith("http://127.0.0.1:")
            with urlopen(url + "/api/v2/system/identity", timeout=5) as response:
                assert response.status == 200
                assert json.load(response)["protocol_version"] == 5
            client = AcceptanceClient(url)
            logged_in = client.login(
                USERNAME if recovery else "joint-core", TEST_ACCOUNT_PASSWORD
            )
            me = client.request(
                "GET",
                "/api/v1/auth/users/me",
                token=logged_in["access_token"],
            )
            assert me["public_id"] == logged_in["user_id"]
            if recovery:
                asyncio.run(write_new_metric(ready))
            # Exercise both the group and wrapper relay, as the physical gate
            # does. Do not turn forced SIGKILL into a successful shutdown.
            os.killpg(process.pid, stop_signal)
            if process.poll() is None:
                process.send_signal(stop_signal)
            assert process.wait(timeout=15) == 0
            proof = json.loads((directory / "complete.json").read_text())
            if recovery:
                assert proof["backup_stopped"] is True
                assert proof["old_jwt_rejected"] and proof["wrong_owner_denied"]
                for name in ("source", "restored"):
                    assert proof[name]["valid"] is True
                    assert proof[name]["active_timer_count"] == 0
                    assert proof[name]["protocol_version"] == 5
                    assert proof[name]["sync_epoch"] == ready[name + "_epoch"]
                assert proof["source"]["post_recovery_metric_count"] == 0
                assert proof["restored"]["post_recovery_metric_count"] == 1
            else:
                assert proof["valid"] is True
                assert proof["active_timer_count"] == 0
                assert proof["protocol_version"] == 5
                assert proof["row_counts"]["users"] == 9
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=10)
