"""Complete history, account boundaries and durable backup/restore validation."""

from contextlib import closing
import hashlib
import json
from pathlib import Path
import sqlite3

import pytest
from sqlalchemy import Engine, event, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import async_sessionmaker

from src.storage.database_adapter import build_database_adapter
from src.storage.logical_archive import export_archive, import_archive
from src.storage.sqlite_maintenance import (
    StorageValidationError,
    create_backup,
    inspect_database,
    restore_backup,
)
from src.v2.errors import DomainError
from src.v2.one_time_recovery import read_one_time_checkpoints
from tests.test_logical_archive import migrate
from tests.test_logical_archive_identity import (
    database_dump,
    read_bundle,
    replace_records,
    write_bundle,
)
from tests.test_one_time_mutations import operation, setup_database
from tests.test_one_time_push import submit
from tests.test_one_time_storage import ACTIVITY, EVENTS


async def test_checkpoint_includes_initial_state_and_account_scoped_complete_history(
    runtime_engine,
):
    factory = await setup_database(runtime_engine)
    await submit(factory, [operation(index) for index in range(3)], owner=1)
    async with factory() as session:
        first = await read_one_time_checkpoints(session, 1)
        second = await read_one_time_checkpoints(session, 2)
        assert len(first) == len(second) == 1
        assert first[0].activity_uuid == second[0].activity_uuid == ACTIVITY
        assert first[0].state.version == 3
        assert first[0].state.completion_event_uuid == EVENTS[2]
        assert second[0].state.version == 0 and second[0].state.head_event_uuid is None
        assert await read_one_time_checkpoints(session, 999) == []


@pytest.mark.parametrize(
    "case,code",
    [
        ("tail", "TASK_HISTORY_INCOMPLETE"),
        ("all", "TASK_HISTORY_INCOMPLETE"),
        ("head", "TASK_STATE_DIVERGED"),
        ("fractional-state", "TASK_STATE_DIVERGED"),
        ("fractional-intent", "TASK_STATE_DIVERGED"),
        ("foreign-revert", "TASK_ACTIVITY_MISMATCH"),
        ("deleted-fact", "TASK_HISTORY_INCOMPLETE"),
        ("legacy", "TASK_STATE_UNINITIALIZED"),
    ],
)
async def test_corrupt_history_cannot_become_a_checkpoint(runtime_engine, case, code):
    factory = await setup_database(runtime_engine)
    await submit(factory, [operation(index) for index in range(3)])
    await submit(factory, [operation()], owner=2)
    statements = {
        "tail": "DELETE FROM activity_events WHERE owner_user_id=1 AND one_time_expected_version=2",
        "all": "DELETE FROM activity_events WHERE owner_user_id=1",
        "head": f"UPDATE activity_details SET one_time_head_event_uuid='{EVENTS[3]}', one_time_completion_event_uuid='{EVENTS[3]}' WHERE node_id=1",
        "fractional-state": f"UPDATE activity_details SET one_time_version=2.5, one_time_head_event_uuid='{EVENTS[1]}', one_time_completion_event_uuid=NULL WHERE node_id=1",
        "fractional-intent": "UPDATE activity_events SET one_time_expected_version=2.5 WHERE owner_user_id=1 AND one_time_expected_version=2",
        "foreign-revert": "UPDATE activity_events SET reverts_event_id=(SELECT id FROM activity_events WHERE owner_user_id=2) WHERE owner_user_id=1 AND event_type='revert'",
        "deleted-fact": "UPDATE activity_events SET deleted_at='2026-09-23 01:00:00' WHERE owner_user_id=1 AND one_time_expected_version=2",
        "legacy": "UPDATE activity_details SET one_time_version=NULL, one_time_head_event_uuid=NULL, one_time_completion_event_uuid=NULL WHERE node_id=1",
    }
    async with runtime_engine.begin() as connection:
        await connection.execute(text(statements[case]))
    async with factory() as session:
        with pytest.raises(DomainError) as error:
            await read_one_time_checkpoints(session, 1)
        assert error.value.code == code and error.value.entity is None
        # Corruption in another account must neither leak nor block this one.
        other = await read_one_time_checkpoints(session, 2)
        assert len(other) == 1 and other[0].state.version == 1


