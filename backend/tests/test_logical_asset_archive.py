"""Portable ownership + real immutable bytes, transaction and failure boundaries."""

from contextlib import closing
from io import BytesIO
import json
import sqlite3
import subprocess
import sys
import zipfile

import pytest
from sqlalchemy import event
from sqlalchemy.engine import Engine

from src.storage import logical_archive as logical
from src.storage.asset_files import AssetFiles
from src.storage.asset_root import AssetRootLease
from src.storage.errors import StorageValidationError
from src.storage.sqlite_maintenance import inspect_database
from tests.asset_file_fixtures import SVG, blob
from tests.test_logical_archive import migrate
from tests.test_logical_archive_identity import (
    database_dump,
    read_bundle,
    replace_records,
)
from tests.test_physical_asset_backup import seed_ready


def prepared(tmp_path, **kwargs):
    source, root = tmp_path / "source.db", tmp_path / "assets"
    entries = seed_ready(source, root, **kwargs)
    archive = logical.export_archive(
        f"sqlite:///{source}", tmp_path / "archive.zip", asset_root=root
    )
    target, destination = tmp_path / "target.db", tmp_path / "destination"
    url = migrate(target)
    destination.mkdir()
    return source, root, entries, archive, target, destination, url


def rewrite(path, *, change=None, entries=None):
    with zipfile.ZipFile(path) as reader:
        members = {name: reader.read(name) for name in reader.namelist()}
    if change is not None:
        bundle = read_bundle(path)
        change(bundle)
        members["manifest.json"] = json.dumps(bundle["manifest"]).encode()
        for name, content in bundle["collections"].items():
            members[f"collections/{name}.jsonl"] = content.encode()
    if entries is not None:
        entries(members)
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as writer:
        for name, data in members.items():
            writer.writestr(name, data)


@pytest.mark.parametrize(
    "second,pending", [("svg", False), ("png", False), ("png", True)]
)
def test_v3_round_trip_remaps_database_ids_but_not_byte_owners(
    tmp_path, second, pending
):
    source, root, entries, archive, target, destination, url = prepared(
        tmp_path, second=second, pending=pending
    )
    before = read_bundle(archive)
    original = inspect_database(source, asset_root=root)
    epoch = logical.import_archive(url, archive, asset_root=destination)
    assert epoch != original.sync_epoch
    assert inspect_database(target, asset_root=destination).valid
    with closing(sqlite3.connect(target)) as connection:
        assert connection.execute("SELECT id FROM users ORDER BY id").fetchall() == [
            (1,),
            (2,),
        ]
        assert connection.execute(
            "SELECT validation_profile FROM account_icon_blobs ORDER BY owner_user_id"
        ).fetchall() == [
            ("svg-v1",),
            (None if pending else ("png-v1" if second == "png" else "svg-v1"),),
        ]
    for entry, data in entries:
        assert (
            AssetFiles(destination).read(
                entry.owner_public_id, entry.blob, entry.profile
            )
            == data
        )
    exported = logical.export_archive(
        url, tmp_path / "again.zip", asset_root=destination
    )
    after = read_bundle(exported)
    assert after["collections"] == before["collections"]
    assert after["manifest"]["format_version"] == 4
    assert after["manifest"]["source_sync_epoch"] == epoch
    assert after["manifest"]["server_instance_id"] == original.server_instance_id
    assert not list(destination.rglob(".install-*"))
    assert archive.stat().st_mode & 0o777 == 0o600


