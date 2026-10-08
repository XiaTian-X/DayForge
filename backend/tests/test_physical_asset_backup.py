"""Actual migrated SQLite + PNG/SVG file closure; no deployment data or Docker."""

import base64
from contextlib import closing
import hashlib
from io import BytesIO
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import zipfile

import pytest

from src.storage import physical_backup as physical
from src.storage.asset_archive import ArchiveBlob
from src.storage.asset_files import AssetFiles
from src.storage.asset_root import AssetRootLease
from src.storage.sqlite_maintenance import (
    StorageValidationError,
    create_backup,
    inspect_database,
    load_and_validate_backup,
    restore_backup,
    prune_backups,
)
from tests.asset_file_fixtures import SVG, blob
from tests.test_asset_storage import seed_appearance, encoded, STAMP
from tests.test_logical_archive_identity import database_dump


def seed_ready(path, root, *, second="svg", pending=False):
    seed_appearance(path)
    root.mkdir()
    png_cases = json.loads(
        (Path(__file__).resolve().parents[2] / "contracts/next/png.json").read_text()
    )
    png = base64.b64decode(
        next(case["png"] for case in png_cases if case["name"] == "rgba")
    )
    expected = []
    with closing(sqlite3.connect(path)) as connection:
        for owner, data in ((101, SVG), (205, png if second == "png" else SVG)):
            description = blob(data, "image/png" if data == png else "image/svg+xml")
            profile = "png-v1" if data == png else "svg-v1"
            public = connection.execute(
                "SELECT public_id FROM users WHERE id=?", (owner,)
            ).fetchone()[0]
            entry = ArchiveBlob(public, description, profile)
            ready = not (owner == 205 and pending)
            connection.execute(
                "UPDATE account_icon_blobs SET sha256=?,byte_length=?,media_type=?,width=1,height=1,ready_at=?,validation_profile=? WHERE owner_user_id=?",
                (
                    description.sha256,
                    len(data),
                    description.media_type,
                    STAMP if ready else None,
                    profile if ready else None,
                    owner,
                ),
            )

            def replace(value):
                if isinstance(value, dict):
                    return (
                        description.model_dump()
                        if "sha256" in value
                        else {key: replace(item) for key, item in value.items()}
                    )
                if isinstance(value, list):
                    return [replace(item) for item in value]
                return value

            charge = 0
            for table in ("account_icon_assets", "account_icon_packs"):
                for identity, content in connection.execute(
                    f"SELECT id, metadata_json FROM {table} WHERE owner_user_id=?",
                    (owner,),
                ).fetchall():
                    content = encoded(replace(json.loads(content)))
                    length = len(content.encode("utf-8"))
                    charge += length
                    connection.execute(
                        f"UPDATE {table} SET metadata_json=?,metadata_bytes=? WHERE id=?",
                        (content, length, identity),
                    )
            connection.execute(
                "UPDATE appearance_accounts SET reserved_bytes=?,reserved_metadata_bytes=? WHERE user_id=?",
                (len(data), charge, owner),
            )
            if ready:
                with BytesIO(data) as source:
                    receipt = AssetFiles(root).publish(public, source, description)
                AssetFiles(root).finish(receipt)
                expected.append((entry, data))
        connection.commit()
    assert inspect_database(path, asset_root=root).valid
    assert not inspect_database(path).valid
    return expected


def asset_path(root, entry):
    return root / entry.name


def backup_paths(backup):
    return backup.with_name(backup.name + ".manifest.json"), backup.with_name(
        backup.name + ".assets.zip"
    )


