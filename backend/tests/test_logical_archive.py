"""SQLite-to-SQLite round-trip contract for portable archive format v1."""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta
from decimal import Decimal
from pathlib import Path
from unittest.mock import Mock

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import Column, Integer, MetaData, Table, create_engine, text
from sqlmodel import Session

from src.auth.models import User
from src.storage.logical_archive import (
    _insert_collection,
    export_archive,
    import_archive,
)
from src.storage.sqlite_maintenance import StorageValidationError
from src.tokens.models import ApiToken
from src.v2.models import (
    ActivityDetail,
    ActivityEvent,
    ActivityMetricLinkV2,
    ClientDevice,
    DurationDayAllocation,
    EntityRevisionSnapshot,
    GoalDetail,
    Household,
    HouseholdMembership,
    MetricObservation,
    PlanNode,
    SyncOperation,
    TimerCommand,
    TimerSegment,
    TimerSession,
    TrackedMetric,
    UserProfile,
    UserSyncPolicy,
)


PROJECT_ROOT = Path(__file__).resolve().parents[1]


@pytest.mark.parametrize("inserted_key", [None, (None,)])
def test_import_rejects_missing_generated_primary_key(inserted_key):
    table = Table("missing_key", MetaData(), Column("id", Integer, primary_key=True))
    connection = Mock()
    connection.execute.return_value.inserted_primary_key = inserted_key
    target_keys: dict[tuple[str, str], object] = {}
    with pytest.raises(StorageValidationError, match="missing inserted primary key"):
        _insert_collection(
            connection, table, [{"key": "synthetic", "data": {}}], target_keys
        )
    assert target_keys == {}


def migrate(path: Path) -> str:
    config = Config(PROJECT_ROOT / "alembic.ini")
    config.set_main_option("script_location", str(PROJECT_ROOT / "alembic"))
    config.set_main_option("sqlalchemy.url", f"sqlite:///{path}")
    command.upgrade(config, "head")
    return f"sqlite:///{path}"


