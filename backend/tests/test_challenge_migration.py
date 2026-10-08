"""Additive migration, actual model check, whole-DDL rollback and safe downgrade."""

from contextlib import closing
import sqlite3

from alembic import command
import pytest
from sqlalchemy import Engine, event

from tests.test_alembic_migration import alembic_config
from tests.test_logical_archive import migrate, seed_source
from tests.test_logical_archive_identity import database_dump


TABLES = (
    "activity_challenge_rounds",
    "activity_challenge_heads",
    "activity_challenge_event_bindings",
    "activity_challenge_timer_bindings",
)
INDEXES = (
    "uq_client_device_owner_identity",
    "uq_activity_event_owner_activity_identity",
    "uq_timer_session_owner_activity_identity",
)


def test_incremental_upgrade_keeps_every_old_column_row_and_ddl_without_guessing_birth(
    tmp_path,
):
    path = tmp_path / "legacy.db"
    migrate(path, revision="000000000007")
    seed_source(f"sqlite:///{path}")
    before = database_dump(path)
    with closing(sqlite3.connect(path)) as connection:
        old_objects = connection.execute(
            "SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name"
        ).fetchall()
        names = [
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
            )
        ]
        rows = {
            name: connection.execute(
                f'SELECT * FROM "{name}" ORDER BY rowid'
            ).fetchall()
            for name in names
            if name != "alembic_version"
        }
    command.upgrade(alembic_config(str(path)), "head")
    command.check(alembic_config(str(path)))
    with closing(sqlite3.connect(path)) as connection:
        current_objects = connection.execute(
            "SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name"
        ).fetchall()
        assert set(old_objects) <= set(current_objects)
        assert all(
            connection.execute(f'SELECT * FROM "{name}" ORDER BY rowid').fetchall()
            == values
            for name, values in rows.items()
        )
        assert all(
            connection.execute(f"SELECT * FROM {table}").fetchall() == []
            for table in TABLES
        )
        assert connection.execute("PRAGMA foreign_key_check").fetchall() == []
    command.downgrade(alembic_config(str(path)), "000000000007")
    assert database_dump(path) == before


@pytest.mark.parametrize("phase", ["third-table", "checkpoint"])
def test_failed_schema_checkpoint_rolls_back_indices_tables_and_old_data_then_retries(
    tmp_path, phase
):
    path = tmp_path / "failed.db"
    migrate(path, revision="000000000007")
    seed_source(f"sqlite:///{path}")
    before = database_dump(path)

    def fail(connection, _cursor, statement, _parameters, _context, _many):
        if connection.engine.url.database == str(path) and (
            (
                phase == "third-table"
                and "CREATE TABLE activity_challenge_event_bindings" in statement
            )
            or (phase == "checkpoint" and "UPDATE alembic_version" in statement)
        ):
            raise RuntimeError("injected challenge migration")

    event.listen(Engine, "before_cursor_execute", fail)
    try:
        with pytest.raises(RuntimeError, match="challenge migration"):
            command.upgrade(alembic_config(str(path)), "head")
    finally:
        event.remove(Engine, "before_cursor_execute", fail)
    assert database_dump(path) == before
    command.upgrade(alembic_config(str(path)), "head")
    command.check(alembic_config(str(path)))


def test_history_or_offline_sql_cannot_bypass_downgrade_guard(tmp_path):
    path = tmp_path / "guard.db"
    migrate(path)
    seed_source(f"sqlite:///{path}")
    from src.v2.challenge_round import initial_round_uuid

    with closing(sqlite3.connect(path)) as connection:
        node_id, owner, public_id = connection.execute(
            "SELECT id,owner_user_id,public_id FROM plan_nodes WHERE node_kind='activity' LIMIT 1"
        ).fetchone()
        connection.execute(
            "INSERT INTO activity_challenge_rounds(owner_user_id,activity_node_id,public_id,generation) VALUES (?,?,?,0)",
            (owner, node_id, initial_round_uuid(public_id)),
        )
        connection.commit()
    before = database_dump(path)
    with pytest.raises(RuntimeError, match="challenge history exists"):
        command.downgrade(alembic_config(str(path)), "000000000007")
    assert database_dump(path) == before
    with pytest.raises(RuntimeError, match="online evidence check"):
        command.downgrade(
            alembic_config(str(tmp_path / "unused.db")),
            "000000000008:000000000007",
            sql=True,
        )
    assert not (tmp_path / "unused.db").exists()
