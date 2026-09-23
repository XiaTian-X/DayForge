"""Migrated SQLite ownership and complete metadata backup, not upload/API proof."""

from contextlib import closing
from copy import deepcopy
import json
import sqlite3

from alembic import command
import pytest
from sqlalchemy import Engine, event

from src.storage.logical_archive import export_archive, import_archive
from src.storage.sqlite_maintenance import (
    StorageValidationError,
    create_backup,
    inspect_database,
    restore_backup,
)
from tests.test_alembic_migration import alembic_config
from tests.test_logical_archive import migrate, seed_source
from tests.test_logical_archive_identity import (
    database_dump,
    read_bundle,
    replace_records,
    write_bundle,
)
from tests.test_one_time_migration import baseline_database, table_snapshot


ASSET = "a0000000-0000-4000-8000-000000000001"
TASK = "a0000000-0000-4000-8000-000000000002"
PACK = "b0000000-0000-4000-8000-000000000001"
STAMP = "2026-09-23 01:00:00"
TABLES = (
    "appearance_accounts",
    "account_icon_blobs",
    "account_icon_assets",
    "account_icon_packs",
    "appearance_catalog",
)


def encoded(value):
    # Independent wire JSON fixture, not the production canonicalizer.
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def insert(connection, table, **values):
    connection.execute(
        f"INSERT INTO {table} ({','.join(values)}) VALUES ({','.join('?' for _ in values)})",
        tuple(values.values()),
    )


def seed_appearance(path):
    url = migrate(path)
    seed_source(url)
    with closing(sqlite3.connect(path)) as connection:
        connection.execute("PRAGMA foreign_keys=ON")
        for owner in (101, 205):
            blob = dict(
                sha256="a" * 64,
                byte_length=96,
                media_type="image/png",
                width=24,
                height=24,
            )
            assets = [
                dict(
                    asset_id=public,
                    name=f"图标-{owner}-{purpose}",
                    purpose=purpose,
                    color_mode="template",
                    light=blob,
                    dark=blob,
                )
                for public, purpose in ((ASSET, "general"), (TASK, "task"))
            ]
            packs = [
                dict(
                    format="dayforge.icon-pack",
                    format_version=1,
                    pack_id=PACK,
                    revision=revision,
                    name=f"图标包-{revision}",
                    assets=assets,
                    roles={"habit.default": ASSET, "task.default": TASK},
                    placeholder_asset_id=ASSET,
                )
                for revision in (1, 2)
            ]
            metadata = [encoded(value) for value in (*assets, *packs)]
            insert(
                connection,
                "appearance_accounts",
                user_id=owner,
                byte_limit=268435456,
                reserved_bytes=96,
                asset_limit=1000,
                reserved_assets=2,
                metadata_byte_limit=8388608,
                reserved_metadata_bytes=sum(len(value.encode()) for value in metadata),
                catalog_sequence=4,
            )
            insert(
                connection,
                "account_icon_blobs",
                id=owner,
                owner_user_id=owner,
                **blob,
                ready_at=None,
                created_at=STAMP,
            )
            for index, asset in enumerate(assets):
                insert(
                    connection,
                    "account_icon_assets",
                    id=owner * 10 + index,
                    owner_user_id=owner,
                    public_id=asset["asset_id"],
                    light_blob_id=owner,
                    dark_blob_id=owner,
                    metadata_json=metadata[index],
                    metadata_bytes=len(metadata[index].encode()),
                    created_at=STAMP,
                )
                insert(
                    connection,
                    "appearance_catalog",
                    owner_user_id=owner,
                    sequence=index + 1,
                    kind="asset",
                    asset_id=owner * 10 + index,
                    pack_version_id=None,
                )
            for index, pack in enumerate(packs):
                insert(
                    connection,
                    "account_icon_packs",
                    id=owner * 10 + index,
                    owner_user_id=owner,
                    pack_uuid=PACK,
                    revision=pack["revision"],
                    metadata_json=metadata[index + 2],
                    metadata_bytes=len(metadata[index + 2].encode()),
                    created_at=STAMP,
                )
                insert(
                    connection,
                    "appearance_catalog",
                    owner_user_id=owner,
                    sequence=index + 3,
                    kind="pack",
                    asset_id=None,
                    pack_version_id=owner * 10 + index,
                )
        connection.commit()
    assert inspect_database(path).valid
    return url