@pytest.mark.parametrize(
    "second,pending", [("svg", False), ("png", False), ("svg", True)]
)
def test_complete_byte_round_trip_keeps_all_tables_ownership_and_pending(
    tmp_path, second, pending
):
    source, root = tmp_path / "source.db", tmp_path / "assets"
    entries = seed_ready(source, root, second=second, pending=pending)
    before = database_dump(source)
    backup, manifest_path = create_backup(
        source, tmp_path / "backups", asset_root=root, kind="manual"
    )
    manifest, inspection = load_and_validate_backup(backup)
    assert manifest["format_version"] == 2 and manifest["assets"]["count"] == len(
        entries
    )
    assert inspection.valid
    for component in (backup, manifest_path, backup_paths(backup)[1]):
        assert component.stat().st_mode & 0o777 == 0o600
    target, target_root = tmp_path / "restored.db", tmp_path / "restored-assets"
    target_root.mkdir()
    safety, epoch = restore_backup(
        backup, target, asset_root=target_root, expected_alembic_head="000000000008"
    )
    assert safety is None and epoch != inspection.sync_epoch
    after = database_dump(target)
    assert [line.replace(epoch, inspection.sync_epoch) for line in after] == before
    for entry, data in entries:
        assert (
            AssetFiles(target_root).read(
                entry.owner_public_id, entry.blob, entry.profile
            )
            == data
        )
    assert not list(target_root.rglob(".install-*"))
    assert not list(tmp_path.glob(".backup-check-*"))
    again, _ = create_backup(
        target, tmp_path / "again", asset_root=target_root, kind="manual"
    )
    assert load_and_validate_backup(again)[1].sync_epoch == epoch


@pytest.mark.parametrize("damage", ["missing", "corrupt", "profile", "timestamp"])
def test_missing_corrupt_or_unknown_ready_data_never_publishes_complete_backup(
    tmp_path, damage
):
    source, root = tmp_path / "source.db", tmp_path / "assets"
    entries = seed_ready(source, root)
    entry, _ = entries[0]
    if damage == "missing":
        asset_path(root, entry).unlink()
    elif damage == "corrupt":
        asset_path(root, entry).write_bytes(b"broken")
    else:
        with closing(sqlite3.connect(source)) as connection:
            connection.execute("PRAGMA ignore_check_constraints=ON")
            field, value = (
                ("validation_profile", "svg-unknown")
                if damage == "profile"
                else ("ready_at", "invalid")
            )
            connection.execute(
                f"UPDATE account_icon_blobs SET {field}=? WHERE owner_user_id=101",
                (value,),
            )
            connection.commit()
    before = database_dump(source)
    with pytest.raises(StorageValidationError):
        create_backup(source, tmp_path / "backups", asset_root=root)
    assert list((tmp_path / "backups").iterdir()) == []
    assert database_dump(source) == before


def test_in_place_restore_has_complete_safety_backup_and_fails_on_bad_current_file(
    tmp_path,
):
    source, root = tmp_path / "source.db", tmp_path / "assets"
    entries = seed_ready(source, root, second="png")
    backup, _ = create_backup(
        source, tmp_path / "archive", asset_root=root, kind="manual"
    )
    with closing(sqlite3.connect(source)) as connection:
        connection.execute(
            "UPDATE plan_nodes SET title='after backup' WHERE id=(SELECT MIN(id) FROM plan_nodes)"
        )
        connection.commit()
    changed = database_dump(source)
    entry, data = entries[0]
    asset_path(root, entry).write_bytes(b"corrupt current data")
    with pytest.raises(StorageValidationError):
        restore_backup(backup, source, asset_root=root)
    assert database_dump(source) == changed
    assert asset_path(root, entry).read_bytes() == b"corrupt current data"
    # A separate verified destination is the repair route; it doesn't overwrite
    # the broken current root or invent a safety backup for missing bytes.
    new_root = tmp_path / "new-root"
    new_root.mkdir()
    restore_backup(backup, tmp_path / "new.db", asset_root=new_root)
    assert asset_path(new_root, entry).read_bytes() == data
    assert asset_path(root, entry).read_bytes() == b"corrupt current data"
    # Synthetic test repair only, then exercise normal in-place safety closure.
    asset_path(root, entry).write_bytes(data)
    safety, epoch = restore_backup(backup, source, asset_root=root)
    assert safety is not None and load_and_validate_backup(safety)[1].valid
    assert database_dump(safety) == changed
    assert inspect_database(source, asset_root=root).sync_epoch == epoch


