"""Additive receipt columns preserve all old rows, fingerprints and rollback."""

from contextlib import closing
import sqlite3

from alembic import command
import pytest
from sqlalchemy import Engine, event

from tests.test_alembic_migration import alembic_config
from tests.test_logical_archive import migrate, seed_source
from tests.test_logical_archive_identity import database_dump


def test_receipt_migration_appends_null_context_without_rewriting_frozen_rows(tmp_path):
    path = tmp_path / "receipts.sqlite"
    migrate(path, revision="000000000008")
    seed_source(f"sqlite:///{path}")
    before = database_dump(path)
    with closing(sqlite3.connect(path)) as connection:
        rows = {
            table: connection.execute(f"SELECT * FROM {table} ORDER BY id").fetchall()
            for table in ("sync_operations", "timer_commands")
        }
    command.upgrade(alembic_config(str(path)), "head")
    command.check(alembic_config(str(path)))
    with closing(sqlite3.connect(path)) as connection:
        for table, original in rows.items():
            current = connection.execute(
                f"SELECT * FROM {table} ORDER BY id"
            ).fetchall()
            assert current == [(*row, None) for row in original]
            assert (
                connection.execute(f"PRAGMA table_info({table})").fetchall()[-1][1]
                == "challenge_context_json"
            )
        assert connection.execute("PRAGMA foreign_key_check").fetchall() == []
    command.downgrade(alembic_config(str(path)), "000000000008")
    assert database_dump(path) == before


@pytest.mark.parametrize("phase", ["second-column", "checkpoint"])
def test_receipt_migration_is_atomic_and_can_retry(tmp_path, phase):
    path = tmp_path / "failure.sqlite"
    migrate(path, revision="000000000008")
    seed_source(f"sqlite:///{path}")
    before = database_dump(path)

    def fail(connection, _cursor, statement, _parameters, _context, _many):
        if connection.engine.url.database == str(path) and (
            (
                phase == "second-column"
                and "ALTER TABLE timer_commands ADD COLUMN" in statement
            )
            or (phase == "checkpoint" and "UPDATE alembic_version" in statement)
        ):
            raise RuntimeError("injected receipt migration")

    event.listen(Engine, "before_cursor_execute", fail)
    try:
        with pytest.raises(RuntimeError, match="receipt migration"):
            command.upgrade(alembic_config(str(path)), "head")
    finally:
        event.remove(Engine, "before_cursor_execute", fail)
    assert database_dump(path) == before
    command.upgrade(alembic_config(str(path)), "head")
    command.check(alembic_config(str(path)))


@pytest.mark.parametrize("table", ["sync_operations", "timer_commands"])
def test_downgrade_refuses_any_context_evidence(tmp_path, table):
    path = tmp_path / "guard.sqlite"
    migrate(path)
    seed_source(f"sqlite:///{path}")
    with closing(sqlite3.connect(path)) as connection:
        connection.execute(f"UPDATE {table} SET challenge_context_json='{{}}'")
        connection.commit()
        assert (
            connection.execute(
                f"SELECT COUNT(*) FROM {table} WHERE challenge_context_json IS NOT NULL"
            ).fetchone()[0]
            > 0
        )
    before = database_dump(path)
    with pytest.raises(RuntimeError, match="must not be discarded"):
        command.downgrade(alembic_config(str(path)), "000000000008")
    assert database_dump(path) == before


def test_offline_downgrade_cannot_skip_receipt_check(tmp_path):
    path = tmp_path / "offline.sqlite"
    migrate(path)
    with pytest.raises(RuntimeError, match="online validation"):
        command.downgrade(
            alembic_config(str(path)), "000000000009:000000000008", sql=True
        )
