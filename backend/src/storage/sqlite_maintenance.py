"""Verified SQLite backup and restore operations used by the deployment CLI."""

from __future__ import annotations

import hashlib
import json
import os
import sqlite3
import tempfile
from contextlib import closing
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any
from uuid import uuid4

from src.v2.one_time_recovery import OneTimeRecoveryError, read_one_time_history
from src.v2.asset_recovery import AssetRecoveryError, read_asset_metadata


BACKUP_FORMAT_VERSION = 1
BACKUP_PREFIX = "dayforge-"


class StorageValidationError(RuntimeError):
    """Raised before an unsafe or unverifiable storage operation."""


@dataclass(frozen=True)
class DatabaseInspection:
    integrity_check: str
    foreign_key_errors: list[dict[str, Any]]
    domain_errors: list[str]
    alembic_head: str | None
    server_instance_id: str | None
    sync_epoch: str | None
    protocol_version: int | None
    active_timer_count: int
    row_counts: dict[str, int]
    tombstone_counts: dict[str, int]

    @property
    def valid(self) -> bool:
        return (
            self.integrity_check == "ok"
            and not self.foreign_key_errors
            and not self.domain_errors
        )


def _utc_stamp() -> str:
    return datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _table_names(connection: sqlite3.Connection) -> list[str]:
    return [
        row[0]
        for row in connection.execute(
            "SELECT name FROM sqlite_master "
            "WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
        )
    ]


def inspect_database(path: Path) -> DatabaseInspection:
    path = path.resolve()
    if not path.is_file():
        raise StorageValidationError(f"database not found: {path}")
    with closing(sqlite3.connect(f"file:{path}?mode=ro", uri=True)) as connection:
        connection.row_factory = sqlite3.Row
        connection.execute("BEGIN")  # All inspection fields describe one snapshot.
        integrity_rows = connection.execute("PRAGMA integrity_check").fetchall()
        integrity = (
            "ok"
            if len(integrity_rows) == 1 and integrity_rows[0][0] == "ok"
            else "; ".join(str(row[0]) for row in integrity_rows)
        )
        foreign_keys = [
            dict(row) for row in connection.execute("PRAGMA foreign_key_check")
        ]
        tables = _table_names(connection)
        row_counts = {
            table: int(
                connection.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0]
            )
            for table in tables
        }
        tombstone_counts: dict[str, int] = {}
        for table in tables:
            columns = {
                row[1] for row in connection.execute(f'PRAGMA table_info("{table}")')
            }
            if "deleted_at" in columns:
                tombstone_counts[table] = int(
                    connection.execute(
                        f'SELECT COUNT(*) FROM "{table}" WHERE deleted_at IS NOT NULL'
                    ).fetchone()[0]
                )

        alembic_head = None
        if "alembic_version" in tables:
            row = connection.execute(
                "SELECT version_num FROM alembic_version"
            ).fetchone()
            alembic_head = row[0] if row else None

        identity = None
        if "server_instances" in tables:
            identity = connection.execute(
                "SELECT instance_uuid, sync_epoch, protocol_version FROM server_instances WHERE id = 1"
            ).fetchone()
        active_timers = 0
        if "timer_sessions" in tables:
            active_timers = int(
                connection.execute(
                    "SELECT COUNT(*) FROM timer_sessions WHERE state IN ('running','paused')"
                ).fetchone()[0]
            )

        domain_errors: list[str] = []
        if "appearance_accounts" in tables:
            try:
                read_asset_metadata(
                    lambda statement: [
                        dict(row) for row in connection.execute(statement)
                    ]
                )
            except AssetRecoveryError as error:
                domain_errors.append(f"invalid appearance metadata: {error}")
        if "activity_details" in tables:
            detail_columns = {
                row[1]
                for row in connection.execute('PRAGMA table_info("activity_details")')
            }
            if "one_time_version" in detail_columns:
                try:
                    read_one_time_history(
                        lambda statement, parameters: [
                            dict(row)
                            for row in connection.execute(statement, parameters)
                        ]
                    )
                except OneTimeRecoveryError as error:
                    domain_errors.append(f"invalid one-time history: {error}")
        if "server_instances" in tables and row_counts["server_instances"] != 1:
            domain_errors.append("server_instances must contain exactly one row")
        if "plan_nodes" in tables:
            invalid_goals = connection.execute(
                "SELECT COUNT(*) FROM plan_nodes "
                "WHERE node_kind = 'goal' AND parent_node_id IS NOT NULL"
            ).fetchone()[0]
            if invalid_goals:
                domain_errors.append(f"{invalid_goals} goal nodes have a parent")
            invalid_parents = connection.execute(
                "SELECT COUNT(*) FROM plan_nodes child "
                "JOIN plan_nodes parent ON parent.id = child.parent_node_id "
                "WHERE child.node_kind != 'activity' OR parent.node_kind != 'goal' "
                "OR parent.parent_node_id IS NOT NULL"
            ).fetchone()[0]
            if invalid_parents:
                domain_errors.append(
                    f"{invalid_parents} plan nodes violate the single-parent hierarchy"
                )
        if "entity_revision_snapshots" in tables:
            duplicate_snapshots = connection.execute(
                "SELECT COUNT(*) FROM ("
                "SELECT 1 FROM entity_revision_snapshots "
                "GROUP BY owner_user_id, entity_type, entity_uuid, revision HAVING COUNT(*) > 1)"
            ).fetchone()[0]
            if duplicate_snapshots:
                domain_errors.append(
                    f"{duplicate_snapshots} duplicate revision snapshots"
                )

        return DatabaseInspection(
            integrity_check=integrity,
            foreign_key_errors=foreign_keys,
            domain_errors=domain_errors,
            alembic_head=alembic_head,
            server_instance_id=identity[0] if identity else None,
            sync_epoch=identity[1] if identity else None,
            protocol_version=int(identity[2]) if identity else None,
            active_timer_count=active_timers,
            row_counts=row_counts,
            tombstone_counts=tombstone_counts,
        )