@pytest.fixture
def source(tmp_path):
    path = tmp_path / "source.sqlite"
    seed_appearance(path)
    return path


def test_incremental_migration_never_creates_accounts_or_rewrites_existing_data(
    tmp_path,
):
    path = baseline_database(tmp_path)
    command.upgrade(alembic_config(str(path)), "000000000002")
    before = table_snapshot(path)
    command.upgrade(alembic_config(str(path)), "head")
    after = table_snapshot(path)
    assert {name: after[name] for name in before} == before
    assert set(after) - set(before) == set(TABLES)
    assert all(after[name][1] == [] for name in TABLES)
    command.check(alembic_config(str(path)))
    command.downgrade(alembic_config(str(path)), "000000000002")
    assert table_snapshot(path) == before


def test_late_create_failure_rolls_back_schema_and_version_then_retries(tmp_path):
    path = baseline_database(tmp_path)
    command.upgrade(alembic_config(str(path)), "000000000002")
    before = database_dump(path)

    def fail(connection, cursor, statement, parameters, context, executemany):
        if (
            str(connection.engine.url.database) == str(path)
            and "CREATE TABLE appearance_catalog" in statement
        ):
            raise RuntimeError("injected catalog schema failure")

    event.listen(Engine, "before_cursor_execute", fail)
    try:
        with pytest.raises(RuntimeError, match="catalog schema failure"):
            command.upgrade(alembic_config(str(path)), "head")
    finally:
        event.remove(Engine, "before_cursor_execute", fail)
    assert database_dump(path) == before
    command.upgrade(alembic_config(str(path)), "head")
    command.check(alembic_config(str(path)))


@pytest.mark.parametrize("with_assets", [False, True])
def test_downgrade_refuses_even_empty_account_quota_configuration(source, with_assets):
    if not with_assets:
        with closing(sqlite3.connect(source)) as connection:
            connection.execute("PRAGMA foreign_keys=ON")
            for table in reversed(TABLES[1:]):
                connection.execute(f"DELETE FROM {table}")
            connection.execute(
                "UPDATE appearance_accounts SET reserved_bytes=0,reserved_assets=0,reserved_metadata_bytes=0,catalog_sequence=0,byte_limit=42"
            )
            connection.commit()
    before = database_dump(source)
    with pytest.raises(RuntimeError, match="appearance data exists"):
        command.downgrade(alembic_config(str(source)), "000000000002")
    assert database_dump(source) == before


@pytest.mark.parametrize(
    "statement",
    [
        "UPDATE account_icon_assets SET light_blob_id=205 WHERE owner_user_id=101",
        "UPDATE account_icon_assets SET dark_blob_id=205 WHERE owner_user_id=101",
        "UPDATE appearance_catalog SET asset_id=2050 WHERE owner_user_id=101 AND kind='asset' AND sequence=1",
        "UPDATE appearance_catalog SET pack_version_id=2050 WHERE owner_user_id=101 AND kind='pack' AND sequence=3",
        "DELETE FROM account_icon_blobs WHERE owner_user_id=101",
        "DELETE FROM account_icon_assets WHERE owner_user_id=101",
        "DELETE FROM account_icon_packs WHERE owner_user_id=101",
        "DELETE FROM appearance_accounts WHERE user_id=101",
        "UPDATE appearance_catalog SET kind='pack' WHERE kind='asset'",
        "UPDATE appearance_catalog SET sequence=0",
        "UPDATE appearance_accounts SET reserved_bytes=-1",
        "UPDATE account_icon_blobs SET byte_length=2097153",
        "UPDATE account_icon_blobs SET width=1025",
        "UPDATE account_icon_packs SET revision=2147483648",
        "UPDATE account_icon_packs SET revision=1 WHERE revision=2",
        "UPDATE account_icon_assets SET public_id='a0000000-0000-4000-8000-000000000001' WHERE public_id='a0000000-0000-4000-8000-000000000002'",
    ],
)
def test_database_rejects_cross_owner_references_and_invalid_shapes(source, statement):
    before = database_dump(source)
    with closing(sqlite3.connect(source)) as connection:
        connection.execute("PRAGMA foreign_keys=ON")
        with pytest.raises(sqlite3.IntegrityError):
            connection.execute(statement)
        connection.rollback()
    assert database_dump(source) == before


