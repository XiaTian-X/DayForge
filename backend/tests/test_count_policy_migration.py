"""Original count rules survive migration, undo and both recovery formats."""

from contextlib import closing
from copy import deepcopy
from datetime import UTC, date, datetime
import json
import sqlite3

from alembic import command
import pytest
from sqlalchemy import Engine, create_engine, event
from sqlmodel import Session

from src.storage.logical_archive import export_archive, import_archive
from src.storage.sqlite_maintenance import (
    StorageValidationError,
    create_backup,
    inspect_database,
    restore_backup,
)
from src.v2.models import ActivityCountDay, ActivityDetail, ActivityEvent, PlanNode
from tests.test_alembic_migration import alembic_config
from tests.test_logical_archive import migrate, seed_source
from tests.test_logical_archive_identity import (
    database_dump,
    read_bundle,
    replace_records,
    write_bundle,
)


def seed_counts(path):
    url = migrate(path)
    seed_source(url)
    engine = create_engine(url)
    try:
        with Session(engine) as session:
            for owner, target, countdown in ((101, 10, False), (205, 20, True)):
                activity = PlanNode(
                    owner_user_id=owner,
                    created_by_user_id=owner,
                    node_kind="activity",
                    public_id="71000000-0000-4000-8000-000000000001",
                    title=f"count-{owner}",
                )
                session.add(activity)
                session.flush()
                session.add(
                    ActivityDetail(
                        node_id=activity.id, tracking_mode="count", target_value=99
                    )
                )
                session.flush()
                policy = json.dumps(
                    dict(target_value=target, is_countdown=countdown),
                    sort_keys=True,
                    separators=(",", ":"),
                )
                fact = ActivityEvent(
                    owner_user_id=owner,
                    activity_node_id=activity.id,
                    event_type="count_delta",
                    value=6,
                    occurred_at=datetime(2026, 10, 8, 1, tzinfo=UTC),
                    local_date=date(2026, 10, 8),
                    timezone="Asia/Shanghai",
                    public_id="71000000-0000-4000-8000-000000000002",
                    count_policy_json=policy,
                )
                session.add(fact)
                session.flush()
                session.add(
                    ActivityCountDay(
                        owner_user_id=owner,
                        activity_node_id=activity.id,
                        local_date=fact.local_date,
                        target_value=target,
                        is_countdown=countdown,
                        first_event_id=fact.id,
                    )
                )
                session.add(
                    ActivityEvent(
                        owner_user_id=owner,
                        activity_node_id=activity.id,
                        event_type="revert",
                        reverts_event_id=fact.id,
                        occurred_at=datetime(2026, 10, 9, 1, tzinfo=UTC),
                        local_date=date(2026, 10, 9),
                        timezone="Asia/Shanghai",
                    )
                )
            session.commit()
    finally:
        engine.dispose()


def test_incremental_migration_preserves_legacy_rows_and_never_guesses_policy(tmp_path):
    source = tmp_path / "legacy.db"
    migrate(source)
    seed_source(f"sqlite:///{source}")
    config = alembic_config(str(source))
    command.downgrade(config, "000000000006")
    with closing(sqlite3.connect(source)) as connection:
        events = connection.execute("SELECT * FROM activity_events").fetchall()
        plans = connection.execute("SELECT * FROM plan_nodes").fetchall()
    command.upgrade(config, "head")
    command.check(config)
    with closing(sqlite3.connect(source)) as connection:
        assert connection.execute("SELECT * FROM plan_nodes").fetchall() == plans
        rows = connection.execute("SELECT * FROM activity_events").fetchall()
        assert [row[:-1] for row in rows] == events and all(
            row[-1] is None for row in rows
        )
        assert connection.execute("SELECT * FROM activity_count_days").fetchall() == []
        assert connection.execute("PRAGMA foreign_key_check").fetchall() == []
    command.downgrade(config, "000000000006")
    with closing(sqlite3.connect(source)) as connection:
        assert connection.execute("SELECT * FROM activity_events").fetchall() == events


def test_final_migration_failure_rolls_back_new_table_column_and_version(tmp_path):
    source = tmp_path / "failure.db"
    migrate(source, revision="000000000006")
    before = database_dump(source)

    def fail(connection, _cursor, statement, _parameters, _context, _many):
        if (
            connection.engine.url.database == str(source)
            and "UPDATE alembic_version" in statement
        ):
            raise RuntimeError("injected count checkpoint failure")

    event.listen(Engine, "before_cursor_execute", fail)
    try:
        with pytest.raises(RuntimeError, match="count checkpoint"):
            command.upgrade(alembic_config(str(source)), "head")
    finally:
        event.remove(Engine, "before_cursor_execute", fail)
    assert database_dump(source) == before
    command.upgrade(alembic_config(str(source)), "head")
    command.check(alembic_config(str(source)))


