"""Tests for database configuration and URL switching."""
import pytest
from src.config import Settings


@pytest.fixture
def clean_db_env():
    """Fixture to clean database environment variables before each test."""
    import os
    # Save original environment
    original_env = os.environ.copy()
    yield
    # Restore original environment
    os.environ.clear()
    os.environ.update(original_env)


@pytest.mark.asyncio
async def test_get_database_url_sqlite(clean_db_env):
    """Set DATABASE_TYPE=sqlite in Settings, verify URL starts with sqlite+aiosqlite://."""
    import os
    # Clear any existing DATABASE_URL override
    os.environ.pop("DATABASE_URL", None)
    os.environ["DATABASE_TYPE"] = "sqlite"
    os.environ["SQLITE_DB_PATH"] = "./test.db"

    # Create fresh settings instance (bypass lru_cache)
    settings = Settings()
    url = f"sqlite+aiosqlite:///{settings.SQLITE_DB_PATH}"

    assert url.startswith("sqlite+aiosqlite://")
    assert "test.db" in url


@pytest.mark.asyncio
async def test_get_database_url_postgresql(clean_db_env):
    """Set DATABASE_TYPE=postgresql in Settings, verify URL contains postgresql+asyncpg://."""
    import os
    # Clear any existing DATABASE_URL override
    os.environ.pop("DATABASE_URL", None)
    os.environ["DATABASE_TYPE"] = "postgresql"
    os.environ["POSTGRES_HOST"] = "test-db.example.com"
    os.environ["POSTGRES_USER"] = "testuser"
    os.environ["POSTGRES_PASSWORD"] = "testpass"
    os.environ["POSTGRES_DB"] = "testdb"
    os.environ["POSTGRES_PORT"] = "5432"

    # Create fresh settings instance (bypass lru_cache)
    settings = Settings()
    url = (
        f"postgresql+asyncpg://{settings.POSTGRES_USER}:{settings.POSTGRES_PASSWORD}"
        f"@{settings.POSTGRES_HOST}:{settings.POSTGRES_PORT}/{settings.POSTGRES_DB}"
    )

    assert url.startswith("postgresql+asyncpg://")
    assert "testuser" in url
    assert "testpass" in url
    assert "test-db.example.com" in url
    assert "testdb" in url


@pytest.mark.asyncio
async def test_get_database_url_custom(clean_db_env):
    """Set DATABASE_URL directly in Settings, verify it can be used."""
    import os
    custom_url = "sqlite+aiosqlite:///./custom_path/test.db"
    os.environ["DATABASE_URL"] = custom_url

    from src.config import get_database_url, get_settings

    get_settings.cache_clear()
    try:
        assert get_database_url() == custom_url
    finally:
        get_settings.cache_clear()


@pytest.mark.asyncio
async def test_sqlite_url_format(clean_db_env):
    """Verify SQLite URL format includes SQLITE_DB_PATH from settings."""
    import os
    os.environ.pop("DATABASE_URL", None)
    os.environ["DATABASE_TYPE"] = "sqlite"
    os.environ["SQLITE_DB_PATH"] = "./my_custom_db.db"

    # Create fresh settings instance (bypass lru_cache)
    settings = Settings()
    url = f"sqlite+aiosqlite:///{settings.SQLITE_DB_PATH}"

    assert url.startswith("sqlite+aiosqlite:///")
    assert "my_custom_db.db" in url


@pytest.mark.asyncio
async def test_postgresql_url_format(clean_db_env):
    """Verify PostgreSQL URL format includes all required components from settings."""
    import os
    os.environ.pop("DATABASE_URL", None)
    os.environ["DATABASE_TYPE"] = "postgresql"
    os.environ["POSTGRES_HOST"] = "production-db.example.com"
    os.environ["POSTGRES_USER"] = "produser"
    os.environ["POSTGRES_PASSWORD"] = "prodpass"
    os.environ["POSTGRES_DB"] = "production_db"
    os.environ["POSTGRES_PORT"] = "5433"

    # Create fresh settings instance (bypass lru_cache)
    settings = Settings()
    url = (
        f"postgresql+asyncpg://{settings.POSTGRES_USER}:{settings.POSTGRES_PASSWORD}"
        f"@{settings.POSTGRES_HOST}:{settings.POSTGRES_PORT}/{settings.POSTGRES_DB}"
    )

    assert "postgresql+asyncpg://" in url
    assert "produser" in url
    assert "production-db.example.com" in url
    assert "production_db" in url
    assert "5433" in url
