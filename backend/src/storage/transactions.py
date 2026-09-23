"""Short caller-created transactions with the same busy boundary as HTTP sync."""

from contextlib import asynccontextmanager
from typing import AsyncIterator

from sqlalchemy.exc import OperationalError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from src.storage.database_adapter import DatabaseBusyError, is_sqlite_busy


@asynccontextmanager
async def storage_transaction(
    sessions: async_sessionmaker[AsyncSession],
) -> AsyncIterator[AsyncSession]:
    try:
        # Keep session lifetime outside transaction exit. A commit/after_commit
        # failure (including cancellation after the DB committed) must still
        # close its connection; the combined factory begin() exit can stop
        # before its session-close step when transaction exit raises.
        async with sessions() as session:
            async with session.begin():
                yield session
    except OperationalError as error:
        # begin() has already rolled back/closed; never retry one statement or
        # acknowledge a result from a stale read snapshot.
        if is_sqlite_busy(error):
            raise DatabaseBusyError("Database transaction must be retried") from error
        raise