def seed_source(database_url: str) -> tuple[str, str]:
    engine = create_engine(database_url)
    started = datetime(2026, 8, 13, 23, 59, 30, tzinfo=UTC)
    ended = started + timedelta(minutes=2)
    with Session(engine) as session:
        owner = User(
            id=101,
            public_id="00000000-0000-4000-8000-000000000001",
            username="父亲",
            password_hash="hash-1",
        )
        member = User(
            id=205,
            public_id="00000000-0000-4000-8000-000000000002",
            username="孩子",
            password_hash="hash-2",
        )
        session.add(owner)
        session.add(member)
        session.flush()
        session.add(
            UserProfile(
                user_id=owner.id, display_name="家庭管理员", timezone="Asia/Shanghai"
            )
        )
        session.add(
            ApiToken(
                user_id=owner.id,
                name="家庭自动化",
                token_hash="token-hash",
                prefix="df_token_01",
            )
        )
        household = Household(
            public_id="10000000-0000-4000-8000-000000000001",
            name="测试家庭",
            created_by_user_id=owner.id,
        )
        session.add(household)
        session.flush()
        session.add_all(
            [
                HouseholdMembership(
                    public_id="11000000-0000-4000-8000-000000000001",
                    household_id=household.id,
                    user_id=owner.id,
                    role="owner",
                ),
                HouseholdMembership(
                    public_id="11000000-0000-4000-8000-000000000002",
                    household_id=household.id,
                    user_id=member.id,
                    role="member",
                ),
            ]
        )
        device = ClientDevice(
            public_id="20000000-0000-4000-8000-000000000001",
            installation_id="phone-installation",
            user_id=owner.id,
            platform="android",
            device_class="interactive",
        )
        session.add(device)
        session.flush()
        session.add(
            UserSyncPolicy(user_id=owner.id, primary_editor_device_id=device.id)
        )
        goal = PlanNode(
            public_id="30000000-0000-4000-8000-000000000001",
            owner_user_id=owner.id,
            created_by_user_id=owner.id,
            node_kind="goal",
            title="健康目标",
        )
        session.add(goal)
        session.flush()
        session.add(GoalDetail(node_id=goal.id, target_cycles=30))
        activity = PlanNode(
            public_id="30000000-0000-4000-8000-000000000002",
            owner_user_id=owner.id,
            created_by_user_id=owner.id,
            parent_node_id=goal.id,
            node_kind="activity",
            title="睡前阅读",
        )
        deleted_activity = PlanNode(
            public_id="30000000-0000-4000-8000-000000000003",
            owner_user_id=owner.id,
            created_by_user_id=owner.id,
            node_kind="activity",
            title="已删除测试",
            deleted_at=ended,
        )
        session.add_all([activity, deleted_activity])
        session.flush()
        session.add_all(
            [
                ActivityDetail(
                    node_id=activity.id,
                    tracking_mode="duration",
                    target_value=Decimal("60.0000"),
                    target_unit="second",
                    timezone="Asia/Shanghai",
                ),
                ActivityDetail(
                    node_id=deleted_activity.id,
                    tracking_mode="check",
                    timezone="Asia/Shanghai",
                ),
            ]
        )
        metric = TrackedMetric(
            public_id="40000000-0000-4000-8000-000000000001",
            owner_user_id=owner.id,
            created_by_user_id=owner.id,
            name="体重",
            unit="kg",
            decimal_places=2,
            target_value=Decimal("65.50"),
        )
        session.add(metric)
        session.flush()
        session.add(
            ActivityMetricLinkV2(
                public_id="41000000-0000-4000-8000-000000000001",
                owner_user_id=owner.id,
                activity_node_id=activity.id,
                metric_id=metric.id,
                coefficient=Decimal("1.250000"),
            )
        )
        event = ActivityEvent(
            public_id="50000000-0000-4000-8000-000000000001",
            owner_user_id=owner.id,
            activity_node_id=activity.id,
            event_type="duration_session",
            duration_seconds=120,
            duration_milliseconds=120_000,
            started_at=started,
            ended_at=ended,
            occurred_at=ended,
            local_date=date(2026, 8, 14),
            timezone="Asia/Shanghai",
            source_device_public_id=device.public_id,
        )
        session.add(event)
        session.flush()
        session.add(
            MetricObservation(
                public_id="60000000-0000-4000-8000-000000000001",
                owner_user_id=owner.id,
                metric_id=metric.id,
                value=Decimal("67.125000"),
                unit="kg",
                occurred_at=ended,
                local_date=date(2026, 8, 14),
                timezone="Asia/Shanghai",
            )
        )
        timer = TimerSession(
            public_id="70000000-0000-4000-8000-000000000001",
            owner_user_id=owner.id,
            activity_node_id=activity.id,
            state="completed",
            controller_device_id=device.id,
            started_at=started,
            state_changed_at=ended,
            ended_at=ended,
            timezone="Asia/Shanghai",
            target_seconds=60,
            max_duration_seconds=3600,
            active_elapsed_ms=120_000,
            completed_event_id=event.id,
        )
        session.add(timer)
        session.flush()
        session.add(
            TimerSegment(
                session_id=timer.id,
                sequence=1,
                started_at=started,
                ended_at=ended,
                duration_ms=120_000,
            )
        )
        session.add(
            TimerCommand(
                user_id=owner.id,
                device_id=device.id,
                command_id="71000000-0000-4000-8000-000000000001",
                session_public_id=timer.public_id,
                command_sequence=1,
                command_type="start",
                request_hash="a" * 64,
                status="applied",
            )
        )
        session.add_all(
            [
                DurationDayAllocation(
                    activity_event_id=event.id,
                    local_date=date(2026, 8, 13),
                    timezone="Asia/Shanghai",
                    duration_ms=30_000,
                ),
                DurationDayAllocation(
                    activity_event_id=event.id,
                    local_date=date(2026, 8, 14),
                    timezone="Asia/Shanghai",
                    duration_ms=90_000,
                ),
            ]
        )
        session.add(
            SyncOperation(
                user_id=owner.id,
                device_id=device.id,
                operation_id="80000000-0000-4000-8000-000000000001",
                request_hash="b" * 64,
                status="applied",
                entity_type="plan_node",
                entity_uuid=goal.public_id,
                action="upsert",
                base_revision=0,
                result_json='{"status":"applied"}',
            )
        )
        session.add(
            EntityRevisionSnapshot(
                owner_user_id=owner.id,
                entity_type="plan_node",
                entity_uuid=goal.public_id,
                revision=1,
                operation="upsert",
                payload_json='{"title":"健康目标"}',
                payload_hash="c" * 64,
                origin_device_id=device.id,
                origin_operation_id="80000000-0000-4000-8000-000000000001",
            )
        )
        session.commit()
    with engine.connect() as connection:
        identity = connection.execute(
            text("SELECT instance_uuid, sync_epoch FROM server_instances WHERE id=1")
        ).one()
    engine.dispose()
    return identity[0], identity[1]