@pytest.mark.parametrize(
    "damage",
    [
        "missing",
        "corrupt",
        "extra",
        "wrong-owner",
        "profile",
        "timestamp",
        "quota",
        "reference",
        "identity",
        "v1",
        "v2",
        "no-root",
    ],
)
def test_invalid_closure_never_publishes_target_or_any_asset(tmp_path, damage):
    _, _, entries, archive, target, destination, url = prepared(tmp_path, second="png")
    before = database_dump(target)
    name = entries[0][0].name
    if damage in {"missing", "corrupt", "extra", "wrong-owner"}:

        def mutate(members):
            if damage == "missing":
                del members[name]
            elif damage == "corrupt":
                members[name] = b"broken"
            elif damage == "extra":
                members["extra"] = b"extra"
            else:
                members[
                    name.replace(
                        entries[0][0].owner_public_id,
                        "00000000-0000-4000-8000-000000000099",
                    )
                ] = members.pop(name)

        rewrite(archive, entries=mutate)
    elif damage != "no-root":

        def mutate_bundle(bundle):
            if damage in {"v1", "v2"}:
                bundle["manifest"]["format_version"] = int(damage[-1])
                return
            table = (
                "account_icon_blobs"
                if damage in {"profile", "timestamp"}
                else "appearance_accounts"
                if damage == "quota"
                else "timer_segments"
            )
            records = [
                json.loads(line) for line in bundle["collections"][table].splitlines()
            ]
            if damage == "profile":
                records[0]["data"]["validation_profile"] = "svg-v999"
            elif damage == "timestamp":
                records[0]["data"]["ready_at"] = "2026-09-27"
            elif damage == "quota":
                records[0]["data"]["reserved_bytes"] += 1
            elif damage == "reference":
                records[0]["data"]["session_id"]["key"] = "missing"
            else:
                records[0]["key"] = "wrong-key"
            replace_records(bundle, table, records)

        rewrite(archive, change=mutate_bundle)
    with pytest.raises(StorageValidationError):
        logical.import_archive(
            url, archive, asset_root=None if damage == "no-root" else destination
        )
    assert database_dump(target) == before
    assert list(destination.iterdir()) == []


@pytest.mark.parametrize("damage", ["missing", "corrupt", "no-root", "already-exists"])
def test_failed_export_preserves_previous_output_and_source(tmp_path, damage):
    source, root, entries, archive, _, _, _ = prepared(tmp_path)
    original = archive.read_bytes()
    if damage == "missing":
        (root / entries[0][0].name).unlink()
    elif damage == "corrupt":
        (root / entries[0][0].name).write_bytes(b"broken")
    before = database_dump(source)
    with pytest.raises(StorageValidationError):
        logical.export_archive(
            f"sqlite:///{source}",
            archive,
            asset_root=None if damage == "no-root" else root,
        )
    assert archive.read_bytes() == original and database_dump(source) == before
    assert not list(tmp_path.glob("*.incomplete"))


def test_root_lock_refuses_import_and_preserves_database(tmp_path):
    _, _, _, archive, target, destination, url = prepared(tmp_path)
    before = database_dump(target)
    lease = AssetRootLease(destination)
    lease.acquire()
    try:
        with pytest.raises(StorageValidationError, match="root is in use"):
            logical.import_archive(url, archive, asset_root=destination)
    finally:
        lease.release()
    assert database_dump(target) == before


def test_file_work_runs_after_rollback_and_without_database_write_lock(
    tmp_path, monkeypatch
):
    _, _, _, archive, target, destination, url = prepared(tmp_path)
    before = database_dump(target)
    publish = AssetFiles.publish
    calls = 0

    def independent_write(self, *args):
        nonlocal calls
        calls += 1
        assert database_dump(target) == before
        with closing(sqlite3.connect(target, timeout=0)) as connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute(
                "UPDATE server_instances SET sync_epoch='uncommitted-probe'"
            )
            connection.rollback()
        return publish(self, *args)

    monkeypatch.setattr(AssetFiles, "publish", independent_write)
    logical.import_archive(url, archive, asset_root=destination)
    assert calls == 2 and inspect_database(target, asset_root=destination).valid


