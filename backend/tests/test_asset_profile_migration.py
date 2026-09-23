from contextlib import closing
import sqlite3

from alembic import command
import pytest
from sqlalchemy import Engine, event

from tests.test_alembic_migration import alembic_config
from tests.test_asset_storage import seed_appearance
from tests.test_logical_archive_identity import database_dump


@pytest.fixture
def previous(tmp_path):
    path = tmp_path / "profiles.sqlite"
    seed_appearance(path)
    command.downgrade(alembic_config(str(path)), "000000000004")
    return path


def rows(path):
    with closing(sqlite3.connect(path)) as connection:
        connection.row_factory = sqlite3.Row
        return [
            dict(row)
            for row in connection.execute(
                "SELECT * FROM account_icon_blobs ORDER BY id"
            )
        ]


def test_upgrade_preserves_pending_blobs_all_references_and_downgrades_losslessly(
    previous,
):
    before = database_dump(previous)
    blobs = rows(previous)
    command.upgrade(alembic_config(str(previous)), "head")
    command.check(alembic_config(str(previous)))
    assert rows(previous) == [row | {"validation_profile": None} for row in blobs]
    with closing(sqlite3.connect(previous)) as connection:
        assert connection.execute("PRAGMA foreign_key_check").fetchall() == []
    command.downgrade(alembic_config(str(previous)), "000000000004")
    assert database_dump(previous) == before


@pytest.mark.parametrize(
    "profile,ready",
    [
        (None, "2026-09-23 00:00:00"),
        ("png-v1", None),
        ("svg-v1", "2026-09-23 00:00:00"),
        ("png-v2", "2026-09-23 00:00:00"),
        ("", "2026-09-23 00:00:00"),
    ],
)
def test_database_rejects_partial_unknown_and_wrong_media_profiles(
    previous, profile, ready
):
    command.upgrade(alembic_config(str(previous)), "head")
    before = database_dump(previous)
    with closing(sqlite3.connect(previous)) as connection:
        with pytest.raises(sqlite3.IntegrityError, match="ck_icon_blob_ready_profile"):
            connection.execute(
                "UPDATE account_icon_blobs SET validation_profile=?,ready_at=? WHERE owner_user_id=101",
                (profile, ready),
            )
        connection.rollback()
    assert database_dump(previous) == before


def test_valid_profile_cannot_be_discarded_by_downgrade(previous):
    command.upgrade(alembic_config(str(previous)), "head")
    with closing(sqlite3.connect(previous)) as connection:
        connection.execute(
            "UPDATE account_icon_blobs SET validation_profile='png-v1',ready_at='2026-09-23 00:00:00' WHERE owner_user_id=101"
        )
        connection.commit()
    before = database_dump(previous)
    with pytest.raises(RuntimeError, match="installed appearance data exists"):
        command.downgrade(alembic_config(str(previous)), "000000000004")
    assert database_dump(previous) == before


def test_upgrade_never_invents_validation_for_unversioned_ready_rows(previous):
    with closing(sqlite3.connect(previous)) as connection:
        connection.execute(
            "UPDATE account_icon_blobs SET ready_at='2026-09-23 00:00:00' WHERE owner_user_id=101"
        )
        connection.commit()
    before = database_dump(previous)
    with pytest.raises(RuntimeError, match="unversioned ready blobs"):
        command.upgrade(alembic_config(str(previous)), "head")
    assert database_dump(previous) == before


def test_failure_after_column_add_rolls_back_schema_and_version(previous):
    before = database_dump(previous)

    def fail(connection, cursor, statement, parameters, context, executemany):
        if (
            str(connection.engine.url.database) == str(previous)
            and "UPDATE alembic_version" in statement
        ):
            raise RuntimeError("synthetic profile migration failure")

    event.listen(Engine, "before_cursor_execute", fail)
    try:
        with pytest.raises(RuntimeError, match="synthetic profile migration"):
            command.upgrade(alembic_config(str(previous)), "head")
    finally:
        event.remove(Engine, "before_cursor_execute", fail)
    assert database_dump(previous) == before
    command.upgrade(alembic_config(str(previous)), "head")
    command.check(alembic_config(str(previous)))
