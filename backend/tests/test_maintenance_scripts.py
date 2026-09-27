"""Shell argv/control flow with an isolated executable, never a Docker daemon.

This batch is exercised in hosted CI per the local no-Docker acceptance waiver.
The stub records calls and injects exit/signal boundaries; real container recovery
is separately required on both native CI architectures.
"""

import json
import os
from pathlib import Path
import subprocess
import sys

import pytest


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
BACKUP = "dayforge-20260927T010000Z-0123456789abcdef0123456789abcdef.db"


@pytest.fixture
def invoke(tmp_path):
    binary = tmp_path / "docker"
    log = tmp_path / "calls.jsonl"
    binary.write_text(
        f"#!{sys.executable}\n"
        "import json, os, signal, sys\n"
        "args = sys.argv[1:]\n"
        "with open(os.environ['FAKE_CALLS'], 'a') as output:\n"
        "    output.write(json.dumps(args) + '\\n')\n"
        "stage = next((name for name in ('stop', 'up', 'sqlite-backup', 'sqlite-restore', 'sqlite-verify') if name in args), 'unknown')\n"
        "if os.environ.get('FAKE_SIGNAL') == stage:\n"
        "    os.kill(os.getppid(), signal.SIGTERM)\n"
        "sys.exit(42 if os.environ.get('FAKE_FAILURE') == stage else 0)\n"
    )
    binary.chmod(0o700)

    def run(script, *args, failure="", terminate=""):
        environment = dict(
            os.environ,
            PATH=str(tmp_path) + os.pathsep + os.environ.get("PATH", ""),
            FAKE_CALLS=str(log),
            FAKE_FAILURE=failure,
            FAKE_SIGNAL=terminate,
        )
        result = subprocess.run(
            ["/bin/sh", str(SCRIPTS / script), *args],
            cwd=tmp_path,
            env=environment,
            capture_output=True,
            text=True,
            timeout=10,
        )
        calls = (
            [json.loads(line) for line in log.read_text().splitlines()]
            if log.exists()
            else []
        )
        return result, calls

    return run


@pytest.mark.parametrize(
    "root", [None, "/app/data/assets", "/app/data/icon space;$(printf untouched)"]
)
def test_backup_passes_root_as_one_argument_and_reports_only_success(invoke, root):
    args = [] if root is None else ["--asset-root", root]
    result, calls = invoke("backup-sqlite.sh", *args)
    assert result.returncode == 0, result.stderr
    assert calls == [
        [
            "compose",
            "exec",
            "-T",
            "backend",
            "python",
            "-m",
            "src.storage.cli",
            "sqlite-backup",
            "--database",
            "/app/data/dayforge-v2.db",
            "--backup-dir",
            "/app/data/backups",
            *args,
        ]
    ]
    assert "Backup created" in result.stdout


@pytest.mark.parametrize(
    "args",
    [
        ["--asset-root"],
        ["--asset-root", "/"],
        ["--asset-root", "relative"],
        ["--asset-root", ""],
        ["ignored"],
        ["--asset-root", "/valid", "extra"],
    ],
)
def test_backup_bad_arguments_do_not_launch_any_process(invoke, args):
    result, calls = invoke("backup-sqlite.sh", *args)
    assert result.returncode == 2 and not calls


def test_failed_backup_does_not_report_success(invoke):
    result, calls = invoke("backup-sqlite.sh", failure="sqlite-backup")
    assert result.returncode == 42 and len(calls) == 1
    assert "Backup created" not in result.stdout


@pytest.mark.parametrize(
    "options",
    [
        [],
        ["--cancel-active-timers"],
        ["--asset-root", "/app/data/icon space"],
        ["--cancel-active-timers", "--asset-root", "/app/data/assets"],
        ["--asset-root", "/app/data/assets", "--cancel-active-timers"],
    ],
)
def test_restore_stops_restores_verifies_then_requests_start(invoke, options):
    result, calls = invoke("restore-sqlite.sh", BACKUP, *options)
    assert result.returncode == 0, result.stderr
    assert len(calls) == 4 and calls[0] == ["compose", "stop", "backend"]
    assert calls[3] == ["compose", "up", "-d", "backend"]
    expected = [
        "compose",
        "run",
        "--rm",
        "--no-deps",
        "backend",
        "python",
        "-m",
        "src.storage.cli",
        "sqlite-restore",
        "--backup-file",
        "/app/data/backups/" + BACKUP,
        "--database",
        "/app/data/dayforge-v2.db",
    ]
    if "--cancel-active-timers" in options:
        expected.append("--cancel-active-timers")
    root_args = (
        ["--asset-root", options[options.index("--asset-root") + 1]]
        if "--asset-root" in options
        else []
    )
    assert calls[1] == expected + root_args
    assert calls[2] == [
        "compose",
        "run",
        "--rm",
        "--no-deps",
        "backend",
        "python",
        "-m",
        "src.storage.cli",
        "sqlite-verify",
        "--database",
        "/app/data/dayforge-v2.db",
        *root_args,
    ]
    assert "Restore verified" in result.stdout


@pytest.mark.parametrize(
    "failure,count",
    [("stop", 1), ("sqlite-restore", 2), ("sqlite-verify", 3), ("up", 4)],
)
def test_restore_error_never_restarts_or_retries_unconditionally(
    invoke, failure, count
):
    result, calls = invoke(
        "restore-sqlite.sh", BACKUP, "--asset-root", "/app/data/assets", failure=failure
    )
    assert result.returncode != 0 and len(calls) == count
    assert "Restore verified" not in result.stdout
    if failure != "up":
        assert not any("up" in call for call in calls)
    if failure != "stop":
        assert "no automatic restart/retry" in result.stderr


def test_restore_termination_preserves_stop_without_post_restore_actions(invoke):
    result, calls = invoke("restore-sqlite.sh", BACKUP, terminate="sqlite-restore")
    assert result.returncode == 143
    assert len(calls) == 2 and not any(
        "up" in call or "sqlite-verify" in call for call in calls
    )
    assert "no automatic restart/retry" in result.stderr


@pytest.mark.parametrize(
    "args",
    [
        [],
        ["../" + BACKUP],
        ["/app/data/" + BACKUP],
        ["unknown.db"],
        [BACKUP, "unknown"],
        [BACKUP, "--cancel-active-timers", "--cancel-active-timers"],
        [BACKUP, "--asset-root"],
        [BACKUP, "--asset-root", ""],
        [BACKUP, "--asset-root", "/"],
        [BACKUP, "--asset-root", "relative"],
        [BACKUP, "--asset-root", "/valid", "--asset-root", "/other"],
    ],
)
def test_invalid_restore_arguments_never_stop_the_service(invoke, args):
    result, calls = invoke("restore-sqlite.sh", *args)
    assert result.returncode == 2 and not calls
