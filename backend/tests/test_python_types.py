"""Executable contracts for the scoped, locked backend type gate."""

from pathlib import Path
import hashlib
import subprocess
import sys
import tomllib

import pytest


BACKEND = Path(__file__).resolve().parents[1]


@pytest.fixture(scope="module")
def type_cache(tmp_path_factory):
    return tmp_path_factory.mktemp("mypy-cache")


@pytest.mark.parametrize(
    "source,diagnostic",
    [
        ('def value() -> int:\n    return "bad"\n', "return-value"),
        ('def untyped():\n    value: int = "bad"\n', "assignment"),
        ("import missing_dayforge_type_probe\n", "import-not-found"),
        (
            "from src.storage.database_adapter import DatabaseAdapter\ndef update(adapter: DatabaseAdapter) -> None:\n    adapter.async_url = 'changed'\n",
            "read-only",
        ),
        (
            "from src.database import get_engine\nget_engine().missing_dayforge_method()\n",
            "attr-defined",
        ),
        (
            "from src.storage.cli import _current_alembic_head\nhead: str = _current_alembic_head()\n",
            "assignment",
        ),
        (
            "from datetime import datetime\nfrom src.v2.schemas import require_aware_utc\nvalue: datetime = require_aware_utc(None)\n",
            "assignment",
        ),
        (
            "from src.auth.schemas import Token\ndef wrong(token: Token) -> str:\n    return token.user_id\n",
            "return-value",
        ),
        (
            "from src.tokens.schemas import TokenResponse\ndef wrong(token: TokenResponse) -> None:\n    token.id = None\n",
            "assignment",
        ),
    ],
)
def test_type_gate_rejects_invalid_contracts(source, diagnostic, type_cache):
    result = run_mypy(source, type_cache)
    assert result.returncode == 1, result.stdout + result.stderr
    assert diagnostic in result.stdout, result.stdout + result.stderr


def run_mypy(source, cache):
    probe = cache / f"probe_{hashlib.sha256(source.encode()).hexdigest()[:16]}.py"
    probe.write_text(source, encoding="utf-8")
    return subprocess.run(
        [
            sys.executable,
            "-m",
            "mypy",
            "--config-file",
            "pyproject.toml",
            "--cache-dir",
            str(cache / "cache"),
            str(probe),
        ],
        text=True,
        capture_output=True,
        cwd=BACKEND,
        check=False,
        timeout=30,
    )


def test_type_gate_accepts_real_immutable_adapter_and_typed_engine(type_cache):
    source = (
        "from typing import assert_type\n"
        "from sqlalchemy.ext.asyncio import AsyncEngine\n"
        "from src.database import get_engine\n"
        "from src.storage.database_adapter import DatabaseAdapter, SQLiteDatabaseAdapter\n"
        "adapter: DatabaseAdapter = SQLiteDatabaseAdapter('sqlite+aiosqlite:///test.db', 'sqlite:///test.db')\n"
        "assert_type(adapter.async_url, str)\n"
        "assert_type(adapter.create_async_engine(), AsyncEngine)\n"
        "assert_type(get_engine(), AsyncEngine)\n"
    )
    result = run_mypy(source, type_cache)
    assert result.returncode == 0, result.stdout + result.stderr


def test_utc_normalizer_retains_required_and_optional_types(type_cache):
    source = (
        "from datetime import datetime, timezone\n"
        "from typing import assert_type\n"
        "from src.v2.schemas import require_aware_utc\n"
        "assert_type(require_aware_utc(datetime.now(timezone.utc)), datetime)\n"
        "assert_type(require_aware_utc(None), None)\n"
        "def optional(value: datetime | None) -> None:\n"
        "    assert_type(require_aware_utc(value), datetime | None)\n"
    )
    result = run_mypy(source, type_cache)
    assert result.returncode == 0, result.stdout + result.stderr


def test_validated_auth_responses_have_precise_public_types(type_cache):
    source = (
        "from typing import assert_type\n"
        "from uuid import UUID\n"
        "from src.auth.models import User\n"
        "from src.auth.router import _token_response\n"
        "from src.tokens.schemas import TokenResponse\n"
        "def response(user: User, raw: dict[str, object]) -> None:\n"
        "    assert_type(_token_response(user).user_id, UUID)\n"
        "    assert_type(TokenResponse.model_validate(raw).id, int)\n"
    )
    result = run_mypy(source, type_cache)
    assert result.returncode == 0, result.stdout + result.stderr


def test_type_gate_scope_is_explicit_without_error_or_import_suppression():
    config = tomllib.loads((BACKEND / "pyproject.toml").read_text())["tool"]["mypy"]
    assert set(config["files"]) == {
        "src/storage",
        "src/config.py",
        "src/database.py",
        "src/time_utils.py",
        "src/v2/time_utils.py",
        "src/v2/schemas.py",
        "src/v2/encoding.py",
        "src/v2/merge.py",
        "src/v2/errors.py",
        "src/auth",
        "src/tokens/models.py",
        "src/tokens/schemas.py",
        "src/tokens/service.py",
        "src/tokens/router.py",
    }
    assert config["check_untyped_defs"] is True
    assert not config.get("ignore_errors", False)
    assert not config.get("ignore_missing_imports", False)
    assert config.get("follow_imports", "normal") == "normal"
    assert not config.get("disable_error_code", [])
    assert not config.get("overrides", [])