def test_logical_roundtrip_remaps_ownership_hashes_and_multiple_pack_versions(
    source, tmp_path
):
    archive = export_archive(f"sqlite:///{source}", tmp_path / "valid.zip")
    expected = read_bundle(archive)
    for name in TABLES:
        assert (
            expected["manifest"]["collections"][name]["rows"]
            == {
                "appearance_accounts": 2,
                "account_icon_blobs": 2,
                "account_icon_assets": 4,
                "account_icon_packs": 4,
                "appearance_catalog": 8,
            }[name]
        )
    target = tmp_path / "target.sqlite"
    url = migrate(target)
    epoch = import_archive(url, archive)
    assert epoch != expected["manifest"]["source_sync_epoch"]
    assert inspect_database(target).valid
    actual = read_bundle(export_archive(url, tmp_path / "again.zip"))
    assert actual["collections"] == expected["collections"]
    with closing(sqlite3.connect(target)) as connection:
        assert connection.execute("SELECT id FROM users ORDER BY id").fetchall() == [
            (1,),
            (2,),
        ]
        assert connection.execute(
            "SELECT user_id,reserved_bytes,reserved_assets,catalog_sequence FROM appearance_accounts ORDER BY user_id"
        ).fetchall() == [(1, 96, 2, 4), (2, 96, 2, 4)]
        assert connection.execute(
            "SELECT a.owner_user_id,b.owner_user_id,a.light_blob_id=a.dark_blob_id FROM account_icon_assets a JOIN account_icon_blobs b ON b.id=a.light_blob_id ORDER BY a.owner_user_id,a.id"
        ).fetchall() == [(1, 1, 1), (1, 1, 1), (2, 2, 1), (2, 2, 1)]
        assert connection.execute("PRAGMA foreign_key_check").fetchall() == []


def test_physical_restore_preserves_lowered_limits_metadata_and_server_identity(
    source, tmp_path
):
    with closing(sqlite3.connect(source)) as connection:
        connection.execute(
            "UPDATE appearance_accounts SET byte_limit=0,asset_limit=0,metadata_byte_limit=0"
        )
        connection.commit()
    before = inspect_database(source)
    backup, _ = create_backup(source, tmp_path / "backups", apply_retention=False)
    target = tmp_path / "restored.sqlite"
    _, epoch = restore_backup(backup, target)
    after = inspect_database(target)
    assert after.valid and after.server_instance_id == before.server_instance_id
    assert epoch != before.sync_epoch and after.row_counts == before.row_counts
    with closing(sqlite3.connect(target)) as connection:
        assert (
            connection.execute(
                "SELECT byte_limit,asset_limit,metadata_byte_limit,reserved_bytes,reserved_assets FROM appearance_accounts"
            ).fetchall()
            == [(0, 0, 0, 96, 2)] * 2
        )
    assert (
        read_bundle(export_archive(f"sqlite:///{target}", tmp_path / "restored.zip"))[
            "collections"
        ]
        == read_bundle(
            export_archive(f"sqlite:///{source}", tmp_path / "original.zip")
        )["collections"]
    )


TAMPERING = [
    "quota",
    "fractional",
    "watermark",
    "catalog",
    "pack",
    "metadata",
    "charge",
    "blob",
    "cross-owner",
    "ready",
    "missing-account",
]


