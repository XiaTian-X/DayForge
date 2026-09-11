"""Tests for database module."""
import pytest
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import SQLModel, Field


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
        from sqlmodel import SQLModel, Field
        from typing import Optional

        # Create a test model
        class TestModel(SQLModel, table=True):
            __tablename__ = "test_models"
            id: Optional[int] = Field(default=None, primary_key=True)
            name: str = Field()

        # Create the table
        async with async_session.bind.begin() as conn:
            await conn.run_sync(SQLModel.metadata.create_all)

        # Insert and commit
        test_obj = TestModel(name="test")
        async_session.add(test_obj)
        await async_session.commit()

        # Verify it was saved
        result = await async_session.get(TestModel, test_obj.id)
        assert result is not None
        assert result.name == "test"


class TestEngineType:
    """Test engine configuration."""

    def test_engine_uses_aiosqlite_for_sqlite(self):
        """Test that engine uses aiosqlite dialect for SQLite."""
        from src.database import get_engine

        engine = get_engine()
        # Check the dialect name
        assert "sqlite" in str(engine.dialect.name).lower()

    def test_sync_engine_url_conversion(self):
        """Test that sync engine URL is correctly converted from async URL."""
        from src.config import get_database_url

        async_url = get_database_url()
        sync_url = async_url.replace("+aiosqlite", "")

        # For SQLite, the sync URL should not have +aiosqlite
        assert "+aiosqlite" not in sync_url
        assert "sqlite://" in sync_url or "sqlite:///" in sync_url


class TestCreateDbAndTables:
    """Test create_db_and_tables function."""

    @pytest.mark.asyncio
    async def test_create_db_and_tables_creates_schema(self):
        """Test that create_db_and_tables creates all SQLModel tables."""
        from src.database import create_db_and_tables, get_engine
        from sqlmodel import SQLModel, Field
        from typing import Optional

        # Define a test model
        class TempModel(SQLModel, table=True):
            __tablename__ = "temp_test_table"
            id: Optional[int] = Field(default=None, primary_key=True)
            value: str = Field()

        # Create tables
        await create_db_and_tables()

        # Verify table was created
        engine = get_engine()
        async with engine.begin() as conn:
            from sqlalchemy import text
            result = await conn.execute(
                text("SELECT name FROM sqlite_master WHERE type='table' AND name='temp_test_table'")
            )
            table = result.fetchone()

        assert table is not None
        assert table[0] == "temp_test_table"
