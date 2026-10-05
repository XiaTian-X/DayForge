"""Additive registration evidence: no guessed legacy proof or parent rebuild."""

from contextlib import closing
import sqlite3

from alembic import command
import pytest
from sqlalchemy import Engine, event

from tests.test_alembic_migration import alembic_config
from tests.test_asset_storage import seed_appearance
from tests.test_logical_archive_identity import database_dump
from tests.test_logical_archive import migrate
from src.storage.logical_archive import export_archive, import_archive
from src.storage.sqlite_maintenance import create_backup, restore_backup


@pytest.fixture
def previous(tmp_path):
    path = tmp_path / "device-protocol.sqlite"
    seed_appearance(path)
    command.downgrade(alembic_config(str(path)), "000000000005")
    return path


def state(path):
    with closing(sqlite3.connect(path)) as connection:
        connection.row_factory = sqlite3.Row
        names = [
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
            )
        ]
        return {
            name: [
                dict(row)
                for row in connection.execute(f'SELECT * FROM "{name}" ORDER BY rowid')
            ]
            for name in names
        }, list(
            connection.execute(
                "SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name"
            )
        )


def test_upgrade_preserves_all_legacy_rows_ddl_ids_and_unknown_proof_and_roundtrips(
    previous,
):
    before = database_dump(previous)
    rows, objects = state(previous)
    command.upgrade(alembic_config(str(previous)), "head")
    command.check(alembic_config(str(previous)))
    current, current_objects = state(previous)
    assert current == rows | {
        "client_devices": [
            row | {"registered_protocol_version": None}
            for row in rows["client_devices"]
        ],
        "alembic_version": [{"version_num": "000000000006"}],
    }
    original_ddl = {tuple(row[:3]): row[3] for row in objects}
    new_ddl = {tuple(row[:3]): row[3] for row in current_objects}
    assert original_ddl.keys() == new_ddl.keys()
    for key, ddl in original_ddl.items():
        if key == ("table", "client_devices", "client_devices"):
            assert ddl.count("revoked_at DATETIME,") == 1
            assert new_ddl[key] == ddl.replace(
                "revoked_at DATETIME,",
                "revoked_at DATETIME, registered_protocol_version INTEGER CONSTRAINT ck_client_device_registered_protocol CHECK (registered_protocol_version IS NULL OR (registered_protocol_version >= 1 AND registered_protocol_version <= 2147483647)),",
            )
        else:
            assert new_ddl[key] == ddl
    with closing(sqlite3.connect(previous)) as connection:
        assert connection.execute("PRAGMA foreign_key_check").fetchall() == []
        assert connection.execute(
            "SELECT registered_protocol_version FROM client_devices"
        ).fetchall() == [(None,)]
    command.downgrade(alembic_config(str(previous)), "000000000005")
    assert database_dump(previous) == before


@pytest.mark.parametrize("value", [0, -1, 2_147_483_648, "unknown", b"5"])
def test_database_rejects_invalid_bounds_or_non_numeric_evidence_without_change(
    previous, value
):
    command.upgrade(alembic_config(str(previous)), "head")
    before = database_dump(previous)
    with closing(sqlite3.connect(previous)) as connection:
        with pytest.raises(
            sqlite3.IntegrityError, match="ck_client_device_registered_protocol"
        ):
            connection.execute(
                "UPDATE client_devices SET registered_protocol_version=?", (value,)
            )
        connection.rollback()
    assert database_dump(previous) == before


@pytest.mark.parametrize("value", [4, 5, 6, 2_147_483_647])
def test_recorded_evidence_cannot_be_dropped_by_downgrade(previous, value):
    command.upgrade(alembic_config(str(previous)), "head")
    with closing(sqlite3.connect(previous)) as connection:
        connection.execute(
            "UPDATE client_devices SET registered_protocol_version=?", (value,)
        )
        connection.commit()
    before = database_dump(previous)
    with pytest.raises(RuntimeError, match="protocol evidence exists"):
        command.downgrade(alembic_config(str(previous)), "000000000005")
    assert database_dump(previous) == before


def test_late_migration_failure_rolls_back_column_version_and_all_rows_then_retries(
    previous,
):
    before = database_dump(previous)

    def fail(connection, _cursor, statement, _params, _context, _many):
        if (
            connection.engine.url.database == str(previous)
            and "UPDATE alembic_version" in statement
        ):
            raise RuntimeError("injected device proof checkpoint")

    event.listen(Engine, "before_cursor_execute", fail)
    try:
        with pytest.raises(RuntimeError, match="device proof checkpoint"):
            command.upgrade(alembic_config(str(previous)), "head")
    finally:
        event.remove(Engine, "before_cursor_execute", fail)
    assert database_dump(previous) == before
    command.upgrade(alembic_config(str(previous)), "head")
    command.check(alembic_config(str(previous)))


def test_offline_downgrade_cannot_bypass_proof_guard(tmp_path):
    with pytest.raises(RuntimeError, match="online evidence check"):
        command.downgrade(
            alembic_config(str(tmp_path / "unused.sqlite")),
            "000000000006:000000000005",
            sql=True,
        )
    assert not (tmp_path / "unused.sqlite").exists()


@pytest.mark.parametrize("proof", [None, 4, 5])
@pytest.mark.parametrize("kind", ["physical", "logical"])
def test_registration_proof_roundtrips_without_guessing_or_changing_owned_identity(
    tmp_path, proof, kind
):
    source, target = tmp_path / "source.db", tmp_path / "target.db"
    seed_appearance(source)
    with closing(sqlite3.connect(source)) as connection:
        connection.execute(
            "UPDATE client_devices SET registered_protocol_version=?,app_version='5'",
            (proof,),
        )
        connection.commit()
        expected = connection.execute(
            "SELECT users.public_id,client_devices.public_id,installation_id,registered_protocol_version "
            "FROM client_devices JOIN users ON users.id=client_devices.user_id"
        ).fetchall()
    before = database_dump(source)
    if kind == "physical":
        backup, _ = create_backup(source, tmp_path / "backups", kind="manual")
        _, epoch = restore_backup(backup, target, expected_alembic_head="000000000006")
        with closing(sqlite3.connect(source)) as connection:
            old_epoch = connection.execute(
                "SELECT sync_epoch FROM server_instances"
            ).fetchone()[0]
        assert [
            line.replace(epoch, old_epoch) for line in database_dump(target)
        ] == before
    else:
        archive = export_archive(f"sqlite:///{source}", tmp_path / "archive.zip")
        import_archive(migrate(target), archive)
    with closing(sqlite3.connect(target)) as connection:
        assert (
            connection.execute(
                "SELECT users.public_id,client_devices.public_id,installation_id,registered_protocol_version "
                "FROM client_devices JOIN users ON users.id=client_devices.user_id"
            ).fetchall()
            == expected
        )
        assert connection.execute("PRAGMA foreign_key_check").fetchall() == []
    assert database_dump(source) == before