def test_nonempty_policy_downgrade_and_offline_bypass_are_rejected_without_change(
    tmp_path,
):
    source = tmp_path / "rules.db"
    seed_counts(source)
    before = database_dump(source)
    with pytest.raises(RuntimeError, match="count day rules exist"):
        command.downgrade(alembic_config(str(source)), "000000000006")
    assert database_dump(source) == before
    with pytest.raises(RuntimeError, match="online evidence check"):
        command.downgrade(
            alembic_config(str(tmp_path / "unused.db")),
            "000000000007:000000000006",
            sql=True,
        )
    assert not (tmp_path / "unused.db").exists()


@pytest.mark.parametrize("kind", ["logical", "physical"])
def test_owned_same_uuid_original_rules_and_undo_survive_recovery(tmp_path, kind):
    source, target = tmp_path / "source.db", tmp_path / "target.db"
    seed_counts(source)
    before = database_dump(source)
    assert inspect_database(source).valid
    if kind == "physical":
        backup, _ = create_backup(source, tmp_path / "backups", kind="manual")
        restore_backup(backup, target, expected_alembic_head="000000000008")
    else:
        archive = export_archive(f"sqlite:///{source}", tmp_path / "archive.zip")
        bundle = read_bundle(archive)
        records = [
            json.loads(line)
            for line in bundle["collections"]["activity_count_days"].splitlines()
        ]
        assert len(records) == 2 and records[0]["key"] != records[1]["key"]
        assert all(
            "owner:" in row["key"] and "count-day:2026-10-08" in row["key"]
            for row in records
        )
        import_archive(migrate(target), archive)
    assert inspect_database(target).valid
    with closing(sqlite3.connect(target)) as connection:
        rows = connection.execute(
            "SELECT u.public_id,n.public_id,d.local_date,d.target_value,d.is_countdown,e.public_id,e.count_policy_json "
            "FROM activity_count_days d JOIN users u ON u.id=d.owner_user_id JOIN plan_nodes n ON n.id=d.activity_node_id "
            "JOIN activity_events e ON e.id=d.first_event_id ORDER BY d.target_value"
        ).fetchall()
        assert len(rows) == 2 and rows[0][0] != rows[1][0] and rows[0][1] == rows[1][1]
        assert [row[3:5] for row in rows] == [(10, 0), (20, 1)]
        assert all(json.loads(row[6])["target_value"] == row[3] for row in rows)
        assert (
            connection.execute(
                "SELECT COUNT(*) FROM activity_events WHERE event_type='revert'"
            ).fetchone()[0]
            == 2
        )
    assert database_dump(source) == before


@pytest.mark.parametrize(
    "field,value",
    [
        ("target_value", 11),
        ("target_value", "10"),
        ("is_countdown", "false"),
        ("is_countdown", 2),
        ("is_countdown", 0),
    ],
)
def test_rechecksummed_archive_cannot_change_historical_goal_or_partially_import(
    tmp_path,
    field,
    value,
):
    source, target = tmp_path / "source.db", tmp_path / "target.db"
    seed_counts(source)
    original = export_archive(f"sqlite:///{source}", tmp_path / "original.zip")
    bundle = deepcopy(read_bundle(original))
    records = [
        json.loads(line)
        for line in bundle["collections"]["activity_count_days"].splitlines()
    ]
    records[0]["data"][field] = value
    replace_records(bundle, "activity_count_days", records)
    damaged = write_bundle(tmp_path / "damaged.zip", bundle)
    url = migrate(target)
    before = database_dump(target)
    with pytest.raises(StorageValidationError, match="invalid count history"):
        import_archive(url, damaged)
    assert database_dump(target) == before


@pytest.mark.parametrize("damage", ["day", "proof", "missing"])
def test_invalid_day_proof_is_not_exported_or_physically_backed_up(tmp_path, damage):
    source = tmp_path / "source.db"
    seed_counts(source)
    with closing(sqlite3.connect(source)) as connection:
        if damage == "day":
            connection.execute("UPDATE activity_count_days SET target_value=99")
        elif damage == "proof":
            connection.execute(
                "UPDATE activity_events SET count_policy_json='{}' WHERE count_policy_json IS NOT NULL"
            )
        else:
            connection.execute("DELETE FROM activity_count_days")
        connection.commit()
    inspection = inspect_database(source)
    assert not inspection.valid and any(
        "COUNT_DAY_INVALID" in item for item in inspection.domain_errors
    )
    with pytest.raises(StorageValidationError):
        create_backup(source, tmp_path / "backups", kind="manual")
    with pytest.raises(StorageValidationError, match="invalid count history"):
        export_archive(f"sqlite:///{source}", tmp_path / "invalid.zip")
