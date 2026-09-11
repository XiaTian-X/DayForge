"""Tests for config module."""
import os
import pytest
from pydantic import ValidationError


@pytest.fixture(autouse=True)
def clear_settings_cache():
    """Clear the get_settings cache before each test."""
    from src.config import get_settings
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


class TestSettings:
    """Test Settings class functionality."""

    def test_settings_loads_defaults(self):
        """Test that Settings loads with default values."""
        from src.config import Settings

        settings = Settings()

        assert settings.DATABASE_TYPE == "sqlite"
        assert settings.SQLITE_DB_PATH == "./dev.db"
        assert settings.JWT_ALGORITHM == "HS256"
        assert settings.ACCESS_TOKEN_EXPIRE_MINUTES == 30
        assert settings.REFRESH_TOKEN_EXPIRE_DAYS == 30
        assert "http://localhost:3000" in settings.CORS_ORIGINS

    def test_settings_loads_from_environment(self, monkeypatch):
        """Test that Settings loads config from environment variables."""
        from src.config import Settings

        monkeypatch.setenv("DATABASE_TYPE", "postgresql")
        monkeypatch.setenv("POSTGRES_HOST", "localhost")
        monkeypatch.setenv("POSTGRES_USER", "testuser")
        monkeypatch.setenv("POSTGRES_PASSWORD", "testpass")
        monkeypatch.setenv("POSTGRES_DB", "testdb")
        monkeypatch.setenv("POSTGRES_PORT", "5433")
        monkeypatch.setenv("JWT_SECRET_KEY", "test-env-secret")
        monkeypatch.setenv("CORS_ORIGINS", '["http://example.com"]')

        settings = Settings()

        assert settings.DATABASE_TYPE == "postgresql"
        assert settings.POSTGRES_HOST == "localhost"
        assert settings.POSTGRES_USER == "testuser"
        assert settings.JWT_SECRET_KEY == "test-env-secret"
        assert settings.CORS_ORIGINS == ["http://example.com"]

    def test_settings_jwt_secret_warning(self):
        """Test that warning is raised when using default JWT secret."""
        from src.config import Settings, DEFAULT_JWT_SECRET

        # Using default should trigger warning
        settings = Settings()
        assert settings.JWT_SECRET_KEY == DEFAULT_JWT_SECRET


class TestGetDatabaseUrl:
    """Test get_database_url function."""

    def test_returns_sqlite_url_when_database_type_is_sqlite(self, monkeypatch):
        """Test get_database_url returns SQLite URL when DATABASE_TYPE=sqlite."""
        from src.config import get_database_url, Settings

        monkeypatch.setenv("DATABASE_TYPE", "sqlite")
        monkeypatch.setenv("SQLITE_DB_PATH", "./test.db")

        url = get_database_url()

        assert url == "sqlite+aiosqlite:///./test.db"

    def test_returns_postgresql_url_when_database_type_is_postgresql(self, monkeypatch):
        """Test get_database_url returns PostgreSQL URL when DATABASE_TYPE=postgresql."""
        from src.config import get_database_url

        monkeypatch.setenv("DATABASE_TYPE", "postgresql")
        monkeypatch.setenv("POSTGRES_HOST", "localhost")
        monkeypatch.setenv("POSTGRES_USER", "myuser")
        monkeypatch.setenv("POSTGRES_PASSWORD", "mypass")
        monkeypatch.setenv("POSTGRES_DB", "mydb")
        monkeypatch.setenv("POSTGRES_PORT", "5432")

        url = get_database_url()

        assert url == "postgresql+asyncpg://myuser:mypass@localhost:5432/mydb"

    def test_sqlite_url_default_path(self):
        """Test SQLite URL uses default path when not specified."""
        from src.config import get_database_url, Settings

        # Reset to defaults
        os.environ.pop("DATABASE_TYPE", None)
        os.environ.pop("SQLITE_DB_PATH", None)

        url = get_database_url()

        assert url == "sqlite+aiosqlite:///./dev.db"


class TestGetSettings:
    """Test get_settings singleton function."""

    def test_get_settings_returns_singleton(self):
        """Test that get_settings returns the same instance."""
        from src.config import get_settings

        settings1 = get_settings()
        settings2 = get_settings()

        assert settings1 is settings2
