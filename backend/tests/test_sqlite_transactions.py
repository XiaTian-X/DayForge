"""Real connection boundaries required by sync snapshots and operation savepoints."""

import sqlite3

import pytest
from sqlalchemy import Engine, event, text
from sqlalchemy.exc import OperationalError

from src.storage.database_adapter import build_database_adapter, is_sqlite_busy


async def test_read_transaction_keeps_one_snapshot_across_independent_commit(tmp_path):
    adapter = build_database_adapter("sqlite", None, str(tmp_path / "snapshot.db"))
    engine = adapter.create_async_engine()
    try:
        async with engine.begin() as connection:
            await connection.execute(text("CREATE TABLE probe (value INTEGER)"))
            await connection.execute(text("INSERT INTO probe VALUES (0)"))
        async with engine.connect() as reader:
            async with reader.begin():
                assert (
                    await reader.execute(text("SELECT value FROM probe"))
                ).scalar_one() == 0
                async with engine.begin() as writer:
                    await writer.execute(text("UPDATE probe SET value = 1"))
                assert (
                    await reader.execute(text("SELECT value FROM probe"))
                ).scalar_one() == 0
            assert (
                await reader.execute(text("SELECT value FROM probe"))
            ).scalar_one() == 1
    finally:
        await engine.dispose()


@pytest.mark.parametrize("preceding_read", [False, True])
async def test_released_savepoint_is_rolled_back_with_outer_transaction(
    tmp_path, preceding_read
):
    engine = build_database_adapter(
        "sqlite", None, str(tmp_path / "savepoint.db")
    ).create_async_engine()
    try:
        async with engine.begin() as connection:
            await connection.execute(text("CREATE TABLE probe (value INTEGER)"))
        async with engine.connect() as connection:
            transaction = await connection.begin()
            if preceding_read:
                assert (
                    await connection.execute(text("SELECT COUNT(*) FROM probe"))
                ).scalar_one() == 0
            async with connection.begin_nested():
                await connection.execute(text("INSERT INTO probe VALUES (1)"))
            await transaction.rollback()
        async with engine.connect() as observer:
            assert (
                await observer.execute(text("SELECT COUNT(*) FROM probe"))
            ).scalar_one() == 0
    finally:
        await engine.dispose()


@pytest.mark.parametrize("nested", [False, True])
def test_migration_engine_ddl_and_savepoint_obey_outer_rollback(tmp_path, nested):
    engine = build_database_adapter(
        "sqlite", None, str(tmp_path / "ddl.db")
    ).create_migration_engine()
    try:
        with engine.connect() as connection:
            transaction = connection.begin()
            if nested:
                with connection.begin_nested():
                    connection.execute(text("CREATE TABLE probe (value INTEGER)"))
            else:
                connection.execute(text("CREATE TABLE probe (value INTEGER)"))
            transaction.rollback()
        with engine.connect() as observer:
            assert (
                observer.execute(
                    text("SELECT name FROM sqlite_master WHERE name = 'probe'")
                ).all()
                == []
            )
    finally:
        engine.dispose()


def test_alembic_failure_leaves_no_partial_schema_and_can_retry(tmp_path):
    from alembic import command
    from tests.test_alembic_migration import alembic_config

    path = tmp_path / "failed-migration.db"
    config = alembic_config(str(path))

    def fail_later_table(
        connection, cursor, statement, parameters, context, executemany
    ):
        if (
            str(connection.engine.url.database) == str(path)
            and "CREATE TABLE timer_segments" in statement
        ):
            raise RuntimeError("injected later migration failure")

    event.listen(Engine, "before_cursor_execute", fail_later_table)
    try:
        with pytest.raises(RuntimeError, match="later migration failure"):
            command.upgrade(config, "head")
    finally:
        event.remove(Engine, "before_cursor_execute", fail_later_table)
    adapter = build_database_adapter("sqlite", None, str(path))
    engine = adapter.create_migration_engine()
    try:
        with engine.connect() as connection:
            assert (
                connection.execute(
                    text("SELECT name FROM sqlite_master WHERE type = 'table'")
                ).all()
                == []
            )
        command.upgrade(config, "head")
        command.check(config)
        with engine.connect() as connection:
            assert (
                connection.execute(
                    text("SELECT version_num FROM alembic_version")
                ).scalar_one()
                == "000000000004"
            )
    finally:
        engine.dispose()


@pytest.mark.parametrize(
    "code, expected",
    [
        (5, True),
        (261, True),
        (517, True),
        (773, True),
        (1, False),
        (6, False),
        (19, False),
    ],
)
def test_only_real_sqlite_busy_codes_are_retryable(code, expected):
    original = sqlite3.OperationalError("private SQL must not become a client response")
    original.sqlite_errorcode = code
    wrapped = OperationalError("private statement", {"private": "value"}, original)
    assert is_sqlite_busy(wrapped) is expected


def test_busy_text_without_sqlite_code_is_not_misclassified():
    for original in (
        sqlite3.OperationalError("database is locked"),
        RuntimeError("SQLITE_BUSY"),
    ):
        assert not is_sqlite_busy(OperationalError("private statement", {}, original))
