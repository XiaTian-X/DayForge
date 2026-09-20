"""The locked formatter checks without writes and formats without semantic drift."""

import ast
from pathlib import Path
import subprocess
import sys

import pytest


BACKEND = Path(__file__).resolve().parents[1]


def run_formatter(path, *options):
    return subprocess.run(
        [
            sys.executable,
            "-m",
            "ruff",
            "format",
            "--config",
            str(BACKEND / "pyproject.toml"),
            *options,
            str(path),
        ],
        text=True,
        capture_output=True,
        cwd=path.parent,
        check=False,
        timeout=30,
    )


@pytest.mark.parametrize(
    "source,exit_code",
    [("value = 1\n", 0), ("value=1\n", 1), ("def broken(:\n", 2)],
)
def test_format_check_is_read_only_and_rejects_drift_or_invalid_syntax(
    tmp_path, source, exit_code
):
    path = tmp_path / "example.py"
    path.write_text(source, encoding="utf-8")
    original = path.read_bytes()

    result = run_formatter(path, "--check")

    assert result.returncode == exit_code, result.stdout + result.stderr
    assert path.read_bytes() == original


def test_formatting_is_idempotent_and_preserves_ast(tmp_path):
    path = tmp_path / "example.py"
    source = (
        "def describe(name):\n"
        "    '''Keep the docstring and string values unchanged.'''\n"
        "    data={'label': f'用户 {name}', 'sql': 'SELECT * FROM users WHERE id = :id'}\n"
        "    return data\n"
    )
    path.write_text(source, encoding="utf-8")

    first = run_formatter(path)
    assert first.returncode == 0, first.stdout + first.stderr
    formatted = path.read_bytes()
    assert formatted != source.encode("utf-8")
    assert ast.dump(ast.parse(formatted), include_attributes=False) == ast.dump(
        ast.parse(source), include_attributes=False
    )
    second = run_formatter(path)
    assert second.returncode == 0, second.stdout + second.stderr
    assert path.read_bytes() == formatted
    check = run_formatter(path, "--check")
    assert check.returncode == 0, check.stdout + check.stderr


def test_source_formatting_does_not_rewrite_markdown_examples(tmp_path):
    source = tmp_path / "example.py"
    source.write_text("value=1\n", encoding="utf-8")
    documentation = tmp_path / "example.md"
    original = b"```python\nvalue=1\n```\n"
    documentation.write_bytes(original)

    result = run_formatter(tmp_path)

    assert result.returncode == 0, result.stdout + result.stderr
    assert source.read_text(encoding="utf-8") == "value = 1\n"
    assert documentation.read_bytes() == original