@pytest.mark.parametrize(
    "damage",
    ["missing", "truncate", "extra", "digest", "count", "path", "version", "no-root"],
)
def test_invalid_archive_is_rejected_before_target_or_asset_writes(tmp_path, damage):
    source, root = tmp_path / "source.db", tmp_path / "assets"
    seed_ready(source, root)
    backup, manifest_file = create_backup(
        source, tmp_path / "archive", asset_root=root, kind="manual"
    )
    _, archive = backup_paths(backup)
    manifest = json.loads(manifest_file.read_text())
    if damage == "missing":
        archive.unlink()
    elif damage == "truncate":
        archive.write_bytes(archive.read_bytes()[:-1])
    elif damage == "extra":
        archive.write_bytes(archive.read_bytes() + b"tail")
    elif damage == "digest":
        manifest["assets"]["sha256"] = "0" * 64
    elif damage == "count":
        manifest["assets"]["count"] = True
    elif damage == "path":
        manifest["assets"]["file"] = "../outside.zip"
    elif damage == "version":
        manifest["format_version"] = 1
    manifest_file.write_text(json.dumps(manifest))
    target = tmp_path / "target.db"
    destination = tmp_path / "destination"
    destination.mkdir()
    with pytest.raises(StorageValidationError):
        restore_backup(
            backup, target, asset_root=None if damage == "no-root" else destination
        )
    assert not target.exists() and list(destination.iterdir()) == []


def test_root_lease_blocks_restore_without_mutating_current_database(tmp_path):
    source, root = tmp_path / "source.db", tmp_path / "assets"
    seed_ready(source, root)
    backup, _ = create_backup(
        source, tmp_path / "archive", asset_root=root, kind="manual"
    )
    before = database_dump(source)
    lease = AssetRootLease(root)
    lease.acquire()
    try:
        with pytest.raises(StorageValidationError, match="root is in use"):
            restore_backup(backup, source, asset_root=root)
    finally:
        lease.release()
    assert database_dump(source) == before


def test_backup_source_path_replacement_after_validation_does_not_change_restored_facts(
    tmp_path, monkeypatch
):
    source, root = tmp_path / "source.db", tmp_path / "assets"
    seed_ready(source, root)
    backup, _ = create_backup(
        source, tmp_path / "archive", asset_root=root, kind="manual"
    )
    before = database_dump(source)
    original = physical._prepare_files

    def replace_source(verified, files):
        # The actual source path is now hostile, but the validated private copy
        # is what gets the epoch update and atomic publication.
        backup.write_bytes(b"not a database any longer")
        return original(verified, files)

    monkeypatch.setattr(physical, "_prepare_files", replace_source)
    destination = tmp_path / "destination"
    destination.mkdir()
    target = tmp_path / "target.db"
    _, epoch = restore_backup(backup, target, asset_root=destination)
    original_epoch = inspect_database(source, asset_root=root).sync_epoch
    assert [
        line.replace(epoch, original_epoch) for line in database_dump(target)
    ] == before


def test_manifest_last_failure_does_not_run_retention(tmp_path, monkeypatch):
    source, root = tmp_path / "source.db", tmp_path / "assets"
    seed_ready(source, root)
    original = physical.publish_new

    def fail(staged, target):
        if target.name.endswith(".manifest.json"):
            raise OSError("synthetic manifest publication failure")
        return original(staged, target)

    monkeypatch.setattr(physical, "publish_new", fail)
    monkeypatch.setattr(
        physical, "prune_backups", lambda *_: pytest.fail("pruned incomplete backup")
    )
    with pytest.raises(StorageValidationError):
        create_backup(source, tmp_path / "archive", asset_root=root)
    assert not list((tmp_path / "archive").glob("*.manifest.json"))
    assert len(list((tmp_path / "archive").glob("*.db"))) == 1
    assert len(list((tmp_path / "archive").glob("*.assets.zip"))) == 1
    assert not list((tmp_path / "archive").glob(".backup-*"))


def test_retention_removes_only_complete_matched_components_and_never_json_paths(
    tmp_path,
):
    source, root = tmp_path / "source.db", tmp_path / "assets"
    seed_ready(source, root)
    directory = tmp_path / "archive"
    scheduled, _ = create_backup(
        source, directory, asset_root=root, apply_retention=False
    )
    manual, _ = create_backup(
        source, directory, asset_root=root, kind="manual", apply_retention=False
    )
    victim = tmp_path / "keep.db"
    victim.write_bytes(b"outside")
    bad = directory / "dayforge-hostile.db.manifest.json"
    bad.write_text(
        json.dumps(
            {
                "database_file": "../keep.db",
                "format_version": 2,
                "created_at": "2026-09-27T00:00:00Z",
                "kind": "scheduled",
            }
        )
    )
    removed = prune_backups(directory, daily=0, weekly=0)
    assert set(removed) == {scheduled, *backup_paths(scheduled)}
    assert victim.read_bytes() == b"outside" and bad.exists()
    assert load_and_validate_backup(manual)[1].valid


