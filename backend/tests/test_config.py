"""Tests for config module."""
import pytest
from pydantic import ValidationError


pytestmark = pytest.mark.usefixtures("isolated_settings_env")


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

        monkeypatch.setenv("DATABASE_TYPE", "sqlite")
        monkeypatch.setenv("DATABASE_URL", "sqlite+aiosqlite:///./configured.db")
        monkeypatch.setenv("JWT_SECRET_KEY", "test-env-secret")
        monkeypatch.setenv("CORS_ORIGINS", '["http://example.com"]')

        settings = Settings()

        assert settings.DATABASE_TYPE == "sqlite"
        assert settings.DATABASE_URL == "sqlite+aiosqlite:///./configured.db"
        assert settings.JWT_SECRET_KEY == "test-env-secret"
        assert settings.CORS_ORIGINS == ["http://example.com"]

    def test_settings_jwt_secret_warning(self, monkeypatch):
        """Test that warning is raised when using default JWT secret."""
        from src.config import Settings, DEFAULT_JWT_SECRET

        monkeypatch.delenv("JWT_SECRET_KEY")
        with pytest.warns(UserWarning, match="^WARNING: Using default JWT_SECRET_KEY\\.") as captured:
            settings = Settings()
        assert len(captured) == 1
        assert settings.JWT_SECRET_KEY == DEFAULT_JWT_SECRET


class TestGetDatabaseUrl:
    """Test get_database_url function."""

    def test_returns_sqlite_url_when_database_type_is_sqlite(self, monkeypatch):
        """Test get_database_url returns SQLite URL when DATABASE_TYPE=sqlite."""
        from src.config import get_database_url

        monkeypatch.setenv("DATABASE_TYPE", "sqlite")
        monkeypatch.setenv("SQLITE_DB_PATH", "./test.db")

        url = get_database_url()

        assert url == "sqlite+aiosqlite:///./test.db"

    def test_rejects_postgresql_database_type(self, monkeypatch):
        """Do not advertise a backend whose driver and behavior are not shipped."""
        from src.config import get_database_url

        monkeypatch.setenv("DATABASE_TYPE", "postgresql")

        with pytest.raises(ValidationError, match="DATABASE_TYPE must be sqlite"):
            get_database_url()

    @pytest.mark.parametrize(
        "database_url",
        [
            "postgresql+asyncpg://user:password@localhost/dayforge",
            "sqlite:///./missing-async-driver.db",
            "sqlite+aiosqlite://",
            "sqlite+aiosqlite://user@host/dayforge.db",
            "not-a-database-url",
        ],
    )
    def test_rejects_unsupported_or_malformed_database_urls(self, database_url):
        from src.config import Settings

        with pytest.raises(ValidationError, match="DATABASE_URL"):
            Settings(DATABASE_URL=database_url)

    def test_rejects_empty_sqlite_path_without_url(self):
        from src.config import Settings

        with pytest.raises(ValidationError, match="SQLITE_DB_PATH"):
            Settings(SQLITE_DB_PATH="")

    def test_explicit_sqlite_async_url_is_preserved(self, monkeypatch):
        from src.config import get_database_url

        monkeypatch.setenv("DATABASE_URL", "sqlite+aiosqlite:///./custom.db")

        assert get_database_url() == "sqlite+aiosqlite:///./custom.db"

    def test_sqlite_url_default_path(self):
        """Test SQLite URL uses default path when not specified."""
        from src.config import get_database_url

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