def test_cleanup_failure_retains_retry_evidence_not_database_rows(
    tmp_path, monkeypatch
):
    _, _, entries, archive, target, destination, url = prepared(tmp_path)
    before = database_dump(target)
    finish = AssetFiles.finish

    def fail(*_args):
        raise OSError("synthetic install finish failure")

    monkeypatch.setattr(AssetFiles, "finish", fail)
    with pytest.raises(StorageValidationError):
        logical.import_archive(url, archive, asset_root=destination)
    assert database_dump(target) == before
    assert len(list(destination.rglob(".install-*.json"))) == 1
    monkeypatch.setattr(AssetFiles, "finish", finish)
    logical.import_archive(url, archive, asset_root=destination)
    assert not list(destination.rglob(".install-*"))
    for entry, data in entries:
        assert (destination / entry.name).read_bytes() == data


def test_unrelated_install_journal_is_preserved(tmp_path):
    _, _, entries, archive, target, destination, url = prepared(tmp_path)
    before = database_dump(target)
    data = SVG.replace(b"<rect", b'<rect x="0"')
    receipt = AssetFiles(destination).publish(
        entries[0][0].owner_public_id, BytesIO(data), blob(data)
    )
    journal = next(destination.rglob(f"{receipt.stem}.json"))
    original = journal.read_bytes()
    with pytest.raises(StorageValidationError, match="requires recovery first"):
        logical.import_archive(url, archive, asset_root=destination)
    assert database_dump(target) == before and journal.read_bytes() == original


def test_real_deferred_foreign_key_commit_failure_rolls_back_and_retries(tmp_path):
    _, _, _, archive, target, destination, url = prepared(tmp_path)
    before = database_dump(target)
    fired = False

    def fail(connection):
        nonlocal fired
        if str(connection.engine.url.database) == str(target):
            fired = True
            connection.exec_driver_sql("PRAGMA defer_foreign_keys=ON")
            connection.exec_driver_sql("UPDATE user_profiles SET user_id=99999")

    event.listen(Engine, "commit", fail)
    try:
        with pytest.raises(StorageValidationError):
            logical.import_archive(url, archive, asset_root=destination)
    finally:
        event.remove(Engine, "commit", fail)
    assert fired and database_dump(target) == before
    assert len(list(destination.glob("accounts/*/blobs/[!.]*"))) == 2
    logical.import_archive(url, archive, asset_root=destination)
    assert inspect_database(target, asset_root=destination).valid


def test_source_path_replacement_cannot_switch_verified_archive(tmp_path, monkeypatch):
    _, _, _, archive, target, destination, url = prepared(tmp_path)
    original = logical._install_archive

    def replace(*args):
        archive.rename(tmp_path / "original.zip")
        archive.write_bytes(b"a different path source")
        original(*args)

    monkeypatch.setattr(logical, "_install_archive", replace)
    logical.import_archive(url, archive, asset_root=destination)
    assert inspect_database(target, asset_root=destination).valid


def test_final_empty_target_check_rejects_concurrent_data_without_overwriting_it(
    tmp_path, monkeypatch
):
    _, _, _, archive, target, destination, url = prepared(tmp_path)
    original = logical._install_archive
    after_concurrent = []

    def insert(*args):
        original(*args)
        with closing(sqlite3.connect(target)) as connection:
            connection.execute(
                "INSERT INTO users(public_id,username,password_hash,is_active,status,is_verified,is_admin,auth_version,created_at,updated_at) "
                "VALUES('00000000-0000-4000-8000-000000000099','other','synthetic',1,'active',0,0,1,'2026-09-27 00:00:00','2026-09-27 00:00:00')"
            )
            connection.commit()
        after_concurrent.extend(database_dump(target))

    monkeypatch.setattr(logical, "_install_archive", insert)
    with pytest.raises(StorageValidationError, match="not empty"):
        logical.import_archive(url, archive, asset_root=destination)
    assert database_dump(target) == after_concurrent


def test_cli_round_trip_with_real_svg_and_png(tmp_path):
    source, root, _, _, target, destination, url = prepared(tmp_path, second="png")
    archive = tmp_path / "cli.zip"

    def cli(*args):
        return subprocess.run(
            [sys.executable, "-m", "src.storage.cli", *map(str, args)],
            capture_output=True,
            text=True,
            timeout=30,
        )

    exported = cli(
        "logical-export",
        "--database-url",
        f"sqlite:///{source}",
        "--output",
        archive,
        "--asset-root",
        root,
    )
    assert exported.returncode == 0, exported.stderr
    restored = cli(
        "logical-import",
        "--database-url",
        url,
        "--archive",
        archive,
        "--asset-root",
        destination,
    )
    assert restored.returncode == 0, restored.stderr
    assert inspect_database(target, asset_root=destination).valid