def leave_committed_wal(database):
    subprocess.run(
        [
            sys.executable,
            "-c",
            """
import os, sqlite3, sys
db=sqlite3.connect(sys.argv[1])
db.execute('PRAGMA journal_mode=WAL')
db.execute('PRAGMA wal_autocheckpoint=0')
db.execute("UPDATE plan_nodes SET title='committed WAL value' WHERE id=(SELECT MIN(id) FROM plan_nodes)")
db.commit()
os._exit(0)
""",
            str(database),
        ],
        check=True,
        timeout=10,
    )
    wal = database.with_name(database.name + "-wal")
    assert wal.stat().st_size > 32
    return wal


@pytest.mark.parametrize("stage", ["checkpoint", "replace"])
def test_failed_restore_preserves_committed_old_wal_facts(tmp_path, monkeypatch, stage):
    source, root = tmp_path / "source.db", tmp_path / "assets"
    seed_ready(source, root)
    backup, _ = create_backup(
        source, tmp_path / "archive", asset_root=root, kind="manual"
    )
    wal = leave_committed_wal(source)
    old_wal = wal.read_bytes()
    if stage == "replace":
        original = physical.os.replace

        def fail(old, new, *args, **kwargs):
            if Path(new) == source:
                raise OSError("synthetic database replacement failure")
            return original(old, new, *args, **kwargs)

        monkeypatch.setattr(physical.os, "replace", fail)
    else:
        checkpoint = physical._checkpoint

        def busy(path):
            if path == source:
                raise StorageValidationError("synthetic busy checkpoint")
            checkpoint(path)

        monkeypatch.setattr(physical, "_checkpoint", busy)
    with pytest.raises(StorageValidationError):
        restore_backup(backup, source, asset_root=root)
    if stage == "checkpoint":
        assert wal.read_bytes() == old_wal
    with closing(sqlite3.connect(source)) as connection:
        assert connection.execute(
            "SELECT title FROM plan_nodes ORDER BY id LIMIT 1"
        ).fetchone() == ("committed WAL value",)
    safety = next((tmp_path / "backups").glob("*.db"))
    assert load_and_validate_backup(safety)[1].valid


def test_install_cleanup_failure_is_retryable_without_publishing_database(
    tmp_path, monkeypatch
):
    source, root = tmp_path / "source.db", tmp_path / "assets"
    entries = seed_ready(source, root, second="png")
    backup, _ = create_backup(
        source, tmp_path / "archive", asset_root=root, kind="manual"
    )
    target, destination = tmp_path / "target.db", tmp_path / "destination"
    destination.mkdir()
    finish = AssetFiles.finish

    def fail(self, receipt):
        raise OSError("synthetic cleanup failure")

    monkeypatch.setattr(AssetFiles, "finish", fail)
    with pytest.raises(StorageValidationError):
        restore_backup(backup, target, asset_root=destination)
    assert not target.exists() and len(list(destination.rglob(".install-*.json"))) == 1
    monkeypatch.setattr(AssetFiles, "finish", finish)
    restore_backup(backup, target, asset_root=destination)
    assert inspect_database(target, asset_root=destination).valid
    assert not list(destination.rglob(".install-*"))
    for entry, data in entries:
        assert asset_path(destination, entry).read_bytes() == data


def test_unrelated_pending_installation_is_not_deleted_for_restore(tmp_path):
    source, root = tmp_path / "source.db", tmp_path / "assets"
    entries = seed_ready(source, root)
    backup, _ = create_backup(
        source, tmp_path / "archive", asset_root=root, kind="manual"
    )
    destination = tmp_path / "destination"
    destination.mkdir()
    data = SVG.replace(b"<rect", b'<rect x="0"')
    foreign = AssetFiles(destination).publish(
        entries[0][0].owner_public_id, BytesIO(data), blob(data)
    )
    before = {
        path.relative_to(destination): path.read_bytes()
        for path in destination.rglob("*")
        if path.is_file()
    }
    with pytest.raises(StorageValidationError, match="requires recovery first"):
        restore_backup(backup, tmp_path / "target.db", asset_root=destination)
    for path, data in before.items():
        assert (destination / path).read_bytes() == data
    assert list(destination.rglob(f"{foreign.stem}.json"))
    assert not (tmp_path / "target.db").exists()