def corrupt(bundle, case):
    name = {
        "quota": "appearance_accounts",
        "fractional": "appearance_accounts",
        "watermark": "appearance_accounts",
        "catalog": "appearance_catalog",
        "pack": "account_icon_packs",
        "metadata": "account_icon_assets",
        "charge": "account_icon_assets",
        "blob": "account_icon_blobs",
        "cross-owner": "account_icon_assets",
        "ready": "account_icon_blobs",
        "missing-account": "appearance_accounts",
    }[case]
    records = [json.loads(line) for line in bundle["collections"][name].splitlines()]
    data = records[0]["data"]
    if case == "quota":
        data["reserved_bytes"] = 95
    elif case == "fractional":
        data["asset_limit"] = 2.5
    elif case == "watermark":
        data["catalog_sequence"] = 9223372036854775807
    elif case == "catalog":
        records.pop()
    elif case == "pack":
        metadata = json.loads(data["metadata_json"])
        metadata["assets"][0]["name"] = "wrong immutable declaration"
        data["metadata_json"] = encoded(metadata)
        data["metadata_bytes"] = len(data["metadata_json"].encode())
    elif case == "metadata":
        data["metadata_json"] = " " + data["metadata_json"]
        data["metadata_bytes"] += 1
    elif case == "charge":
        data["metadata_bytes"] += 1
    elif case == "blob":
        data["width"] = 25
    elif case == "cross-owner":
        others = [
            json.loads(line)
            for line in bundle["collections"]["account_icon_blobs"].splitlines()
        ]
        data["light_blob_id"] = {"$ref": "account_icon_blobs", "key": others[1]["key"]}
    elif case == "ready":
        data["ready_at"] = "2026-09-23T01:00:00Z"
    elif case == "missing-account":
        records.clear()
    replace_records(bundle, name, records)


@pytest.mark.parametrize("case", TAMPERING)
def test_recomputed_archive_checksum_cannot_hide_invalid_metadata(
    source, tmp_path, case
):
    valid = export_archive(f"sqlite:///{source}", tmp_path / "valid.zip")
    bundle = deepcopy(read_bundle(valid))
    corrupt(bundle, case)
    bad = write_bundle(tmp_path / "bad.zip", bundle)
    target = tmp_path / "target.sqlite"
    url = migrate(target)
    before = database_dump(target)
    from sqlalchemy.exc import IntegrityError

    with pytest.raises((StorageValidationError, IntegrityError)):
        import_archive(url, bad)
    assert database_dump(target) == before
    import_archive(url, valid)
    assert inspect_database(target).valid


@pytest.mark.parametrize(
    "statement",
    [
        "UPDATE appearance_accounts SET reserved_bytes=95 WHERE user_id=101",
        "UPDATE appearance_accounts SET asset_limit=2.5 WHERE user_id=101",
        "DELETE FROM appearance_catalog WHERE owner_user_id=101 AND sequence=4",
        "UPDATE account_icon_blobs SET width=25 WHERE owner_user_id=101",
        "UPDATE account_icon_blobs SET ready_at='2026-09-23 01:00:00' WHERE owner_user_id=101",
    ],
)
def test_physical_backup_and_logical_export_reject_inconsistent_source(
    source, tmp_path, statement
):
    with closing(sqlite3.connect(source)) as connection:
        connection.execute("PRAGMA foreign_keys=ON")
        connection.execute(statement)
        connection.commit()
    inspection = inspect_database(source)
    assert not inspection.valid and any(
        "appearance" in message for message in inspection.domain_errors
    )
    before = database_dump(source)
    with pytest.raises(StorageValidationError, match="appearance"):
        create_backup(source, tmp_path / "backups", apply_retention=False)
    with pytest.raises(StorageValidationError, match="appearance"):
        export_archive(f"sqlite:///{source}", tmp_path / "bad.zip")
    assert list((tmp_path / "backups").iterdir()) == []
    assert not (tmp_path / "bad.zip").exists()
    assert database_dump(source) == before