async def test_referenced_activity_detail_cannot_disappear(runtime_engine):
    factory = await setup_database(runtime_engine)
    await submit(factory, [operation()])
    with pytest.raises(IntegrityError, match="FOREIGN KEY constraint failed"):
        async with runtime_engine.begin() as connection:
            await connection.execute(
                text("DELETE FROM activity_details WHERE node_id=1")
            )
    async with factory() as session:
        assert (await read_one_time_checkpoints(session, 1))[0].state.version == 1


async def test_existing_reader_retains_checkpoint_and_history_after_other_commit(
    runtime_engine,
):
    factory = await setup_database(runtime_engine)
    async with factory() as reader:
        original = await read_one_time_checkpoints(reader, 1)
        await submit(factory, [operation()])
        assert await read_one_time_checkpoints(reader, 1) == original
        assert original[0].state.version == 0
    async with factory() as reader:
        assert (await read_one_time_checkpoints(reader, 1))[0].state.version == 1


async def test_tombstone_excluded_from_bootstrap_but_history_checked_in_backup(
    runtime_engine, tmp_path
):
    factory = await setup_database(runtime_engine)
    await submit(factory, [operation()])
    async with runtime_engine.begin() as connection:
        await connection.execute(
            text("UPDATE plan_nodes SET deleted_at='2026-09-23 01:00:00' WHERE id=1")
        )
    async with factory() as session:
        assert await read_one_time_checkpoints(session, 1) == []
    source = Path(runtime_engine.url.database)
    assert inspect_database(source).valid
    async with runtime_engine.begin() as connection:
        await connection.execute(
            text("DELETE FROM activity_events WHERE owner_user_id=1")
        )
    inspection = inspect_database(source)
    assert not inspection.valid
    assert inspection.domain_errors == [
        "invalid one-time history: TASK_HISTORY_INCOMPLETE"
    ]
    with pytest.raises(StorageValidationError, match="TASK_HISTORY_INCOMPLETE"):
        create_backup(source, tmp_path / "backups", apply_retention=False)
    assert list((tmp_path / "backups").iterdir()) == []


@pytest.mark.parametrize("case", ["tail", "all", "cross-owner", "fractional"])
async def test_checked_hash_does_not_make_incomplete_logical_archive_valid(
    runtime_engine, tmp_path, case
):
    factory = await setup_database(runtime_engine)
    await submit(factory, [operation(index) for index in range(3)])
    await submit(factory, [operation()], owner=2)
    source_url = str(runtime_engine.url.set(drivername="sqlite"))
    archive = export_archive(source_url, tmp_path / "valid.zip")
    bundle = read_bundle(archive)
    users = [json.loads(line) for line in bundle["collections"]["users"].splitlines()]
    owner = next(row["key"] for row in users if row["data"]["username"] == "one-time-1")
    records = [
        json.loads(line)
        for line in bundle["collections"]["activity_events"].splitlines()
    ]
    own = lambda row: row["data"]["owner_user_id"]["key"] == owner
    if case in {"tail", "all"}:
        records = [
            row
            for row in records
            if not (
                own(row) and (case == "all" or row["data"]["public_id"] == EVENTS[2])
            )
        ]
    elif case == "cross-owner":
        other = next(row for row in records if not own(row))
        undo = next(
            row for row in records if own(row) and row["data"]["event_type"] == "revert"
        )
        undo["data"]["reverts_event_id"] = {
            "$ref": "activity_events",
            "key": other["key"],
        }
    else:
        tail = next(
            row for row in records if own(row) and row["data"]["public_id"] == EVENTS[2]
        )
        tail["data"]["one_time_expected_version"] = 2.5
    replace_records(bundle, "activity_events", records)  # Recompute genuine checksum.
    invalid = write_bundle(tmp_path / "invalid.zip", bundle)
    target = tmp_path / "target.sqlite"
    target_url = migrate(target)
    before = database_dump(target)
    with pytest.raises(StorageValidationError, match="invalid one-time history"):
        import_archive(target_url, invalid)
    assert database_dump(target) == before  # Includes identity, epoch and sequences.
    import_archive(target_url, archive)
    assert inspect_database(target).valid


