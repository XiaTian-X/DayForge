"""Versioned SQLite + immutable asset backup and maintenance restore orchestration."""

from contextlib import ExitStack, closing, contextmanager
from dataclasses import dataclass
from datetime import UTC, datetime
from io import BytesIO
import os
from pathlib import Path
import sqlite3
from tempfile import TemporaryDirectory
from typing import Any, Iterator
from uuid import uuid4

from src.storage.asset_archive import ArchiveBlob, AssetArchive, write_asset_archive
from src.storage.asset_files import AssetFiles
from src.storage.asset_root import AssetRootBusy, AssetRootLease, scan_installations
from src.storage.backup_files import (
    checked_digest,
    checked_size,
    durable_file,
    file_fingerprint,
    publish_new,
    read_manifest,
    regular_file,
    sync_directory,
    verify_stream,
    write_manifest,
)
from src.storage.errors import StorageValidationError
from src.storage.sqlite_inspection import (
    DatabaseInspection,
    _copy_with_sqlite_backup,
    _inspect_database,
    inspect_database,
)


BACKUP_FORMAT_VERSION = 2
BACKUP_PREFIX = "dayforge-"


def _manifest_for(
    database_file: Path, inspection: DatabaseInspection, *, kind: str
) -> dict[str, Any]:
    size, digest = file_fingerprint(database_file)
    return {
        "format_version": BACKUP_FORMAT_VERSION,
        "kind": kind,
        "created_at": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        "database_file": database_file.name,
        "database_size": size,
        "database_sha256": digest,
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


def _valid(inspection: DatabaseInspection) -> None:
    if not inspection.valid:
        raise StorageValidationError(
            f"backup integrity, foreign-key or domain checks failed: integrity={inspection.integrity_check}, "
            f"foreign_keys={len(inspection.foreign_key_errors)}, domain_errors={inspection.domain_errors}"
        )
    if (
        not all(
            (
                inspection.alembic_head,
                inspection.server_instance_id,
                inspection.sync_epoch,
            )
        )
        or type(inspection.protocol_version) is not int
    ):
        raise StorageValidationError(
            "backup database has no canonical schema or server identity"
        )


@contextmanager
def _errors() -> Iterator[None]:
    try:
        yield
    except (ValueError, OSError, sqlite3.Error) as error:
        raise StorageValidationError(
            "backup component validation or I/O failed"
        ) from error


def create(
    database: Path,
    directory: Path,
    *,
    kind: str,
    apply_retention: bool,
    asset_root: Path | None,
) -> tuple[Path, Path]:
    if kind not in ("manual", "scheduled", "pre_restore"):
        raise StorageValidationError("unsupported backup kind")
    directory = directory.resolve()
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / f"dayforge-{datetime.now(UTC):%Y%m%dT%H%M%SZ}-{uuid4().hex}.db"
    manifest_path = target.with_name(target.name + ".manifest.json")
    with _errors(), TemporaryDirectory(prefix=".backup-", dir=directory) as temporary:
        private = Path(temporary)
        staged = private / "snapshot.db"
        _copy_with_sqlite_backup(database.resolve(), staged)
        entries: list[ArchiveBlob] = []
        inspection = _inspect_database(staged, verify_assets=entries.extend)
        _valid(inspection)
        manifest = _manifest_for(staged, inspection, kind=kind)
        manifest["database_file"] = target.name
        manifest["assets"] = None
        archive_target = target.with_name(target.name + ".assets.zip")
        archive_staged = private / "assets.zip"
        if entries:
            if asset_root is None:
                raise StorageValidationError("appearance bytes require an asset root")
            with archive_staged.open("x+b") as output:
                os.chmod(archive_staged, 0o600)
                write_asset_archive(output, AssetFiles(asset_root), entries)
            size, digest = file_fingerprint(archive_staged)
            manifest["assets"] = dict(
                file=archive_target.name, size=size, sha256=digest, count=len(entries)
            )
        staged_manifest = private / "manifest.json"
        write_manifest(staged_manifest, manifest)
        publish_new(staged, target)
        if entries:
            publish_new(archive_staged, archive_target)
        # This final durable name, not the presence of a .db file, is success.
        publish_new(staged_manifest, manifest_path)
    if apply_retention:
        prune_backups(directory)
    return target, manifest_path


@dataclass(frozen=True)
class VerifiedBackup:
    manifest: dict[str, Any]
    inspection: DatabaseInspection
    database: Path
    entries: tuple[ArchiveBlob, ...]
    archive: AssetArchive | None


@contextmanager
def verified(
    backup: Path,
    *,
    staging_directory: Path | None = None,
) -> Iterator[VerifiedBackup]:
    """Freeze the exact hash-verified DB before SQLite opens it; hold ZIP source.

    No caller may publish references using a later reopening of the source DB.
    The private copy and all archive handles outlive the yielded operation.
    """
    backup = backup.absolute()
    with _errors(), ExitStack() as stack:
        manifest = read_manifest(backup.with_name(backup.name + ".manifest.json"))
        version = manifest.get("format_version")
        if type(version) is not int or version not in (1, 2):
            raise StorageValidationError("unsupported backup format version")
        if manifest.get("database_file") != backup.name:
            raise StorageValidationError("backup manifest filename mismatch")
        private = Path(
            stack.enter_context(
                TemporaryDirectory(prefix=".backup-check-", dir=staging_directory)
            )
        )
        staged = private / "snapshot.db"
        with regular_file(backup) as source, staged.open("x+b") as output:
            os.chmod(staged, 0o600)
            verify_stream(
                source,
                checked_size(manifest.get("database_size")),
                checked_digest(manifest.get("database_sha256")),
                output=output,
            )
            output.flush()
        # A successful write count is not a proof of the bytes SQLite will
        # actually read from disk. Verify the private copy before opening it.
        with regular_file(staged) as saved:
            verify_stream(
                saved,
                checked_size(manifest.get("database_size")),
                checked_digest(manifest.get("database_sha256")),
            )
        entries: list[ArchiveBlob] = []
        inspection = _inspect_database(
            staged, verify_assets=entries.extend if version == 2 else None
        )
        _valid(inspection)
        for key, actual in (
            ("alembic_head", inspection.alembic_head),
            ("server_instance_id", inspection.server_instance_id),
            ("sync_epoch", inspection.sync_epoch),
            ("protocol_version", inspection.protocol_version),
            ("integrity_check", inspection.integrity_check),
            ("foreign_key_errors", inspection.foreign_key_errors),
            ("domain_errors", inspection.domain_errors),
            ("active_timer_count", inspection.active_timer_count),
            ("row_counts", inspection.row_counts),
            ("tombstone_counts", inspection.tombstone_counts),
        ):
            if manifest.get(key) != actual:
                raise StorageValidationError(
                    f"backup {key} does not match its manifest"
                )
        description = manifest.get("assets")
        archive = None
        if entries:
            if not isinstance(description, dict) or set(description) != {
                "file",
                "size",
                "sha256",
                "count",
            }:
                raise StorageValidationError(
                    "appearance backup descriptor is missing or invalid"
                )
            archive_path = backup.with_name(backup.name + ".assets.zip")
            if description["file"] != archive_path.name or checked_size(
                description["count"], maximum=100_000
            ) != len(entries):
                raise StorageValidationError("appearance backup name or count mismatch")
            archive_source = stack.enter_context(regular_file(archive_path))
            verify_stream(archive_source, description["size"], description["sha256"])
            archive = AssetArchive(archive_source, entries)
            archive.verify()
        elif description is not None:
            raise StorageValidationError("undeclared appearance backup sidecar")
        if version == 2 and "assets" not in manifest:
            raise StorageValidationError("appearance backup descriptor is missing")
        yield VerifiedBackup(manifest, inspection, staged, tuple(entries), archive)


def _prepare_files(backup: VerifiedBackup, files: AssetFiles) -> None:
    expected = {entry.name: entry for entry in backup.entries}
    # No application jobs can still be using these intents: the caller holds
    # the same exclusive root lease as the runtime, after maintenance stop.
    pending = scan_installations(files)
    for receipt in pending:
        entry = ArchiveBlob(receipt.owner_public_id, receipt.blob, receipt.profile)
        if expected.get(entry.name) != entry:
            raise StorageValidationError(
                "unrelated appearance installation requires recovery first"
            )
    # Only logs whose complete identity matches this verified backup can be
    # retried. finish never removes final blobs or marks database rows ready.
    for receipt in pending:
        files.finish(receipt)
    if backup.archive is None:
        return
    for entry in backup.entries:
        data = backup.archive.read(entry.name)
        with BytesIO(data) as source:
            receipt = files.publish(entry.owner_public_id, source, entry.blob)
        files.finish(receipt)  # Do not accumulate one journal per backup image.


def restore(
    backup: Path,
    target: Path,
    *,
    cancel_active_timers: bool,
    expected_alembic_head: str | None,
    asset_root: Path | None,
) -> tuple[Path | None, str]:
    target = target.absolute()
    target.parent.mkdir(parents=True, exist_ok=True)
    with _errors(), ExitStack() as stack:
        if target.exists() or target.is_symlink():
            with regular_file(target):
                pass
            if backup.exists() and os.path.samefile(backup, target):
                raise StorageValidationError(
                    "restore destination must not be the selected backup"
                )
        source = stack.enter_context(verified(backup, staging_directory=target.parent))
        if (
            expected_alembic_head is not None
            and source.inspection.alembic_head != expected_alembic_head
        ):
            raise StorageValidationError(
                "backup Alembic head is incompatible with the running application"
            )
        if source.inspection.active_timer_count and not cancel_active_timers:
            raise StorageValidationError(
                "backup contains active timers; rerun with explicit cancellation approval"
            )
        files = None
        if asset_root is not None:
            lease = AssetRootLease(asset_root)
            try:
                lease.acquire()
            except AssetRootBusy as error:
                raise StorageValidationError(
                    "appearance root is in use; stop the service before restore"
                ) from error
            stack.callback(lease.release)
            files = AssetFiles(asset_root)
        elif source.entries:
            raise StorageValidationError("appearance restore requires an asset root")
        safety = None
        if target.exists():
            current = inspect_database(target, asset_root=asset_root)
            _valid(current)
            if current.alembic_head != source.inspection.alembic_head:
                raise StorageValidationError(
                    "backup and current database Alembic heads differ"
                )
            safety, _ = create(
                target,
                target.parent / "backups",
                kind="pre_restore",
                apply_retention=False,
                asset_root=asset_root,
            )
        if files is not None:
            _prepare_files(source, files)
        new_epoch = str(uuid4())
        with closing(sqlite3.connect(source.database)) as connection:
            connection.execute("PRAGMA foreign_keys=ON")
            if cancel_active_timers:
                now = datetime.now(UTC).isoformat().replace("+00:00", "Z")
                connection.execute(
                    "UPDATE timer_sessions SET state='cancelled', ended_at=?, state_changed_at=?, updated_at=?, revision=revision+1 WHERE state IN ('running','paused')",
                    (now, now, now),
                )
            changed = connection.execute(
                "UPDATE server_instances SET sync_epoch=? WHERE id=1", (new_epoch,)
            )
            if changed.rowcount != 1:
                raise StorageValidationError(
                    "restored database has no canonical server identity"
                )
            connection.commit()
        result = inspect_database(source.database, asset_root=asset_root)
        _valid(result)
        if result.active_timer_count:
            raise StorageValidationError(
                "restored database still contains active timers"
            )
        _checkpoint(source.database)
        durable_file(source.database)
        # Checkpoint first: a failed rename must leave the old DB complete even
        # if a previous crashed process left committed frames in its WAL.
        if target.exists():
            _checkpoint(target)
            with regular_file(target) as original:
                os.fsync(original.fileno())
        for suffix in ("-wal", "-shm"):
            target.with_name(target.name + suffix).unlink(missing_ok=True)
        os.replace(source.database, target)
        sync_directory(target.parent)
        return safety, new_epoch


def _checkpoint(path: Path) -> None:
    with closing(
        sqlite3.connect(path.absolute().as_uri() + "?mode=rw", uri=True, timeout=0)
    ) as connection:
        result = connection.execute("PRAGMA wal_checkpoint(TRUNCATE)").fetchone()
        if result is None or result[0] != 0:
            raise StorageValidationError(
                "database is in use; stop the service before restore"
            )


def prune_backups(directory: Path, *, daily: int = 7, weekly: int = 4) -> list[Path]:
    """Keep recent daily files plus one older backup per ISO week; never prune manual backups."""
    if any(type(value) is not int or value < 0 for value in (daily, weekly)):
        raise StorageValidationError("invalid backup retention limits")
    manifests: list[tuple[Path, dict[str, Any], datetime]] = []
    for manifest_path in directory.glob(f"{BACKUP_PREFIX}*.db.manifest.json"):
        try:
            # Derive paths from the actual inventory, never from JSON path text.
            database_file = manifest_path.with_name(
                manifest_path.name.removesuffix(".manifest.json")
            )
            with verified(database_file) as candidate:
                manifest = candidate.manifest
            created = datetime.fromisoformat(
                manifest["created_at"].replace("Z", "+00:00")
            )
            if created.utcoffset() is None:
                continue
        except (
            KeyError,
            TypeError,
            ValueError,
            AttributeError,
            OSError,
            StorageValidationError,
        ):
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
        database_file = manifest_path.with_name(
            manifest_path.name.removesuffix(".manifest.json")
        )
        # Stop advertising completeness before deleting any component.
        manifest_path.unlink(missing_ok=True)
        sync_directory(directory)
        database_file.unlink(missing_ok=True)
        removed.extend([database_file, manifest_path])
        if manifest.get("assets") is not None:
            archive = database_file.with_name(database_file.name + ".assets.zip")
            archive.unlink(missing_ok=True)
            removed.append(archive)
        sync_directory(directory)
    return removed