def test_corrupt_existing_blob_is_not_overwritten_or_deleted(tmp_path):
    _, _, entries, archive, target, destination, url = prepared(tmp_path)
    before = database_dump(target)
    entry, data = entries[0]
    files = AssetFiles(destination)
    receipt = files.publish(entry.owner_public_id, BytesIO(data), entry.blob)
    files.finish(receipt)
    path = destination / entry.name
    path.write_bytes(b"existing broken evidence")
    with pytest.raises(StorageValidationError):
        logical.import_archive(url, archive, asset_root=destination)
    assert database_dump(target) == before
    assert path.read_bytes() == b"existing broken evidence"


def test_actual_commit_then_error_does_not_claim_database_rollback(tmp_path):
    _, _, entries, archive, target, destination, url = prepared(tmp_path)
    previous = inspect_database(target).sync_epoch
    fired = False

    def after_commit(connection):
        nonlocal fired
        if str(connection.engine.url.database) == str(target):
            fired = True
            connection.connection.driver_connection.commit()
            raise OSError("synthetic connection failure after durable commit")

    event.listen(Engine, "commit", after_commit)
    try:
        with pytest.raises(StorageValidationError):
            logical.import_archive(url, archive, asset_root=destination)
    finally:
        event.remove(Engine, "commit", after_commit)
    assert fired
    current = inspect_database(target, asset_root=destination)
    assert current.valid and current.sync_epoch != previous
    for entry, data in entries:
        assert (destination / entry.name).read_bytes() == data
    before_retry = database_dump(target)
    with pytest.raises(StorageValidationError, match="not empty"):
        logical.import_archive(url, archive, asset_root=destination)
    assert database_dump(target) == before_retry


def test_changed_archive_bytes_after_verification_are_rechecked_before_install(
    tmp_path, monkeypatch
):
    _, _, _, archive, target, destination, url = prepared(tmp_path)
    before = database_dump(target)
    original = logical._install_archive

    def corrupt(assets, entries, files):
        # Same opened inode, not path replacement. Earlier verify is no ticket.
        with archive.open("r+b") as output:
            output.seek(0)
            output.write(b"x" * archive.stat().st_size)
        original(assets, entries, files)

    monkeypatch.setattr(logical, "_install_archive", corrupt)
    with pytest.raises(StorageValidationError):
        logical.import_archive(url, archive, asset_root=destination)
    assert database_dump(target) == before
    assert not list(destination.glob("accounts/*/blobs/[!.]*"))


def test_export_snapshot_does_not_mix_in_later_database_changes(tmp_path, monkeypatch):
    source, root, _, _, _, _, _ = prepared(tmp_path)
    validate = logical._validate_appearance
    changed = False
    before = None
    with closing(sqlite3.connect(source)) as connection:
        before = connection.execute(
            "SELECT title FROM plan_nodes ORDER BY id LIMIT 1"
        ).fetchone()[0]

    def change_after_snapshot(connection, metadata, **kwargs):
        nonlocal changed
        validate(connection, metadata, **kwargs)
        with closing(sqlite3.connect(source)) as writer:
            writer.execute(
                "UPDATE plan_nodes SET title='later commit' WHERE id=(SELECT MIN(id) FROM plan_nodes)"
            )
            writer.commit()
        changed = True

    monkeypatch.setattr(logical, "_validate_appearance", change_after_snapshot)
    exported = logical.export_archive(
        f"sqlite:///{source}", tmp_path / "snapshot.zip", asset_root=root
    )
    records = [
        json.loads(line)
        for line in read_bundle(exported)["collections"]["plan_nodes"].splitlines()
    ]
    assert changed and before in {row["data"]["title"] for row in records}
    assert "later commit" not in {row["data"]["title"] for row in records}


