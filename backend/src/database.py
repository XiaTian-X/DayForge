"""Database configuration and session management."""
from sqlmodel import SQLModel, create_engine, Session
from sqlalchemy import event
from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine, async_sessionmaker
from typing import AsyncGenerator, Optional, Any

from src.config import get_database_url


# Get database URL from config
DATABASE_URL = get_database_url()


# Async engine for SQLModel - can be overridden for testing
_engine: Optional[Any] = None


def get_engine() -> Any:
    """Get the database engine, creating if necessary."""
    global _engine
    if _engine is None:
        connect_args = {"check_same_thread": False} if "sqlite" in DATABASE_URL else {}
        _engine = create_async_engine(
            DATABASE_URL,
            echo=False,
            future=True,
            connect_args=connect_args
        )
        # Enable foreign keys for SQLite async engine
        if "sqlite" in DATABASE_URL:
            @event.listens_for(_engine.sync_engine, "connect")
            def set_sqlite_pragma_async(dbapi_conn, connection_record):
                cursor = dbapi_conn.cursor()
                cursor.execute("PRAGMA foreign_keys=ON")
                cursor.execute("PRAGMA journal_mode=WAL")
                cursor.execute("PRAGMA busy_timeout=5000")
                cursor.close()
    return _engine


def set_engine(engine: Any) -> None:
    """Set the engine (for testing)."""
    global _engine
    _engine = engine


# Sync engine for migrations (replace +aiosqlite with empty string for SQLite)
sync_engine_url = DATABASE_URL.replace("+aiosqlite", "")
sync_engine = create_engine(sync_engine_url, echo=False, future=True)


# For SQLite, enable foreign key constraints
if "sqlite" in DATABASE_URL:
    @event.listens_for(sync_engine, "connect")
    def set_sqlite_pragma(dbapi_conn, connection_record):
        cursor = dbapi_conn.cursor()
        cursor.execute("PRAGMA foreign_keys=ON")
        cursor.execute("PRAGMA journal_mode=WAL")
        cursor.execute("PRAGMA busy_timeout=5000")
        cursor.close()


async def get_session() -> AsyncGenerator[AsyncSession, None]:
    """Async database session dependency.

    Yields an AsyncSession that automatically commits on success
    or rolls back on error.
    """
    engine = get_engine()
    async with async_sessionmaker(
        bind=engine,
        class_=AsyncSession,
        expire_on_commit=False
    )() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise
        finally:
            await session.close()


def get_sync_session() -> Session:
    """Sync database session for migrations."""
    with Session(sync_engine) as session:
        yield session


async def create_db_and_tables():
    """Create registered tables for isolated tests only.

    Runtime and deployment must use Alembic. This helper remains for existing
    unit-test fixtures and must not be called from application startup.
    """
    engine = get_engine()
    async with engine.begin() as conn:
        await conn.run_sync(SQLModel.metadata.create_all)


__all__ = ["get_session", "create_db_and_tables", "get_engine", "set_engine", "sync_engine", "DATABASE_URL"]
