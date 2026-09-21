"""Configuration sources must honor precedence without bypassing validation."""

from typing import Any

import pytest
from pydantic import ValidationError
from pydantic_settings import SettingsError

from src.config import DEFAULT_JWT_SECRET, Settings
from src.storage.database_adapter import build_database_adapter


pytestmark = pytest.mark.usefixtures("isolated_settings_env")


@pytest.mark.parametrize("spelling", [str.upper, str.lower, str.title])
@pytest.mark.parametrize("source", ["init", "env", "dotenv", "default"])
def test_case_insensitive_source_precedence(monkeypatch, tmp_path, spelling, source):
    dotenv = tmp_path / "explicit.env"
    if source != "default":
        dotenv.write_text(
            f"{spelling('SQLITE_DB_PATH')}=./dotenv.sqlite\n"
            f"{spelling('ACCESS_TOKEN_EXPIRE_MINUTES')}=41\n"
            f'{spelling("CORS_ORIGINS")}=["https://dotenv.invalid"]\n',
            encoding="utf-8",
        )
    if source in {"init", "env"}:
        monkeypatch.setenv(spelling("SQLITE_DB_PATH"), "./env.sqlite")
        monkeypatch.setenv(spelling("ACCESS_TOKEN_EXPIRE_MINUTES"), "42")
        monkeypatch.setenv(spelling("CORS_ORIGINS"), '["https://env.invalid"]')
    initial: dict[str, Any] = (
        {
            spelling("SQLITE_DB_PATH"): "./init.sqlite",
            spelling("ACCESS_TOKEN_EXPIRE_MINUTES"): 43,
            spelling("CORS_ORIGINS"): ["https://init.invalid"],
        }
        if source == "init"
        else {}
    )
    monkeypatch.setitem(Settings.model_config, "env_file", dotenv)
    settings = Settings(**initial)
    expected_path, expected_minutes = {
        "init": ("./init.sqlite", 43),
        "env": ("./env.sqlite", 42),
        "dotenv": ("./dotenv.sqlite", 41),
        "default": ("./dev.db", 30),
    }[source]
    assert settings.SQLITE_DB_PATH == expected_path
    assert settings.ACCESS_TOKEN_EXPIRE_MINUTES == expected_minutes
    if source == "default":
        assert "http://localhost:3000" in settings.CORS_ORIGINS
    else:
        assert settings.CORS_ORIGINS == [f"https://{source}.invalid"]


@pytest.mark.parametrize("spelling", [str.upper, str.lower, str.title])
@pytest.mark.parametrize(
    "initial,message",
    [
        (
            {"ENVIRONMENT": "production", "JWT_SECRET_KEY": DEFAULT_JWT_SECRET},
            "JWT_SECRET_KEY must be explicitly configured",
        ),
        ({"ADMIN_USERNAME": "synthetic-admin"}, "configured together"),
        (
            {"ADMIN_USERNAME": "synthetic-admin", "ADMIN_PASSWORD": "short"},
            "at least 12",
        ),
        ({"DATABASE_URL": "postgresql://unsupported.invalid/db"}, "DATABASE_URL"),
    ],
)
def test_constructor_case_cannot_bypass_safety_checks(spelling, initial, message):
    with pytest.raises(ValidationError, match=message):
        Settings(**{spelling(name): value for name, value in initial.items()})


@pytest.mark.parametrize("spelling", [str.upper, str.lower, str.title])
def test_constructor_database_url_overrides_path(spelling):
    expected = "sqlite+aiosqlite:///./chosen.sqlite"
    initial: dict[str, Any] = {
        spelling("DATABASE_URL"): expected,
        spelling("SQLITE_DB_PATH"): "./not-selected.sqlite",
    }
    settings = Settings(**initial)
    adapter = build_database_adapter(
        settings.DATABASE_TYPE, settings.DATABASE_URL, settings.SQLITE_DB_PATH
    )
    assert adapter.async_url == expected


def test_invalid_environment_json_is_not_silently_replaced_by_defaults(monkeypatch):
    monkeypatch.setenv("CORS_ORIGINS", "not-json")
    with pytest.raises(SettingsError, match="CORS_ORIGINS"):
        Settings()
