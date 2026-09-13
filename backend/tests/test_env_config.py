"""Tests for environment variable configuration loading."""
import re
import warnings

import pytest
from pydantic import ValidationError

from src.config import Settings, DEFAULT_JWT_SECRET


DEFAULT_SECRET_WARNING = (
    "WARNING: Using default JWT_SECRET_KEY. "
    "This is allowed for local development only."
)
# Deliberately synthetic: enough bytes for HMAC tests, not a deployment secret.
EXPLICIT_TEST_SECRET = "test" * 8


pytestmark = pytest.mark.usefixtures("isolated_settings_env")


def test_settings_loads_defaults(monkeypatch):
    """Settings loads defaults without inherited environment or a local .env."""
    monkeypatch.delenv("JWT_SECRET_KEY")
    with pytest.warns(UserWarning, match=f"^{re.escape(DEFAULT_SECRET_WARNING)}$") as captured:
        settings = Settings()

    assert len(captured) == 1
    assert settings.JWT_SECRET_KEY == DEFAULT_JWT_SECRET
    assert settings.DATABASE_TYPE == "sqlite"
    assert settings.SQLITE_DB_PATH == "./dev.db"
    assert settings.JWT_ALGORITHM == "HS256"
    assert settings.ACCESS_TOKEN_EXPIRE_MINUTES == 30
    assert settings.REFRESH_TOKEN_EXPIRE_DAYS == 30
    assert "http://localhost:3000" in settings.CORS_ORIGINS


def test_settings_override_with_env(monkeypatch):
    monkeypatch.setenv("JWT_SECRET_KEY", EXPLICIT_TEST_SECRET)
    monkeypatch.setenv("DATABASE_TYPE", "sqlite")
    monkeypatch.setenv("SQLITE_DB_PATH", "./custom-env.db")
    monkeypatch.setenv("CORS_ORIGINS", '["https://example.com"]')

    settings = Settings()

    assert settings.JWT_SECRET_KEY == EXPLICIT_TEST_SECRET
    assert settings.DATABASE_TYPE == "sqlite"
    assert settings.SQLITE_DB_PATH == "./custom-env.db"
    assert settings.CORS_ORIGINS == ["https://example.com"]


def test_settings_dotenv_is_opt_in_for_isolated_tests(monkeypatch, tmp_path):
    """Ignore ambient .env files while still exercising explicit dotenv loading."""
    dotenv = tmp_path / ".env"
    dotenv.write_text(
        f"JWT_SECRET_KEY={EXPLICIT_TEST_SECRET}\nSQLITE_DB_PATH=./dotenv-test.db\n",
        encoding="utf-8",
    )
    monkeypatch.chdir(tmp_path)
    assert Settings().SQLITE_DB_PATH == "./dev.db"

    monkeypatch.delenv("JWT_SECRET_KEY")
    with warnings.catch_warnings(record=True) as captured:
        warnings.simplefilter("always")
        settings = Settings(_env_file=dotenv)

    assert not captured
    assert settings.JWT_SECRET_KEY == EXPLICIT_TEST_SECRET
    assert settings.SQLITE_DB_PATH == "./dotenv-test.db"


@pytest.mark.parametrize("environment", ["development", "Development"])
@pytest.mark.parametrize("explicit_default", [False, True])
def test_jwt_secret_key_warning(monkeypatch, environment, explicit_default):
    """Missing and explicitly configured development defaults both warn once."""
    monkeypatch.setenv("ENVIRONMENT", environment)
    if explicit_default:
        monkeypatch.setenv("JWT_SECRET_KEY", DEFAULT_JWT_SECRET)
    else:
        monkeypatch.delenv("JWT_SECRET_KEY")

    with pytest.warns(UserWarning, match=f"^{re.escape(DEFAULT_SECRET_WARNING)}$") as captured:
        settings = Settings()

    assert len(captured) == 1
    assert settings.JWT_SECRET_KEY == DEFAULT_JWT_SECRET


@pytest.mark.parametrize("environment", ["development", "production", "Production"])
def test_explicit_jwt_secret_does_not_warn(monkeypatch, environment):
    monkeypatch.setenv("ENVIRONMENT", environment)
    monkeypatch.setenv("JWT_SECRET_KEY", EXPLICIT_TEST_SECRET)
    with warnings.catch_warnings(record=True) as captured:
        warnings.simplefilter("always")
        settings = Settings()

    assert not captured
    assert settings.JWT_SECRET_KEY == EXPLICIT_TEST_SECRET


def test_cors_origins_default():
    settings = Settings()

    assert len(settings.CORS_ORIGINS) > 0
    assert any("localhost" in origin for origin in settings.CORS_ORIGINS)
    assert "http://localhost:3000" in settings.CORS_ORIGINS


@pytest.mark.parametrize("environment", ["production", "Production"])
@pytest.mark.parametrize("explicit_default", [False, True])
def test_production_rejects_default_jwt_secret(monkeypatch, environment, explicit_default):
    monkeypatch.setenv("ENVIRONMENT", environment)
    if explicit_default:
        monkeypatch.setenv("JWT_SECRET_KEY", DEFAULT_JWT_SECRET)
    else:
        monkeypatch.delenv("JWT_SECRET_KEY")
    with pytest.raises(ValidationError, match="JWT_SECRET_KEY"):
        Settings()


def test_admin_credentials_must_be_complete_and_strong():
    with pytest.raises(ValidationError, match="configured together"):
        Settings(ADMIN_USERNAME="admin", ADMIN_PASSWORD=None)
    with pytest.raises(ValidationError, match="at least 12"):
        Settings(ADMIN_USERNAME="admin", ADMIN_PASSWORD="short")


def test_production_accepts_explicit_security_configuration():
    settings = Settings(
        ENVIRONMENT="production",
        JWT_SECRET_KEY=EXPLICIT_TEST_SECRET,
        ADMIN_USERNAME="dayforge-admin",
        ADMIN_PASSWORD="a-strong-admin-password",
    )
    assert settings.ENVIRONMENT == "production"