@pytest.mark.parametrize("stage", ["file", "directory"])
def test_export_sync_failure_never_clobbers_existing_backup(
    tmp_path, monkeypatch, stage
):
    from src.storage import backup_files

    source, root, _, _, _, _, _ = prepared(tmp_path)
    output = tmp_path / "new-export.zip"

    def fail(*_args):
        raise OSError("synthetic export sync failure")

    monkeypatch.setattr(
        backup_files, "durable_file" if stage == "file" else "sync_directory", fail
    )
    with pytest.raises(StorageValidationError):
        logical.export_archive(f"sqlite:///{source}", output, asset_root=root)
    if stage == "file":
        assert not output.exists()
    else:
        # Name publication happened before the directory sync error. Preserve
        # the complete output, report uncertain durability, never silently retry.
        assert read_bundle(output)["manifest"]["format_version"] == 4


def test_v2_cannot_publish_ready_metadata_even_when_no_byte_entries_remain(tmp_path):
    _, _, entries, archive, target, destination, url = prepared(tmp_path)
    # Exercise the original v2 ready-byte boundary on its matching pre-round
    # schema. The new v4 collection gate has an independent rejection test.
    from alembic import command
    from tests.test_alembic_migration import alembic_config

    command.downgrade(alembic_config(str(target)), "000000000007")
    before = database_dump(target)

    def strip(members):
        for entry, _ in entries:
            del members[entry.name]
        for name in (
            "activity_challenge_rounds",
            "activity_challenge_heads",
            "activity_challenge_event_bindings",
            "activity_challenge_timer_bindings",
        ):
            del members[f"collections/{name}.jsonl"]

    def old_shape(bundle):
        bundle["manifest"].update(format_version=2, alembic_head="000000000007")
        for name in (
            "activity_challenge_rounds",
            "activity_challenge_heads",
            "activity_challenge_event_bindings",
            "activity_challenge_timer_bindings",
        ):
            assert bundle["manifest"]["collections"][name]["rows"] == 0
            del bundle["manifest"]["collections"][name]
            del bundle["collections"][name]

    rewrite(
        archive,
        change=old_shape,
        entries=strip,
    )
    with pytest.raises(StorageValidationError, match="complete byte backup"):
        logical.import_archive(url, archive, asset_root=destination)
    assert database_dump(target) == before and list(destination.iterdir()) == []


def test_entire_missing_collection_cannot_be_imported_as_an_empty_table(tmp_path):
    _, _, _, archive, target, destination, url = prepared(tmp_path)
    before = database_dump(target)

    def drop(bundle):
        del bundle["manifest"]["collections"]["metric_observations"]
        del bundle["collections"]["metric_observations"]

    rewrite(
        archive,
        change=drop,
        entries=lambda members: members.pop("collections/metric_observations.jsonl"),
    )
    with pytest.raises(StorageValidationError, match="collection set differs"):
        logical.import_archive(url, archive, asset_root=destination)
    assert database_dump(target) == before and list(destination.iterdir()) == []


def test_invalid_parent_graph_is_rejected_before_file_publication(tmp_path):
    _, _, _, archive, target, destination, url = prepared(tmp_path)
    before = database_dump(target)

    def invalid(bundle):
        records = [
            json.loads(line)
            for line in bundle["collections"]["plan_nodes"].splitlines()
        ]
        activities = [row for row in records if row["data"]["node_kind"] == "activity"]
        child = next(
            row for row in activities if row["data"]["parent_node_id"] is not None
        )
        parent = next(row for row in activities if row is not child)
        child["data"]["parent_node_id"] = {"$ref": "plan_nodes", "key": parent["key"]}
        replace_records(bundle, "plan_nodes", records)

    rewrite(archive, change=invalid)
    with pytest.raises(StorageValidationError, match="single-parent hierarchy"):
        logical.import_archive(url, archive, asset_root=destination)
    assert database_dump(target) == before and list(destination.iterdir()) == []
