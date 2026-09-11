"""Tests for environment variable configuration loading."""
import pytest
import os
import warnings
from importlib import reload
from pydantic import ValidationError

from src.config import Settings, get_settings, get_database_url, DEFAULT_JWT_SECRET


@pytest.fixture
def clean_env():
    """Fixture to clean environment variables before each test."""
    # Save original environment
    original_env = os.environ.copy()
    yield
    # Restore original environment
    os.environ.clear()
    os.environ.update(original_env)
    # Reload settings module to pick up environment changes
    reload(__import__('src.config', fromlist=['Settings']))


@pytest.mark.asyncio
async def test_settings_loads_defaults(clean_env):
    """Settings() loads default values when no .env file."""
    # Remove any potential env overrides
    for key in ["DATABASE_TYPE", "DATABASE_URL", "JWT_SECRET_KEY", "CORS_ORIGINS"]:
        os.environ.pop(key, None)

    settings = Settings()

    assert settings.DATABASE_TYPE == "sqlite"
    assert settings.SQLITE_DB_PATH == "./dev.db"
    assert settings.JWT_ALGORITHM == "HS256"
    assert settings.ACCESS_TOKEN_EXPIRE_MINUTES == 30
    assert settings.REFRESH_TOKEN_EXPIRE_DAYS == 30
    assert "http://localhost:3000" in settings.CORS_ORIGINS


@pytest.mark.asyncio
async def test_settings_override_with_env(clean_env):
    """Set env var, create Settings, verify override."""
    # Set environment variable
    os.environ["JWT_SECRET_KEY"] = "custom-secret-key-from-env"
    os.environ["DATABASE_TYPE"] = "postgresql"
    os.environ["CORS_ORIGINS"] = '["https://example.com"]'

    settings = Settings()

    assert settings.JWT_SECRET_KEY == "custom-secret-key-from-env"
    assert settings.DATABASE_TYPE == "postgresql"
    assert settings.CORS_ORIGINS == ["https://example.com"]


@pytest.mark.asyncio
async def test_jwt_secret_key_warning(clean_env):
    """If JWT_SECRET_KEY is default/missing, warning logged."""
    # Ensure default secret is used
    os.environ.pop("JWT_SECRET_KEY", None)

    # Verify warning is raised with default secret
    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter("always")
        settings = Settings()

        # Check warning was raised
        assert len(w) == 1
        assert issubclass(w[-1].category, UserWarning)
        assert "WARNING: Using default JWT_SECRET_KEY" in str(w[-1].message)
        assert settings.JWT_SECRET_KEY == DEFAULT_JWT_SECRET


@pytest.mark.asyncio
async def test_cors_origins_default(clean_env):
    """Default CORS_ORIGINS includes localhost."""
    # Remove any CORS env override
    os.environ.pop("CORS_ORIGINS", None)

    settings = Settings()

    assert len(settings.CORS_ORIGINS) > 0
    assert any("localhost" in origin for origin in settings.CORS_ORIGINS)
    assert "http://localhost:3000" in settings.CORS_ORIGINS


def test_production_rejects_default_jwt_secret(clean_env):
    with pytest.raises(ValidationError, match="JWT_SECRET_KEY"):
        Settings(ENVIRONMENT="production", JWT_SECRET_KEY=DEFAULT_JWT_SECRET)


def test_admin_credentials_must_be_complete_and_strong(clean_env):
    with pytest.raises(ValidationError, match="configured together"):
        Settings(ADMIN_USERNAME="admin", ADMIN_PASSWORD=None)
    with pytest.raises(ValidationError, match="at least 12"):
        Settings(ADMIN_USERNAME="admin", ADMIN_PASSWORD="short")


def test_production_accepts_explicit_security_configuration(clean_env):
    settings = Settings(
        ENVIRONMENT="production",
        JWT_SECRET_KEY="a-production-secret-with-sufficient-entropy",
        ADMIN_USERNAME="dayforge-admin",
        ADMIN_PASSWORD="a-strong-admin-password",
    )
    assert settings.ENVIRONMENT == "production"
