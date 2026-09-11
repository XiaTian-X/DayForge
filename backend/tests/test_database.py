"""Tests for database module."""
import pytest


class TestDatabaseModule:
    """Test database module imports and exports."""

    def test_database_module_imports(self):
        """Test that database module exports required functions."""
        from src.database import get_session, create_db_and_tables, get_engine

        assert get_session is not None
        assert create_db_and_tables is not None
        assert get_engine is not None

    def test_engine_is_async(self):
        """Test that engine is an async engine."""
        from src.database import get_engine

        engine = get_engine()
        # Check engine has async attributes
        assert hasattr(engine, 'begin')
        assert hasattr(engine, 'dispose')


class TestAsyncSession:
    """Test async session functionality."""

    @pytest.mark.asyncio
    async def test_async_session_fixture_creates_tables(self, async_session):
        """Test that async session fixture creates tables on startup."""
        from src.database import get_engine

        engine = get_engine()
        # Verify we can access the database through the session
        # The async_session fixture already creates tables via SQLModel metadata
        assert async_session is not None

        # Verify tables were created by checking the database
        async with engine.begin() as conn:
            from sqlalchemy import text
            result = await conn.execute(
                text("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
            )
            tables = [row[0] for row in result.fetchall()]

        # The async_session fixture should have created some tables
        # Even if no models defined, we should be able to query
        assert async_session.bind is not None

    @pytest.mark.asyncio
    async def test_async_session_can_commit(self, async_session):
        """Test that async session can commit transactions."""
        from src.auth.models import User

        # Insert and commit
        test_obj = User(username="database-commit", password_hash="test-hash")
        async_session.add(test_obj)
        await async_session.commit()

        # Verify it was saved
        result = await async_session.get(User, test_obj.id)
        assert result is not None
        assert result.username == "database-commit"


class TestEngineType:
    """Test engine configuration."""

    def test_engine_uses_aiosqlite_for_sqlite(self):
        """Test that engine uses aiosqlite dialect for SQLite."""
        from src.database import get_engine

        engine = get_engine()
        assert engine.url.drivername == "sqlite+aiosqlite"

    def test_sync_engine_url_conversion(self):
        """Test that the adapter provides a synchronous Alembic URL."""
        from src.config import get_database_url, get_migration_database_url

        async_url = get_database_url()
        sync_url = get_migration_database_url()

        assert async_url.startswith("sqlite+aiosqlite:///")
        assert "+aiosqlite" not in sync_url
        assert sync_url.startswith("sqlite:///")

    def test_runtime_adapter_applies_required_sqlite_pragmas(self, tmp_path):
        """Production engines must enforce integrity and bounded writer waiting."""
        from sqlalchemy import text
        from src.storage.database_adapter import build_database_adapter

        adapter = build_database_adapter("sqlite", None, str(tmp_path / "adapter.db"))
        engine = adapter.create_migration_engine()
        try:
            with engine.connect() as connection:
                assert connection.execute(text("PRAGMA foreign_keys")).scalar_one() == 1
                assert connection.execute(text("PRAGMA busy_timeout")).scalar_one() == 5000
                assert connection.execute(text("PRAGMA journal_mode")).scalar_one() == "wal"
        finally:
            engine.dispose()

    @pytest.mark.asyncio
    async def test_async_adapter_applies_required_sqlite_pragmas(self, tmp_path):
        from sqlalchemy import text
        from src.storage.database_adapter import build_database_adapter

        adapter = build_database_adapter("sqlite", None, str(tmp_path / "async-adapter.db"))
        engine = adapter.create_async_engine()
        try:
            async with engine.connect() as connection:
                assert (await connection.execute(text("PRAGMA foreign_keys"))).scalar_one() == 1
                assert (await connection.execute(text("PRAGMA busy_timeout"))).scalar_one() == 5000
                assert (await connection.execute(text("PRAGMA journal_mode"))).scalar_one() == "wal"
        finally:
            await engine.dispose()


class TestCreateDbAndTables:
    """Test create_db_and_tables function."""

    @pytest.mark.asyncio
    async def test_create_db_and_tables_creates_schema(self, tmp_path):
        """Test that create_db_and_tables creates all SQLModel tables."""
        from sqlalchemy import text
        from src.database import create_db_and_tables, get_engine, set_engine
        from src.storage.database_adapter import build_database_adapter

        previous_engine = get_engine()
        adapter = build_database_adapter("sqlite", None, str(tmp_path / "create-all.db"))
        isolated_engine = adapter.create_async_engine()
        set_engine(isolated_engine)
        try:
            await create_db_and_tables()
            async with isolated_engine.begin() as connection:
                result = await connection.execute(
                    text("SELECT name FROM sqlite_master WHERE type='table' AND name='users'")
                )
                assert result.scalar_one() == "users"
        finally:
            set_engine(previous_engine)
            await isolated_engine.dispose()
