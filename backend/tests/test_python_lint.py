"""The locked lint gate must reject errors without breaking pytest discovery."""

import json
import os
from pathlib import Path
import subprocess
import sys

import pytest


BACKEND = Path(__file__).resolve().parents[1]
REPOSITORY = BACKEND.parent


@pytest.mark.parametrize(
    "source,code",
    [
        ("import os\n", "F401"),
        ("import os\nimport os\nprint(os.name)\n", "F811"),
        ("print(undefined_name)\n", "F821"),
        ("def action():\n    unused = 1\n", "F841"),
        ("import os, sys\nprint(os.name, sys.version)\n", "E401"),
        ("def broken(:\n", "invalid-syntax"),
    ],
)
def test_lint_rejects_invalid_code(source, code):
    result = subprocess.run(
        [
            sys.executable,
            "-m",
            "ruff",
            "check",
            "--config",
            str(BACKEND / "pyproject.toml"),
            "--output-format",
            "json",
            "--stdin-filename",
            "example.py",
            "-",
        ],
        input=source,
        text=True,
        capture_output=True,
        cwd=BACKEND,
        check=False,
        timeout=30,
    )
    assert result.returncode == 1, result.stderr
    assert code in {diagnostic["code"] for diagnostic in json.loads(result.stdout)}


def test_lint_accepts_explicit_pytest_fixture_reexport():
    result = subprocess.run(
        [
            sys.executable,
            "-m",
            "ruff",
            "check",
            "--config",
            str(BACKEND / "pyproject.toml"),
            "--stdin-filename",
            "example.py",
            "-",
        ],
        input="from fixtures import setup as setup\ndef test_example(setup):\n    assert setup\n",
        text=True,
        capture_output=True,
        cwd=BACKEND,
        check=False,
        timeout=30,
    )
    assert result.returncode == 0, result.stdout + result.stderr


@pytest.mark.parametrize(
    "fail_stage", ["sync", "ruff", "format", "pytest", "openapi", "none"]
)
def test_verify_backend_fails_fast_and_never_updates_the_lock(tmp_path, fail_stage):
    """Exercise the real shell entry point with a recording uv, not string matching."""
    command_log = tmp_path / "commands.jsonl"
    launcher = tmp_path / "uv"
    launcher.write_text(
        "#!/usr/bin/env python3\n"
        "import json, os, sys\n"
        "args = sys.argv[1:]\n"
        "with open(os.environ['LINT_TEST_LOG'], 'a') as log:\n"
        "    log.write(json.dumps(args) + '\\n')\n"
        "stage = ('sync' if args[0] == 'sync' else 'format' if 'format' in args else "
        "'ruff' if 'ruff' in args else "
        "'pytest' if 'pytest' in args else 'openapi')\n"
        "sys.exit(1 if stage == os.environ['LINT_TEST_FAILURE'] else 0)\n",
        encoding="utf-8",
    )
    launcher.chmod(0o755)
    result = subprocess.run(
        [str(REPOSITORY / "tools/verify"), "backend"],
        env={
            **os.environ,
            "PATH": str(tmp_path) + os.pathsep + os.environ["PATH"],
            "LINT_TEST_LOG": str(command_log),
            "LINT_TEST_FAILURE": fail_stage,
        },
        cwd=tmp_path,
        text=True,
        capture_output=True,
        check=False,
        timeout=30,
    )
    expected = [
        ["sync", "--frozen"],
        ["run", "--frozen", "ruff", "check", "--config", "pyproject.toml", "."],
        [
            "run",
            "--frozen",
            "ruff",
            "format",
            "--check",
            "--config",
            "pyproject.toml",
            ".",
        ],
        ["run", "--frozen", "python", "-m", "pytest", "-p", "tests.warning_budget"],
        ["run", "--frozen", "python", "scripts/export_openapi.py", "--check"],
    ]
    count = {"sync": 1, "ruff": 2, "format": 3, "pytest": 4, "openapi": 5, "none": 5}[
        fail_stage
    ]
    assert command_log.exists(), result.stdout + result.stderr
    assert [
        json.loads(line) for line in command_log.read_text().splitlines()
    ] == expected[:count]
    assert result.returncode == (0 if fail_stage == "none" else 1), (
        result.stdout + result.stderr
    )