def test_directory_sync_failure_after_publication_has_complete_new_db_and_safety_backup(
    tmp_path, monkeypatch
):
    source, root = tmp_path / "source.db", tmp_path / "assets"
    seed_ready(source, root)
    backup, _ = create_backup(
        source, tmp_path / "archive", asset_root=root, kind="manual"
    )
    previous = inspect_database(source, asset_root=root).sync_epoch

    def fail(_directory):
        raise OSError("synthetic final directory sync failure")

    monkeypatch.setattr(physical, "sync_directory", fail)
    with pytest.raises(StorageValidationError):
        restore_backup(backup, source, asset_root=root)
    current = inspect_database(source, asset_root=root)
    assert current.valid and current.sync_epoch != previous
    safety = next((tmp_path / "backups").glob("*.db"))
    assert load_and_validate_backup(safety)[1].sync_epoch == previous


def test_recomputed_zip_digest_cannot_authorize_extra_account_bytes(tmp_path):
    source, root = tmp_path / "source.db", tmp_path / "assets"
    seed_ready(source, root)
    backup, manifest_file = create_backup(
        source, tmp_path / "archive", asset_root=root, kind="manual"
    )
    archive = backup_paths(backup)[1]
    with zipfile.ZipFile(archive, "a", compression=zipfile.ZIP_DEFLATED) as writer:
        writer.writestr("extra-account", b"undeclared")
    manifest = json.loads(manifest_file.read_text())
    manifest["assets"]["size"] = archive.stat().st_size
    manifest["assets"]["sha256"] = hashlib.sha256(archive.read_bytes()).hexdigest()
    manifest_file.write_text(json.dumps(manifest))
    with pytest.raises(StorageValidationError):
        load_and_validate_backup(backup)


def test_concurrent_install_after_sqlite_snapshot_does_not_enter_earlier_backup(
    tmp_path, monkeypatch
):
    source, root = tmp_path / "source.db", tmp_path / "assets"
    seed_ready(source, root, pending=True)
    original = physical._inspect_database
    invoked = False

    def install_after_copy(path, **kwargs):
        nonlocal invoked
        if not invoked:
            invoked = True
            owner = "00000000-0000-4000-8000-000000000002"
            with BytesIO(SVG) as input_file:
                receipt = AssetFiles(root).publish(owner, input_file, blob())
            AssetFiles(root).finish(receipt)
            with closing(sqlite3.connect(source)) as connection:
                connection.execute(
                    "UPDATE account_icon_blobs SET ready_at=?,validation_profile='svg-v1' WHERE owner_user_id=205",
                    (STAMP,),
                )
                connection.commit()
        return original(path, **kwargs)

    monkeypatch.setattr(physical, "_inspect_database", install_after_copy)
    backup, _ = create_backup(
        source, tmp_path / "archive", asset_root=root, kind="manual"
    )
    assert invoked
    manifest, _ = load_and_validate_backup(backup)
    assert manifest["assets"]["count"] == 1
    with closing(sqlite3.connect(backup)) as connection:
        assert connection.execute(
            "SELECT ready_at,validation_profile FROM account_icon_blobs WHERE owner_user_id=205"
        ).fetchone() == (None, None)
    assert inspect_database(source, asset_root=root).valid