async def test_tampered_physical_backup_rejected_before_target_is_changed(
    runtime_engine, tmp_path
):
    factory = await setup_database(runtime_engine)
    await submit(factory, [operation()])
    backup, manifest_file = create_backup(
        Path(runtime_engine.url.database), tmp_path / "backups", apply_retention=False
    )
    with closing(sqlite3.connect(backup)) as connection:
        connection.execute("DELETE FROM activity_events WHERE owner_user_id=1")
        connection.commit()
    manifest = json.loads(manifest_file.read_text())
    manifest["database_sha256"] = hashlib.sha256(backup.read_bytes()).hexdigest()
    manifest_file.write_text(json.dumps(manifest))
    target = tmp_path / "untouched.sqlite"
    migrate(target)
    before = database_dump(target)
    with pytest.raises(StorageValidationError, match="domain checks"):
        restore_backup(backup, target)
    assert database_dump(target) == before


async def test_physical_restore_reopens_complete_history_and_replays_original_ids(
    runtime_engine, tmp_path
):
    factory = await setup_database(runtime_engine)
    operations = [operation(index) for index in range(3)]
    first = await submit(factory, operations)
    await submit(factory, [operation()], owner=2)
    source = Path(runtime_engine.url.database)
    original = inspect_database(source)
    backup, _ = create_backup(source, tmp_path / "backups", apply_retention=False)
    restored = tmp_path / "restored.sqlite"
    _, epoch = restore_backup(
        backup, restored, expected_alembic_head=original.alembic_head
    )
    inspection = inspect_database(restored)
    assert (
        inspection.valid
        and inspection.server_instance_id == original.server_instance_id
    )
    assert inspection.sync_epoch == epoch and epoch != original.sync_epoch
    assert inspection.row_counts == original.row_counts
    assert not list(tmp_path.glob(".restored.sqlite.restore-*"))
    engine = build_database_adapter("sqlite", None, str(restored)).create_async_engine()
    try:
        restored_factory = async_sessionmaker(engine, expire_on_commit=False)
        async with restored_factory() as session:
            assert (await read_one_time_checkpoints(session, 1))[0].state.version == 3
            assert (await read_one_time_checkpoints(session, 2))[0].state.version == 1
        assert await submit(restored_factory, operations) == [
            {**result, "status": "already_applied"} for result in first
        ]
    finally:
        await engine.dispose()


async def test_logical_export_keeps_one_snapshot_across_concurrent_commit(
    runtime_engine, tmp_path
):
    factory = await setup_database(runtime_engine)
    await submit(factory, [operation()])
    source = Path(runtime_engine.url.database)
    source_url = str(runtime_engine.url.set(drivername="sqlite"))
    changed = False

    def concurrent_write(
        connection, cursor, statement, parameters, context, executemany
    ):
        nonlocal changed
        if changed or connection.engine.url.database != str(source):
            return
        # After portable collection export reads details, before it reads events.
        if not statement.startswith("SELECT activity_details."):
            return
        changed = True
        with closing(sqlite3.connect(source)) as writer:
            writer.execute("BEGIN")
            writer.execute("DELETE FROM activity_events WHERE owner_user_id=1")
            writer.execute(
                "UPDATE activity_details SET one_time_version=0, one_time_head_event_uuid=NULL, "
                "one_time_completion_event_uuid=NULL WHERE node_id=1"
            )
            writer.commit()

    event.listen(Engine, "after_cursor_execute", concurrent_write)
    try:
        archive = export_archive(source_url, tmp_path / "snapshot.zip")
    finally:
        event.remove(Engine, "after_cursor_execute", concurrent_write)
    assert changed
    bundle = read_bundle(archive)
    details = [
        json.loads(line)["data"]
        for line in bundle["collections"]["activity_details"].splitlines()
    ]
    assert sorted(row["one_time_version"] for row in details) == [0, 1]
    assert bundle["manifest"]["collections"]["activity_events"]["rows"] == 1
    target = tmp_path / "snapshot-target.sqlite"
    import_archive(migrate(target), archive)
    assert inspect_database(target).valid
