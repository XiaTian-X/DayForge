"""Runtime lifecycle and pool policy, using real SQLite connections."""

import asyncio
from contextlib import asynccontextmanager
from unittest.mock import AsyncMock, Mock

import pytest
from sqlalchemy import event, text, update
from sqlalchemy.engine import CursorResult
from sqlalchemy.ext.asyncio import AsyncEngine
from sqlalchemy.pool import NullPool, StaticPool
from sqlmodel import col

from src import database, main
from src.auth.models import User
from src.storage.database_adapter import build_database_adapter


@pytest.fixture
def isolated_runtime(monkeypatch, tmp_path):
    """Do not create, close or replace a previous test's runtime engine."""
    monkeypatch.setattr(database, "_engine", None)
    monkeypatch.setattr(
        database,
        "DATABASE_ADAPTER",
        build_database_adapter("sqlite", None, str(tmp_path / "lifecycle.db")),
    )
    monkeypatch.setattr(main, "_create_admin_if_missing", AsyncMock())


async def test_unused_lifespan_does_not_create_engine(isolated_runtime, monkeypatch):
    factory = Mock(side_effect=AssertionError("shutdown created an unused engine"))
    monkeypatch.setattr(type(database.DATABASE_ADAPTER), "create_async_engine", factory)
    async with main.lifespan(main.app):
        assert database._engine is None
    assert database._engine is None
    factory.assert_not_called()


@pytest.mark.parametrize(
    "phase", ["normal", "body-error", "body-cancel", "startup-error", "startup-cancel"]
)
async def test_lifespan_closes_real_pooled_connection(
    isolated_runtime, monkeypatch, phase
):
    """Memory SQLite makes missing dispose observable instead of relying on GC."""
    engine = build_database_adapter("sqlite", None, ":memory:").create_async_engine()
    database.set_engine(engine)
    closed: list[bool] = []
    disposed: list[bool] = []
    event.listen(engine.sync_engine, "close", lambda *_: closed.append(True))
    event.listen(
        engine.sync_engine, "engine_disposed", lambda *_: disposed.append(True)
    )
    failure = asyncio.CancelledError if phase.endswith("cancel") else RuntimeError

    async def startup():
        async with engine.begin() as connection:
            await connection.execute(
                text("CREATE TABLE lifecycle_probe (value INTEGER)")
            )
            await connection.execute(text("INSERT INTO lifecycle_probe VALUES (7)"))
        if phase.startswith("startup"):
            raise failure("startup interrupted")

    monkeypatch.setattr(main, "_create_admin_if_missing", startup)

    async def exercise():
        async with main.lifespan(main.app):
            async with engine.connect() as connection:
                assert (
                    await connection.execute(text("SELECT value FROM lifecycle_probe"))
                ).scalar_one() == 7
            if phase.startswith("body"):
                raise failure("body interrupted")

    try:
        if phase == "normal":
            await exercise()
        else:
            with pytest.raises(failure, match="interrupted"):
                await exercise()
        assert closed == [True]
        assert disposed == [True]
        assert database._engine is None
    finally:
        # Cleanup also on the pre-fix red test; no leaked background thread.
        await engine.dispose()


async def test_lifespans_recreate_engine_without_losing_file_data(isolated_runtime):
    engines: list[AsyncEngine] = []
    disposed: list[bool] = []
    try:
        for index in range(2):
            async with main.lifespan(main.app):
                engine = database.get_engine()
                engines.append(engine)
                event.listen(
                    engine.sync_engine,
                    "engine_disposed",
                    lambda *_: disposed.append(True),
                )
                async with engine.begin() as connection:
                    if index == 0:
                        await connection.execute(
                            text("CREATE TABLE durable (value INTEGER)")
                        )
                        await connection.execute(text("INSERT INTO durable VALUES (9)"))
                    assert (
                        await connection.execute(text("SELECT value FROM durable"))
                    ).scalar_one() == 9
            assert database._engine is None
        assert engines[0] is not engines[1]
        assert disposed == [True, True]
    finally:
        for engine in engines:
            await engine.dispose()