def _copy_with_sqlite_backup(source: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    with (
        closing(sqlite3.connect(source)) as source_db,
        closing(sqlite3.connect(target)) as target_db,
    ):
        source_db.backup(target_db)


def _manifest_for(
    database_file: Path, inspection: DatabaseInspection, *, kind: str
) -> dict[str, Any]:
    return {
        "format_version": BACKUP_FORMAT_VERSION,
        "kind": kind,
        "created_at": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        "database_file": database_file.name,
        "database_size": database_file.stat().st_size,
        "database_sha256": _sha256(database_file),
        "alembic_head": inspection.alembic_head,
        "server_instance_id": inspection.server_instance_id,
        "sync_epoch": inspection.sync_epoch,
        "protocol_version": inspection.protocol_version,
        "integrity_check": inspection.integrity_check,
        "foreign_key_errors": inspection.foreign_key_errors,
        "domain_errors": inspection.domain_errors,
        "active_timer_count": inspection.active_timer_count,
        "row_counts": inspection.row_counts,
        "tombstone_counts": inspection.tombstone_counts,
    }


def _write_manifest(path: Path, manifest: dict[str, Any]) -> Path:
    manifest_path = path.with_suffix(path.suffix + ".manifest.json")
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    os.chmod(path, 0o600)
    os.chmod(manifest_path, 0o600)
    return manifest_path


def _discard_temporary_database(path: Path) -> None:
    """Only caller-created private temporaries, after all connections are closed.

    Read-only inspection of a WAL-mode copy can leave empty WAL/SHM companions.
    Never call this for the source database or an installed/active restore target.
    """
    for candidate in (
        path,
        path.with_name(path.name + "-wal"),
        path.with_name(path.name + "-shm"),
    ):
        candidate.unlink(missing_ok=True)


def create_backup(
    database_path: Path,
    backup_directory: Path,
    *,
    kind: str = "scheduled",
    apply_retention: bool = True,
) -> tuple[Path, Path]:
    database_path = database_path.resolve()
    backup_directory = backup_directory.resolve()
    backup_directory.mkdir(parents=True, exist_ok=True)
    target = backup_directory / f"{BACKUP_PREFIX}{_utc_stamp()}-{uuid4().hex[:8]}.db"
    temporary = target.with_suffix(".db.incomplete")
    try:
        _copy_with_sqlite_backup(database_path, temporary)
        inspection = inspect_database(temporary)
        if not inspection.valid:
            raise StorageValidationError(
                f"backup validation failed: integrity={inspection.integrity_check}, "
                f"foreign_keys={len(inspection.foreign_key_errors)}, "
                f"domain_errors={inspection.domain_errors}"
            )
        os.replace(temporary, target)
        manifest_path = _write_manifest(
            target, _manifest_for(target, inspection, kind=kind)
        )
    finally:
        _discard_temporary_database(temporary)
    if apply_retention:
        prune_backups(backup_directory)
    return target, manifest_path


def load_and_validate_backup(
    backup_file: Path,
) -> tuple[dict[str, Any], DatabaseInspection]:
    backup_file = backup_file.resolve()
    manifest_path = backup_file.with_suffix(backup_file.suffix + ".manifest.json")
    if not backup_file.is_file() or not manifest_path.is_file():
        raise StorageValidationError("backup database and manifest are both required")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("format_version") != BACKUP_FORMAT_VERSION:
        raise StorageValidationError("unsupported backup format version")
    if manifest.get("database_file") != backup_file.name:
        raise StorageValidationError("backup manifest filename mismatch")
    if manifest.get("database_sha256") != _sha256(backup_file):
        raise StorageValidationError("backup checksum mismatch")
    inspection = inspect_database(backup_file)
    if not inspection.valid:
        raise StorageValidationError(
            "backup database failed integrity, foreign-key, or domain checks"
        )
    if manifest.get("alembic_head") != inspection.alembic_head:
        raise StorageValidationError("backup Alembic head does not match its manifest")
    if manifest.get("server_instance_id") != inspection.server_instance_id:
        raise StorageValidationError(
            "backup server identity does not match its manifest"
        )
    return manifest, inspection


def restore_backup(
    backup_file: Path,
    database_path: Path,
    *,
    cancel_active_timers: bool = False,
    expected_alembic_head: str | None = None,
) -> tuple[Path | None, str]:
    backup_file = backup_file.resolve()
    database_path = database_path.resolve()
    _, source_inspection = load_and_validate_backup(backup_file)
    if (
        expected_alembic_head is not None
        and source_inspection.alembic_head != expected_alembic_head
    ):
        raise StorageValidationError(
            "backup Alembic head is incompatible with the running application"
        )
    if source_inspection.active_timer_count and not cancel_active_timers:
        raise StorageValidationError(
            "backup contains active timers; rerun with explicit cancellation approval"
        )

    safety_backup: Path | None = None
    if database_path.exists():
        target_inspection = inspect_database(database_path)
        if target_inspection.alembic_head != source_inspection.alembic_head:
            raise StorageValidationError(
                "backup and current database Alembic heads differ"
            )
        safety_backup, _ = create_backup(
            database_path,
            database_path.parent / "backups",
            kind="pre_restore",
            apply_retention=False,
        )

    database_path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{database_path.name}.restore-",
        dir=database_path.parent,
    )
    os.close(descriptor)
    temporary = Path(temporary_name)
    temporary.unlink(missing_ok=True)
    new_epoch = str(uuid4())
    try:
        _copy_with_sqlite_backup(backup_file, temporary)
        with closing(sqlite3.connect(temporary)) as connection:
            connection.execute("PRAGMA foreign_keys=ON")
            if cancel_active_timers:
                now = datetime.now(UTC).isoformat().replace("+00:00", "Z")
                connection.execute(
                    "UPDATE timer_sessions SET state = 'cancelled', ended_at = ?, "
                    "state_changed_at = ?, updated_at = ?, revision = revision + 1 "
                    "WHERE state IN ('running','paused')",
                    (now, now, now),
                )
            changed = connection.execute(
                "UPDATE server_instances SET sync_epoch = ? WHERE id = 1",
                (new_epoch,),
            )
            if changed.rowcount != 1:
                raise StorageValidationError(
                    "restored database has no canonical server identity"
                )
            connection.commit()
        restored_inspection = inspect_database(temporary)
        if not restored_inspection.valid or restored_inspection.active_timer_count:
            raise StorageValidationError(
                "restored database failed post-restore validation"
            )
        os.chmod(temporary, 0o600)
        os.replace(temporary, database_path)
        database_path.with_name(database_path.name + "-wal").unlink(missing_ok=True)
        database_path.with_name(database_path.name + "-shm").unlink(missing_ok=True)
    finally:
        _discard_temporary_database(temporary)
    return safety_backup, new_epoch


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


