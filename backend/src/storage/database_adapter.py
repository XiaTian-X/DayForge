"""Supported database runtime adapters.

The application currently supports SQLite only. A future adapter must provide
both an async application engine and a synchronous migration engine while
preserving the transaction semantics exercised by the sync contract tests.
"""

from __future__ import annotations

from dataclasses import dataclass
import sqlite3
from typing import Protocol

from sqlalchemy import create_engine, event
from sqlalchemy.engine import Connection, Engine, URL, make_url
from sqlalchemy.exc import ArgumentError, OperationalError
from sqlalchemy.ext.asyncio import AsyncEngine, create_async_engine
from sqlalchemy.pool import NullPool, StaticPool


class DatabaseConfigurationError(ValueError):
    """Raised when configuration names a database runtime DayForge cannot run."""


class DatabaseBusyError(RuntimeError):
    """The complete transaction was rolled back and may be retried by its caller."""


def is_sqlite_busy(error: OperationalError) -> bool:
    """Use the driver's code, never exception text that can contain SQL or data."""
    original = error.orig
    return (
        isinstance(original, sqlite3.OperationalError)
        and getattr(original, "sqlite_errorcode", 0) & 0xFF == sqlite3.SQLITE_BUSY
    )


class DatabaseAdapter(Protocol):
    """Engine boundary required from every supported database implementation."""

    @property
    def kind(self) -> str: ...

    @property
    def async_url(self) -> str: ...

    @property
    def migration_url(self) -> str: ...

    def create_async_engine(self) -> AsyncEngine: ...

    def create_migration_engine(self) -> Engine: ...


def _apply_sqlite_pragmas(dbapi_connection, _connection_record) -> None:
    # Let SQLAlchemy own BEGIN, including SELECT, DDL and an initial SAVEPOINT.
    # Driver legacy transaction control otherwise skips those statements.
    dbapi_connection.isolation_level = None
    cursor = dbapi_connection.cursor()
    try:
        cursor.execute("PRAGMA foreign_keys=ON")
        cursor.execute("PRAGMA journal_mode=WAL")
        cursor.execute("PRAGMA busy_timeout=5000")
    finally:
        cursor.close()


def _begin_sqlite_transaction(connection: Connection) -> None:
    connection.exec_driver_sql("BEGIN")


def configure_sqlite_transactions(engine: Engine) -> None:
    """Apply once to a fresh runtime or Alembic engine, before first connect.

    PRAGMAs run outside a transaction. Deferred BEGIN preserves WAL readers;
    an outdated reader's write upgrade may fail with SQLITE_BUSY_SNAPSHOT and
    must roll back the whole request before retry, never only its last statement.
    Do not combine this policy with SQLAlchemy's driver-level AUTOCOMMIT.
    """
    event.listen(engine, "connect", _apply_sqlite_pragmas)
    event.listen(engine, "begin", _begin_sqlite_transaction)


@dataclass(frozen=True)
class SQLiteDatabaseAdapter:
    """Single-process SQLite engine configuration used by runtime and Alembic."""

    async_url: str
    migration_url: str
    kind: str = "sqlite"

    def create_async_engine(self) -> AsyncEngine:
        url = make_url(self.async_url)
        is_memory = (
            not url.database
            or url.database == ":memory:"
            or url.query.get("mode") == "memory"
        )
        engine = create_async_engine(
            self.async_url,
            echo=False,
            future=True,
            connect_args={"check_same_thread": False},
            # Preserve the pre-2.0.38 async SQLite policy across driver upgrades.
            # Memory databases need one retained connection to keep their data.
            poolclass=StaticPool if is_memory else NullPool,
        )
        configure_sqlite_transactions(engine.sync_engine)
        return engine

    def create_migration_engine(self) -> Engine:
        engine = create_engine(self.migration_url, echo=False, future=True)
        configure_sqlite_transactions(engine)
        return engine


def _sqlite_async_url(database_url: str | None, sqlite_path: str) -> URL:
    if database_url is None:
        if not sqlite_path.strip() or "\x00" in sqlite_path:
            raise DatabaseConfigurationError("SQLITE_DB_PATH must name a database file")
        return URL.create("sqlite+aiosqlite", database=sqlite_path)
    try:
        url = make_url(database_url)
    except ArgumentError as error:
        raise DatabaseConfigurationError(
            "DATABASE_URL is not a valid SQLAlchemy URL"
        ) from error
    if url.drivername != "sqlite+aiosqlite":
        raise DatabaseConfigurationError(
            "DATABASE_URL must use sqlite+aiosqlite; no other database backend is supported"
        )
    if any((url.username, url.password, url.host, url.port)):
        raise DatabaseConfigurationError(
            "DATABASE_URL must be a local SQLite URL without network credentials"
        )
    if not url.database:
        raise DatabaseConfigurationError("DATABASE_URL must name a SQLite database")
    return url


def build_database_adapter(
    database_type: str,
    database_url: str | None,
    sqlite_path: str,
) -> DatabaseAdapter:
    """Validate configuration and return the only adapter supported by this release."""
    if database_type.strip().lower() != "sqlite":
        raise DatabaseConfigurationError(
            "DATABASE_TYPE must be sqlite; PostgreSQL is not implemented in this release"
        )
    async_url = _sqlite_async_url(database_url, sqlite_path)
    migration_url = async_url.set(drivername="sqlite")
    return SQLiteDatabaseAdapter(
        async_url=async_url.render_as_string(hide_password=False),
        migration_url=migration_url.render_as_string(hide_password=False),
    )


__all__ = [
    "DatabaseAdapter",
    "DatabaseConfigurationError",
    "DatabaseBusyError",
    "SQLiteDatabaseAdapter",
    "build_database_adapter",
    "configure_sqlite_transactions",
    "is_sqlite_busy",
]