@pytest.mark.parametrize("failure", [RuntimeError, asyncio.CancelledError])
async def test_failed_dispose_remains_retryable(isolated_runtime, monkeypatch, failure):
    engine = database.get_engine()
    original_dispose = AsyncEngine.dispose
    calls = 0

    async def fail_once(self, close=True):
        nonlocal calls
        calls += 1
        if calls == 1:
            raise failure("dispose interrupted")
        await original_dispose(self, close=close)

    monkeypatch.setattr(AsyncEngine, "dispose", fail_once)
    try:
        with pytest.raises(failure, match="dispose interrupted"):
            async with main.lifespan(main.app):
                pass
        assert database._engine is engine
        async with main.lifespan(main.app):
            assert database.get_engine() is engine
        assert database._engine is None
        async with main.lifespan(main.app):
            pass
        assert calls == 2
    finally:
        await original_dispose(engine)


@pytest.mark.parametrize("use_uri", [False, True])
async def test_file_pool_releases_each_connection_and_reapplies_pragmas(
    tmp_path, use_uri
):
    file = tmp_path / "pool.sqlite"
    url = f"sqlite+aiosqlite:///file:{file}?uri=true" if use_uri else None
    engine = build_database_adapter("sqlite", url, str(file)).create_async_engine()
    opened: list[bool] = []
    closed: list[bool] = []
    event.listen(engine.sync_engine, "connect", lambda *_: opened.append(True))
    event.listen(engine.sync_engine, "close", lambda *_: closed.append(True))
    try:
        assert isinstance(engine.pool, NullPool)
        for index in range(2):
            async with engine.connect() as connection:
                for pragma, expected in (
                    ("foreign_keys", 1),
                    ("busy_timeout", 5000),
                    ("journal_mode", "wal"),
                ):
                    assert (
                        await connection.execute(text(f"PRAGMA {pragma}"))
                    ).scalar_one() == expected
            assert len(opened) == index + 1
            assert len(closed) == index + 1
    finally:
        await engine.dispose()


@pytest.mark.parametrize(
    "url",
    [
        "sqlite+aiosqlite:///:memory:",
        "sqlite+aiosqlite:///file:lifecycle?mode=memory&cache=shared&uri=true",
    ],
)
async def test_memory_pool_retains_data_between_connections(url):
    engine = build_database_adapter("sqlite", url, "unused").create_async_engine()
    try:
        assert isinstance(engine.pool, StaticPool)
        async with engine.begin() as connection:
            await connection.execute(text("CREATE TABLE retained (value INTEGER)"))
            await connection.execute(text("INSERT INTO retained VALUES (1)"))
        async with engine.connect() as connection:
            assert (
                await connection.execute(text("SELECT value FROM retained"))
            ).scalar_one() == 1
    finally:
        await engine.dispose()


async def test_cancelled_session_rolls_back_and_releases_connection(isolated_runtime):
    """Cancel an active task after a write through the production dependency."""
    engine = database.get_engine()
    opened: list[bool] = []
    closed: list[bool] = []
    ready = asyncio.Event()
    event.listen(engine.sync_engine, "connect", lambda *_: opened.append(True))
    event.listen(engine.sync_engine, "close", lambda *_: closed.append(True))
    async with engine.begin() as connection:
        await connection.execute(text("CREATE TABLE cancelled_write (value INTEGER)"))

    async def writer():
        async with asynccontextmanager(database.get_session)() as session:
            await session.execute(text("INSERT INTO cancelled_write VALUES (1)"))
            ready.set()
            await asyncio.Event().wait()

    task = asyncio.create_task(writer())
    try:
        await asyncio.wait_for(ready.wait(), timeout=5)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        async with engine.begin() as connection:
            assert (
                await connection.execute(text("SELECT COUNT(*) FROM cancelled_write"))
            ).scalar_one() == 0
            await connection.execute(text("INSERT INTO cancelled_write VALUES (2)"))
        assert len(opened) == len(closed) == 3
    finally:
        if not task.done():
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
        await engine.dispose()


@pytest.mark.parametrize("synchronize_session", ["auto", False])
async def test_single_orm_update_retains_cursor_rowcount(
    async_session, synchronize_session
):
    """Back the narrowed result type used by sync and password-upgrade guards."""
    user = User(username="rowcount-probe", password_hash="unused", auth_version=0)
    async_session.add(user)
    await async_session.flush()
    statement = (
        update(User)
        .where(col(User.id) == user.id, col(User.auth_version) == 0)
        .values(auth_version=1)
        .execution_options(synchronize_session=synchronize_session)
    )
    for expected in (1, 0):
        result = await async_session.execute(statement)
        assert isinstance(result, CursorResult)
        assert result.rowcount == expected
