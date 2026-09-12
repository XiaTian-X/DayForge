"""Database session management over the configured storage adapter."""
from sqlmodel import SQLModel
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from typing import AsyncGenerator, Optional, Any

from src.config import get_database_adapter


DATABASE_ADAPTER = get_database_adapter()
DATABASE_URL = DATABASE_ADAPTER.async_url


# Async engine for SQLModel - can be overridden for testing
_engine: Optional[Any] = None


def get_engine() -> Any:
    """Get the database engine, creating if necessary."""
    global _engine
    if _engine is None:
        _engine = DATABASE_ADAPTER.create_async_engine()
    return _engine


def set_engine(engine: Any) -> None:
    """Set the engine (for testing)."""
    global _engine
    _engine = engine


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


async def create_db_and_tables():
    """Create registered tables for isolated tests only.

    Runtime and deployment must use Alembic. This helper remains for existing
    unit-test fixtures and must not be called from application startup.
    """
    engine = get_engine()
    async with engine.begin() as conn:
        await conn.run_sync(SQLModel.metadata.create_all)


__all__ = [
    "get_session",
    "create_db_and_tables",
    "get_engine",
    "set_engine",
    "DATABASE_ADAPTER",
    "DATABASE_URL",
]
