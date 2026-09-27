"""Stable administrative storage API; SQLite inspection and backup have separate owners."""

from contextlib import closing
import hashlib
from pathlib import Path
import sqlite3
from typing import Any

from src.storage.errors import StorageValidationError as StorageValidationError
from src.storage.sqlite_inspection import (
    DatabaseInspection as DatabaseInspection,
    inspect_database as inspect_database,
)
from src.storage.physical_backup import (
    create as _create_backup,
    restore as _restore_backup,
    verified as _verified_backup,
    prune_backups as prune_backups,
)


def create_backup(
    database_path: Path,
    backup_directory: Path,
    *,
    kind: str = "scheduled",
    apply_retention: bool = True,
    asset_root: Path | None = None,
) -> tuple[Path, Path]:
    return _create_backup(
        database_path,
        backup_directory,
        kind=kind,
        apply_retention=apply_retention,
        asset_root=asset_root,
    )


def load_and_validate_backup(
    backup_file: Path,
) -> tuple[dict[str, Any], DatabaseInspection]:
    with _verified_backup(backup_file) as backup:
        return backup.manifest, backup.inspection


def restore_backup(
    backup_file: Path,
    database_path: Path,
    *,
    cancel_active_timers: bool = False,
    expected_alembic_head: str | None = None,
    asset_root: Path | None = None,
) -> tuple[Path | None, str]:
    return _restore_backup(
        backup_file,
        database_path,
        cancel_active_timers=cancel_active_timers,
        expected_alembic_head=expected_alembic_head,
        asset_root=asset_root,
    )


def backfill_revision_snapshots(database_path: Path) -> int:
    """Create immutable revision bases from retained legacy sync changes."""
    database_path = database_path.resolve()
    inspection = inspect_database(database_path)
    required = {"sync_changes", "entity_revision_snapshots"}
    if not required.issubset(inspection.row_counts):
        raise StorageValidationError(
            "database has not been migrated to revision snapshots"
        )
    inserted = 0
    with closing(sqlite3.connect(database_path)) as connection:
        connection.execute("PRAGMA foreign_keys=ON")
        rows = connection.execute(
            "SELECT recipient_user_id, entity_type, entity_uuid, revision, operation, "
            "payload_json, origin_device_id, origin_operation_id, changed_at "
            "FROM sync_changes ORDER BY sequence"
        ).fetchall()
        for row in rows:
            cursor = connection.execute(
                "INSERT OR IGNORE INTO entity_revision_snapshots("
                "owner_user_id, entity_type, entity_uuid, revision, operation, payload_json, "
                "payload_hash, origin_device_id, origin_operation_id, created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (
                    *row[:6],
                    hashlib.sha256(row[5].encode("utf-8")).hexdigest(),
                    *row[6:],
                ),
            )
            inserted += cursor.rowcount
        connection.commit()
    return inserted