def prune_backups(directory: Path, *, daily: int = 7, weekly: int = 4) -> list[Path]:
    """Keep recent daily files plus one older backup per ISO week; never prune manual backups."""
    manifests: list[tuple[Path, dict[str, Any], datetime]] = []
    for manifest_path in directory.glob(f"{BACKUP_PREFIX}*.db.manifest.json"):
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            created = datetime.fromisoformat(
                manifest["created_at"].replace("Z", "+00:00")
            )
        except (KeyError, ValueError, json.JSONDecodeError):
            continue
        manifests.append((manifest_path, manifest, created))
    manifests.sort(key=lambda item: item[2], reverse=True)
    keep: set[Path] = set()
    keep.update(item[0] for item in manifests if item[1].get("kind") == "manual")
    scheduled = [item for item in manifests if item[1].get("kind") == "scheduled"]
    keep.update(item[0] for item in scheduled[:daily])
    seen_weeks: set[tuple[int, int]] = set()
    for manifest_path, _, created in scheduled[daily:]:
        week = (created.isocalendar().year, created.isocalendar().week)
        if week not in seen_weeks and len(seen_weeks) < weekly:
            keep.add(manifest_path)
            seen_weeks.add(week)
    removed: list[Path] = []
    for manifest_path, manifest, _ in manifests:
        if manifest_path in keep or manifest.get("kind") != "scheduled":
            continue
        database_file = directory / str(manifest.get("database_file", ""))
        database_file.unlink(missing_ok=True)
        manifest_path.unlink(missing_ok=True)
        removed.extend([database_file, manifest_path])
    return removed
