"""Executable recovery guarantees for the SQLite deployment path."""

from __future__ import annotations

import json
import sqlite3
from contextlib import closing
from pathlib import Path

import pytest
from src.storage.sqlite_maintenance import (
    StorageValidationError,
    backfill_revision_snapshots,
    create_backup,
    load_and_validate_backup,
    restore_backup,
)


def make_database(path: Path, *, active_timer: bool = False) -> None:
    with closing(sqlite3.connect(path)) as connection:
        connection.executescript(
            """
            PRAGMA foreign_keys=ON;
            CREATE TABLE alembic_version(version_num TEXT PRIMARY KEY NOT NULL);
            INSERT INTO alembic_version VALUES('20260814_revision_merge');
            CREATE TABLE server_instances(
                id INTEGER PRIMARY KEY,
                instance_uuid TEXT NOT NULL UNIQUE,
                sync_epoch TEXT NOT NULL UNIQUE,
                protocol_version INTEGER NOT NULL
            );
            INSERT INTO server_instances VALUES(1, 'instance-1', 'epoch-before', 4);
            CREATE TABLE users(id INTEGER PRIMARY KEY, username TEXT NOT NULL);
            INSERT INTO users VALUES(1, '原始用户');
            CREATE TABLE plan_nodes(
                id INTEGER PRIMARY KEY,
                owner_user_id INTEGER NOT NULL REFERENCES users(id),
                node_kind TEXT NOT NULL,
                parent_node_id INTEGER REFERENCES plan_nodes(id),
                deleted_at TEXT
            );
            INSERT INTO plan_nodes VALUES(1, 1, 'goal', NULL, NULL);
            CREATE TABLE timer_sessions(
                id INTEGER PRIMARY KEY,
                state TEXT NOT NULL,
                ended_at TEXT,
                state_changed_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                revision INTEGER NOT NULL
            );
            CREATE TABLE client_devices(id INTEGER PRIMARY KEY);
            CREATE TABLE sync_changes(
                sequence INTEGER PRIMARY KEY,
                recipient_user_id INTEGER NOT NULL REFERENCES users(id),
                entity_type TEXT NOT NULL,
                entity_uuid TEXT NOT NULL,
                revision INTEGER NOT NULL,
                operation TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                origin_device_id INTEGER REFERENCES client_devices(id),
                origin_operation_id TEXT,
                changed_at TEXT NOT NULL
            );
            CREATE TABLE entity_revision_snapshots(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                owner_user_id INTEGER NOT NULL REFERENCES users(id),
                entity_type TEXT NOT NULL,
                entity_uuid TEXT NOT NULL,
                revision INTEGER NOT NULL,
                operation TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                payload_hash TEXT NOT NULL,
                origin_device_id INTEGER REFERENCES client_devices(id),
                origin_operation_id TEXT,
                created_at TEXT NOT NULL,
                UNIQUE(owner_user_id, entity_type, entity_uuid, revision)
            );
            """
        )
        if active_timer:
            connection.execute(
                "INSERT INTO timer_sessions VALUES(1, 'running', NULL, 'now', 'now', 1)"
            )
        connection.commit()


def test_verified_backup_and_restore_changes_epoch_but_preserves_identity(tmp_path: Path):
    database = tmp_path / "dayforge.db"
    backups = tmp_path / "archive"
    make_database(database)

    backup, manifest_path = create_backup(database, backups, kind="manual")
    manifest, inspection = load_and_validate_backup(backup)
    assert manifest["server_instance_id"] == "instance-1"
    assert manifest["row_counts"]["users"] == 1
    assert inspection.valid
    assert oct(backup.stat().st_mode & 0o777) == "0o600"
    assert oct(manifest_path.stat().st_mode & 0o777) == "0o600"

    with closing(sqlite3.connect(database)) as connection:
        connection.execute("UPDATE users SET username = '恢复前的新值' WHERE id = 1")
        connection.commit()

    safety, new_epoch = restore_backup(backup, database)
    assert safety is not None and safety.is_file()
    assert new_epoch != "epoch-before"
    with closing(sqlite3.connect(database)) as connection:
        assert connection.execute("SELECT username FROM users").fetchone()[0] == "原始用户"
        identity = connection.execute(
            "SELECT instance_uuid, sync_epoch FROM server_instances WHERE id = 1"
        ).fetchone()
        assert identity == ("instance-1", new_epoch)


def test_checksum_and_foreign_key_corruption_are_rejected(tmp_path: Path):
    database = tmp_path / "dayforge.db"
    make_database(database)
    backup, _ = create_backup(database, tmp_path / "archive", kind="manual")

    with backup.open("ab") as output:
        output.write(b"corrupt")
    with pytest.raises(StorageValidationError, match="checksum"):
        load_and_validate_backup(backup)

    invalid = tmp_path / "invalid.db"
    make_database(invalid)
    with closing(sqlite3.connect(invalid)) as connection:
        connection.execute("PRAGMA foreign_keys=OFF")
        connection.execute("INSERT INTO plan_nodes VALUES(2, 999, 'goal', NULL, NULL)")
        connection.commit()
    with pytest.raises(StorageValidationError, match="foreign_keys=1"):
        create_backup(invalid, tmp_path / "invalid-archive")


def test_active_timer_restore_requires_explicit_cancellation(tmp_path: Path):
    source = tmp_path / "source.db"
    target = tmp_path / "target.db"
    make_database(source, active_timer=True)
    make_database(target)
    backup, _ = create_backup(source, tmp_path / "archive", kind="manual")

    with pytest.raises(StorageValidationError, match="active timers"):
        restore_backup(backup, target)

    _, epoch = restore_backup(backup, target, cancel_active_timers=True)
    with closing(sqlite3.connect(target)) as connection:
        timer = connection.execute(
            "SELECT state, ended_at, revision FROM timer_sessions WHERE id = 1"
        ).fetchone()
        assert timer[0] == "cancelled"
        assert timer[1] is not None
        assert timer[2] == 2
        assert connection.execute(
            "SELECT sync_epoch FROM server_instances WHERE id = 1"
        ).fetchone()[0] == epoch


def test_revision_snapshot_backfill_is_idempotent(tmp_path: Path):
    database = tmp_path / "dayforge.db"
    make_database(database)
    payload = json.dumps({"title": "中文目标"}, ensure_ascii=False, sort_keys=True)
    with closing(sqlite3.connect(database)) as connection:
        connection.execute(
            "INSERT INTO sync_changes VALUES(1, 1, 'plan_node', 'node-1', 1, "
            "'upsert', ?, NULL, 'operation-1', '2026-08-14T00:00:00Z')",
            (payload,),
        )
        connection.commit()

    assert backfill_revision_snapshots(database) == 1
    assert backfill_revision_snapshots(database) == 0
    with closing(sqlite3.connect(database)) as connection:
        row = connection.execute(
            "SELECT payload_json, length(payload_hash) FROM entity_revision_snapshots"
        ).fetchone()
        assert row == (payload, 64)
