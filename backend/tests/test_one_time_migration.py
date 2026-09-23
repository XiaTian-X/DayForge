"""Incremental schema safety; these raw-SQL probes are not domain/HTTP proofs."""

from contextlib import closing
import json
import sqlite3

from alembic import command
import pytest
from sqlalchemy import Engine, event

from src.storage.logical_archive import import_archive
from tests.test_alembic_migration import alembic_config
from tests.test_logical_archive_identity import FIXTURE, write_bundle


def baseline_database(tmp_path):
    path = tmp_path / "baseline.sqlite"
    command.upgrade(alembic_config(str(path)), "000000000001")
    bundle = json.loads(FIXTURE.read_text())
    import_archive(f"sqlite:///{path}", write_bundle(tmp_path / "baseline.zip", bundle))
    return path


def table_snapshot(path):
    with closing(sqlite3.connect(path)) as connection:
        tables = connection.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('alembic_version','sqlite_sequence') ORDER BY name"
        ).fetchall()
        return {
            name: (
                [row[1] for row in connection.execute(f"PRAGMA table_info({name})")],
                connection.execute(f"SELECT * FROM {name} ORDER BY 1").fetchall(),
            )
            for (name,) in tables
        }


def test_incremental_upgrade_preserves_all_existing_data_and_does_not_guess_state(
    tmp_path,
):
    path = baseline_database(tmp_path)
    before = table_snapshot(path)
    command.upgrade(alembic_config(str(path)), "head")
    command.check(alembic_config(str(path)))
    with closing(sqlite3.connect(path)) as connection:
        for table, (columns, values) in before.items():
            assert (
                connection.execute(
                    f"SELECT {','.join(columns)} FROM {table} ORDER BY 1"
                ).fetchall()
                == values
            )
        assert connection.execute(
            "SELECT one_time_version, one_time_head_event_uuid, one_time_completion_event_uuid FROM activity_details"
        ).fetchall() == [(None, None, None), (None, None, None)]
        assert connection.execute(
            "SELECT one_time_expected_version, one_time_expected_head_event_uuid FROM activity_events"
        ).fetchall() == [(None, None)]
        assert connection.execute("PRAGMA foreign_key_check").fetchall() == []
    command.downgrade(alembic_config(str(path)), "000000000001")
    assert table_snapshot(path) == before


def test_late_incremental_migration_failure_rolls_back_all_added_columns(tmp_path):
    path = baseline_database(tmp_path)
    before = table_snapshot(path)

    def fail_event(connection, cursor, statement, parameters, context, executemany):
        if (
            str(connection.engine.url.database) == str(path)
            and "ADD COLUMN one_time_expected_head_event_uuid" in statement
        ):
            raise RuntimeError("injected event schema failure")

    event.listen(Engine, "before_cursor_execute", fail_event)
    try:
        with pytest.raises(RuntimeError, match="event schema failure"):
            command.upgrade(alembic_config(str(path)), "head")
    finally:
        event.remove(Engine, "before_cursor_execute", fail_event)
    assert table_snapshot(path) == before
    with closing(sqlite3.connect(path)) as connection:
        assert connection.execute(
            "SELECT version_num FROM alembic_version"
        ).fetchone() == ("000000000001",)
    command.upgrade(alembic_config(str(path)), "head")
    command.check(alembic_config(str(path)))


@pytest.mark.parametrize(
    "version,head,completion",
    [
        (None, "10000000-0000-0000-0000-000000000001", None),
        (0, "10000000-0000-0000-0000-000000000001", None),
        (1, None, None),
        (1, "10000000-0000-0000-0000-000000000001", None),
        (
            2,
            "10000000-0000-0000-0000-000000000001",
            "10000000-0000-0000-0000-000000000001",
        ),
        (-1, None, None),
        (2147483648, "10000000-0000-0000-0000-000000000001", None),
    ],
)
def test_database_rejects_partial_and_invalid_projection(
    tmp_path, version, head, completion
):
    path = baseline_database(tmp_path)
    command.upgrade(alembic_config(str(path)), "head")
    with closing(sqlite3.connect(path)) as connection:
        with pytest.raises(sqlite3.IntegrityError, match="ck_activity_one_time_state"):
            connection.execute(
                "UPDATE activity_details SET tracking_mode='check', completion_policy='one_and_done', one_time_version=?, one_time_head_event_uuid=?, one_time_completion_event_uuid=?",
                (version, head, completion),
            )
        connection.rollback()
        assert connection.execute(
            "SELECT one_time_version FROM activity_details"
        ).fetchall() == [(None,), (None,)]


def test_downgrade_refuses_even_initialized_zero_state_without_erasing_data(tmp_path):
    path = baseline_database(tmp_path)
    command.upgrade(alembic_config(str(path)), "head")
    with closing(sqlite3.connect(path)) as connection:
        connection.execute(
            "UPDATE activity_details SET tracking_mode='check', completion_policy='one_and_done', one_time_version=0"
        )
        connection.commit()
    before = table_snapshot(path)
    with pytest.raises(RuntimeError, match="one-time data exists"):
        command.downgrade(alembic_config(str(path)), "000000000001")
    assert table_snapshot(path) == before
    with closing(sqlite3.connect(path)) as connection:
        assert connection.execute(
            "SELECT version_num FROM alembic_version"
        ).fetchone() == ("000000000005",)