def test_logical_archive_round_trip_remaps_ids_and_preserves_domain_data(
    tmp_path: Path,
):
    source_url = migrate(tmp_path / "source.db")
    target_url = migrate(tmp_path / "target.db")
    instance_id, old_epoch = seed_source(source_url)
    archive = export_archive(source_url, tmp_path / "dayforge-logical-v1.zip")
    new_epoch = import_archive(target_url, archive)

    assert new_epoch != old_epoch
    engine = create_engine(target_url)
    with engine.connect() as connection:
        assert (
            connection.execute(
                text("SELECT instance_uuid FROM server_instances")
            ).scalar_one()
            == instance_id
        )
        assert (
            connection.execute(
                text("SELECT sync_epoch FROM server_instances")
            ).scalar_one()
            == new_epoch
        )
        assert connection.execute(
            text("SELECT username FROM users ORDER BY public_id")
        ).scalars().all() == ["父亲", "孩子"]
        assert (
            connection.execute(
                text(
                    "SELECT id FROM users WHERE public_id='00000000-0000-4000-8000-000000000001'"
                )
            ).scalar_one()
            != 101
        )
        hierarchy = connection.execute(
            text(
                "SELECT child.title, parent.title FROM plan_nodes child "
                "JOIN plan_nodes parent ON parent.id=child.parent_node_id"
            )
        ).one()
        assert hierarchy == ("睡前阅读", "健康目标")
        assert (
            connection.execute(
                text("SELECT COUNT(*) FROM plan_nodes WHERE deleted_at IS NOT NULL")
            ).scalar_one()
            == 1
        )
        assert (
            connection.execute(
                text("SELECT CAST(value AS TEXT) FROM metric_observations")
            )
            .scalar_one()
            .startswith("67.125")
        )
        assert connection.execute(
            text(
                "SELECT local_date, duration_ms FROM duration_day_allocations ORDER BY local_date"
            )
        ).all() == [("2026-08-13", 30_000), ("2026-08-14", 90_000)]
        assert (
            connection.execute(
                text("SELECT COUNT(*) FROM sync_operations")
            ).scalar_one()
            == 1
        )
        assert (
            connection.execute(
                text("SELECT COUNT(*) FROM entity_revision_snapshots")
            ).scalar_one()
            == 1
        )
        assert (
            connection.execute(text("SELECT COUNT(*) FROM sync_changes")).scalar_one()
            == 0
        )
        assert (
            connection.execute(text("SELECT COUNT(*) FROM sync_cursors")).scalar_one()
            == 0
        )
    engine.dispose()


def test_logical_import_rejects_nonempty_target(tmp_path: Path):
    source_url = migrate(tmp_path / "source.db")
    target_url = migrate(tmp_path / "target.db")
    seed_source(source_url)
    seed_source(target_url)
    archive = export_archive(source_url, tmp_path / "archive.zip")

    with pytest.raises(StorageValidationError, match="not empty"):
        import_archive(target_url, archive)


@pytest.mark.parametrize("state", ["running", "paused"])
def test_logical_export_rejects_active_timers_without_creating_archive(tmp_path, state):
    source_url = migrate(tmp_path / "source.db")
    seed_source(source_url)
    engine = create_engine(source_url)
    try:
        with engine.begin() as connection:
            connection.execute(
                text(
                    "UPDATE timer_sessions SET state=:state, ended_at=NULL, completed_event_id=NULL"
                ),
                {"state": state},
            )
        output = tmp_path / "blocked.zip"
        with pytest.raises(StorageValidationError, match="without active timers"):
            export_archive(source_url, output)
        assert not output.exists()
        with engine.connect() as connection:
            assert (
                connection.execute(
                    text("SELECT state FROM timer_sessions")
                ).scalar_one()
                == state
            )
    finally:
        engine.dispose()