def test_actual_reader_lock_rejects_checkpoint_before_deleting_old_wal(tmp_path):
    source, root = tmp_path / "source.db", tmp_path / "assets"
    seed_ready(source, root)
    backup, _ = create_backup(
        source, tmp_path / "archive", asset_root=root, kind="manual"
    )
    with closing(sqlite3.connect(source)) as reader:
        reader.execute("PRAGMA journal_mode=WAL")
        reader.execute("BEGIN")
        reader.execute("SELECT * FROM plan_nodes").fetchall()
        wal = leave_committed_wal(source)
        original = wal.read_bytes()
        with pytest.raises(StorageValidationError, match="database is in use"):
            restore_backup(backup, source, asset_root=root)
        assert wal.read_bytes() == original
        reader.rollback()
    with closing(sqlite3.connect(source)) as connection:
        assert connection.execute(
            "SELECT title FROM plan_nodes ORDER BY id LIMIT 1"
        ).fetchone() == ("committed WAL value",)


def test_legacy_v1_without_assets_is_readable_but_bool_version_is_not(tmp_path):
    from tests.test_sqlite_maintenance import make_database

    source = tmp_path / "old.db"
    make_database(source)
    backup = tmp_path / "dayforge-legacy.db"
    backup.write_bytes(source.read_bytes())
    manifest_file = backup_paths(backup)[0]
    # Independently reconstruct the old producer's complete v1 record. Do not
    # export v2 and relabel it to simulate legacy-format compatibility.
    manifest = dict(
        format_version=1,
        kind="manual",
        created_at="2026-09-23T01:00:00Z",
        database_file=backup.name,
        database_size=backup.stat().st_size,
        database_sha256=hashlib.sha256(backup.read_bytes()).hexdigest(),
        alembic_head="000000000001",
        server_instance_id="instance-1",
        sync_epoch="epoch-before",
        protocol_version=4,
        integrity_check="ok",
        foreign_key_errors=[],
        domain_errors=[],
        active_timer_count=0,
        row_counts={
            "alembic_version": 1,
            "server_instances": 1,
            "users": 1,
            "plan_nodes": 1,
            "timer_sessions": 0,
            "client_devices": 0,
            "sync_changes": 0,
            "entity_revision_snapshots": 0,
        },
        tombstone_counts={"plan_nodes": 0},
    )
    manifest_file.write_text(json.dumps(manifest))
    assert load_and_validate_backup(backup)[1].valid
    restore_backup(backup, tmp_path / "old-restored.db")
    manifest["format_version"] = True
    manifest_file.write_text(json.dumps(manifest))
    with pytest.raises(StorageValidationError, match="unsupported backup format"):
        load_and_validate_backup(backup)


def test_cli_backup_verify_and_restore_real_files(tmp_path):
    source, root = tmp_path / "source.db", tmp_path / "assets"
    seed_ready(source, root, second="png")
    directory = tmp_path / "archive"

    def cli(*args):
        return subprocess.run(
            [sys.executable, "-m", "src.storage.cli", *map(str, args)],
            capture_output=True,
            text=True,
            timeout=30,
        )

    result = cli(
        "sqlite-backup",
        "--database",
        source,
        "--backup-dir",
        directory,
        "--asset-root",
        root,
        "--kind",
        "manual",
    )
    assert result.returncode == 0, result.stderr
    backup = next(directory.glob("*.db"))
    assert f"backup={backup}" in result.stdout
    assert cli("sqlite-verify", "--database", source).returncode != 0
    assert (
        cli("sqlite-verify", "--database", source, "--asset-root", root).returncode == 0
    )
    target, destination = tmp_path / "target.db", tmp_path / "destination"
    destination.mkdir()
    restored = cli(
        "sqlite-restore",
        "--database",
        target,
        "--backup-file",
        backup,
        "--asset-root",
        destination,
    )
    assert restored.returncode == 0, restored.stderr
    assert inspect_database(target, asset_root=destination).valid


def test_restore_refuses_overwriting_selected_backup_or_following_target_link(tmp_path):
    source, root = tmp_path / "source.db", tmp_path / "assets"
    seed_ready(source, root)
    backup, _ = create_backup(
        source, tmp_path / "archive", asset_root=root, kind="manual"
    )
    content = backup.read_bytes()
    with pytest.raises(StorageValidationError, match="selected backup"):
        restore_backup(backup, backup, asset_root=root)
    link = tmp_path / "target-link.db"
    link.symlink_to(source)
    before = database_dump(source)
    with pytest.raises(StorageValidationError):
        restore_backup(backup, link, asset_root=root)
    assert (
        backup.read_bytes() == content
        and database_dump(source) == before
        and link.is_symlink()
    )
