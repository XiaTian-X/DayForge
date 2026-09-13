"""Database models for the account-scoped DayForge v2 domain.

The V2 schema is the canonical habit, activity and metric data model. Public
APIs use UUIDs; integer IDs are internal only.
"""

from datetime import date, datetime, time
from decimal import Decimal
from enum import Enum
from typing import Optional
import uuid

from sqlalchemy import CheckConstraint, Index, UniqueConstraint, text
from sqlmodel import Field, SQLModel

from src.time_utils import utc_now


def new_uuid() -> str:
    return str(uuid.uuid4())


class SyncableFields(SQLModel):
    """Fields shared by records that participate in v2 synchronization."""

    public_id: str = Field(default_factory=new_uuid, max_length=36, index=True)
    revision: int = Field(default=1, ge=1)
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)
    deleted_at: Optional[datetime] = Field(default=None, index=True)


class NodeKind(str, Enum):
    GOAL = "goal"
    ACTIVITY = "activity"


class NodeStatus(str, Enum):
    ACTIVE = "active"
    PAUSED = "paused"
    COMPLETED = "completed"
    FAILED = "failed"
    ARCHIVED = "archived"


class TrackingMode(str, Enum):
    CHECK = "check"
    COUNT = "count"
    DURATION = "duration"


class CompletionPolicy(str, Enum):
    RECURRING = "recurring"
    ONE_AND_DONE = "one_and_done"


class ActivityEventType(str, Enum):
    CHECK_IN = "check_in"
    COUNT_DELTA = "count_delta"
    COUNT_SNAPSHOT = "count_snapshot"
    DURATION_SESSION = "duration_session"
    REVERT = "revert"


class SourceType(str, Enum):
    APP = "app"
    WIDGET = "widget"
    API = "api"
    SMART_DEVICE = "smart_device"
    AUTOMATION = "automation"
    IMPORT = "import"


class TimerState(str, Enum):
    RUNNING = "running"
    PAUSED = "paused"
    COMPLETED = "completed"
    CANCELLED = "cancelled"


class ServerInstance(SQLModel, table=True):
    """Stable identity shared by local and reverse-proxy entry points."""

    __tablename__ = "server_instances"

    id: int = Field(default=1, primary_key=True)
    instance_uuid: str = Field(default_factory=new_uuid, max_length=36, unique=True)
    sync_epoch: str = Field(default_factory=new_uuid, max_length=36, unique=True)
    protocol_version: int = Field(default=4, ge=1)
    created_at: datetime = Field(default_factory=utc_now)


class UserProfile(SQLModel, table=True):
    __tablename__ = "user_profiles"
    __table_args__ = (UniqueConstraint("user_id", name="uq_user_profiles_user"),)

    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(foreign_key="users.id", nullable=False, index=True)
    display_name: Optional[str] = Field(default=None, max_length=100)
    avatar_url: Optional[str] = Field(default=None, max_length=500)
    timezone: str = Field(default="UTC", max_length=64)
    locale: str = Field(default="zh-CN", max_length=20)
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)


class Household(SyncableFields, table=True):
    __tablename__ = "households"
    __table_args__ = (
        UniqueConstraint("public_id", name="uq_households_public_id"),
    )

    id: Optional[int] = Field(default=None, primary_key=True)
    name: str = Field(min_length=1, max_length=100)
    created_by_user_id: int = Field(foreign_key="users.id", nullable=False, index=True)


class HouseholdMembership(SyncableFields, table=True):
    __tablename__ = "household_memberships"
    __table_args__ = (
        UniqueConstraint("public_id", name="uq_household_memberships_public_id"),
        UniqueConstraint("household_id", "user_id", name="uq_household_membership_user"),
        CheckConstraint("role IN ('owner','admin','member')", name="ck_household_membership_role"),
        CheckConstraint(
            "status IN ('invited','active','left','removed')",
            name="ck_household_membership_status",
        ),
    )

    id: Optional[int] = Field(default=None, primary_key=True)
    household_id: int = Field(foreign_key="households.id", nullable=False, index=True)
    user_id: int = Field(foreign_key="users.id", nullable=False, index=True)
    role: str = Field(default="member", max_length=20)
    status: str = Field(default="active", max_length=20)
    joined_at: Optional[datetime] = Field(default=None)


class ClientDevice(SQLModel, table=True):
    __tablename__ = "client_devices"
    __table_args__ = (
        UniqueConstraint("public_id", name="uq_client_devices_public_id"),
        UniqueConstraint("user_id", "installation_id", name="uq_client_device_installation"),
        CheckConstraint(
            "platform IN ('android','ios','desktop','hardware','service')",
            name="ck_client_device_platform",
        ),
        CheckConstraint(
            "device_class IN ('interactive','hardware','automation')",
            name="ck_client_device_class",
        ),
        CheckConstraint(
            "capability_revision >= 1",
            name="ck_client_device_capability_revision",
        ),
    )

    id: Optional[int] = Field(default=None, primary_key=True)
    public_id: str = Field(default_factory=new_uuid, max_length=36, index=True)
    installation_id: str = Field(max_length=100, index=True)
    user_id: int = Field(foreign_key="users.id", nullable=False, index=True)
    platform: str = Field(max_length=20)
    app_version: Optional[str] = Field(default=None, max_length=50)
    display_name: Optional[str] = Field(default=None, max_length=100)
    device_class: str = Field(default="interactive", max_length=20, index=True)
    structural_edit_enabled: bool = Field(default=False)
    capability_revision: int = Field(default=1, ge=1)
    created_at: datetime = Field(default_factory=utc_now)
    last_seen_at: datetime = Field(default_factory=utc_now)
    revoked_at: Optional[datetime] = Field(default=None, index=True)


class UserSyncPolicy(SQLModel, table=True):
    """Per-account device policy; family/admin roles never imply data access."""

    __tablename__ = "user_sync_policies"
    __table_args__ = (
        CheckConstraint("revision >= 1", name="ck_user_sync_policy_revision"),
    )

    user_id: int = Field(foreign_key="users.id", primary_key=True)
    primary_editor_device_id: Optional[int] = Field(
        default=None,
        foreign_key="client_devices.id",
        index=True,
    )
    revision: int = Field(default=1, ge=1)
    updated_at: datetime = Field(default_factory=utc_now)


class PlanNode(SyncableFields, table=True):
    __tablename__ = "plan_nodes"
    __table_args__ = (
        UniqueConstraint("owner_user_id", "public_id", name="uq_plan_node_owner_public_id"),
        CheckConstraint("node_kind IN ('goal','activity')", name="ck_plan_node_kind"),
        CheckConstraint(
            "status IN ('active','paused','completed','failed','archived')",
            name="ck_plan_node_status",
        ),
        CheckConstraint("parent_node_id IS NULL OR parent_node_id != id", name="ck_plan_node_not_self_parent"),
    )

    id: Optional[int] = Field(default=None, primary_key=True)
    owner_user_id: int = Field(foreign_key="users.id", nullable=False, index=True)
    created_by_user_id: int = Field(foreign_key="users.id", nullable=False, index=True)
    parent_node_id: Optional[int] = Field(default=None, foreign_key="plan_nodes.id", index=True)
    node_kind: str = Field(max_length=20, index=True)
    title: str = Field(min_length=1, max_length=100)
    description: str = Field(default="", max_length=1000)
    icon: str = Field(default="favorite", max_length=100)
    color_hex: str = Field(default="#2196F3", max_length=20)
    status: str = Field(default=NodeStatus.ACTIVE.value, max_length=20, index=True)
    visibility: str = Field(default="private", max_length=20)
    sort_order: int = Field(default=0)


class GoalDetail(SQLModel, table=True):
    __tablename__ = "goal_details"

    node_id: int = Field(foreign_key="plan_nodes.id", primary_key=True)
    start_date: Optional[date] = Field(default=None)
    due_date: Optional[date] = Field(default=None)
    target_cycles: Optional[int] = Field(default=None)
    failure_policy_json: str = Field(default='{"schema_version":1,"type":"strict"}')
    evaluation_policy_json: str = Field(default='{"schema_version":1,"type":"manual"}')
    manual_result: Optional[str] = Field(default=None, max_length=20)


class ActivityDetail(SQLModel, table=True):
    __tablename__ = "activity_details"
    __table_args__ = (
        CheckConstraint("tracking_mode IN ('check','count','duration')", name="ck_activity_tracking_mode"),
        CheckConstraint(
            "completion_policy IN ('recurring','one_and_done')",
            name="ck_activity_completion_policy",
        ),
        CheckConstraint("target_value >= 0", name="ck_activity_target_value"),
        CheckConstraint("target_cycles IS NULL OR target_cycles > 0", name="ck_activity_target_cycles"),
    )

    node_id: int = Field(foreign_key="plan_nodes.id", primary_key=True)
    tracking_mode: str = Field(max_length=20)
    is_countdown: bool = Field(default=False)
    recurrence_rule_json: str = Field(default='{"schema_version":1,"type":"daily","interval":1}')
    completion_policy: str = Field(default=CompletionPolicy.RECURRING.value, max_length=20)
    target_value: Decimal = Field(default=Decimal("1"), decimal_places=4, max_digits=18)
    target_unit: Optional[str] = Field(default=None, max_length=50)
    target_cycles: Optional[int] = Field(default=None)
    failure_policy_json: str = Field(default='{"schema_version":1,"type":"strict"}')
    preferred_local_time: Optional[time] = Field(default=None)
    timezone: str = Field(default="UTC", max_length=64)
    origin_assignment_id: Optional[str] = Field(default=None, max_length=36, index=True)


class ActivityEvent(SyncableFields, table=True):
    __tablename__ = "activity_events"
    __table_args__ = (
        UniqueConstraint("owner_user_id", "public_id", name="uq_activity_event_owner_public_id"),
        UniqueConstraint(
            "source_device_public_id",
            "external_event_id",
            name="uq_activity_event_external_source",
        ),
        Index(
            "uq_activity_event_revert_target",
            "reverts_event_id",
            unique=True,
            sqlite_where=text("reverts_event_id IS NOT NULL"),
            postgresql_where=text("reverts_event_id IS NOT NULL"),
        ),
        CheckConstraint(
            "event_type IN ('check_in','count_delta','count_snapshot','duration_session','revert')",
            name="ck_activity_event_type",
        ),
        CheckConstraint("duration_seconds IS NULL OR duration_seconds >= 0", name="ck_activity_event_duration"),
        CheckConstraint(
            "duration_milliseconds IS NULL OR duration_milliseconds >= 0",
            name="ck_activity_event_duration_ms",
        ),
    )

    id: Optional[int] = Field(default=None, primary_key=True)
    owner_user_id: int = Field(foreign_key="users.id", nullable=False, index=True)
    activity_node_id: int = Field(foreign_key="activity_details.node_id", nullable=False, index=True)
    event_type: str = Field(max_length=30, index=True)
    value: Optional[Decimal] = Field(default=None, decimal_places=4, max_digits=18)
    duration_seconds: Optional[int] = Field(default=None)
    duration_milliseconds: Optional[int] = Field(default=None)
    started_at: Optional[datetime] = Field(default=None)
    ended_at: Optional[datetime] = Field(default=None)
    occurred_at: datetime = Field(default_factory=utc_now, index=True)
    local_date: date = Field(index=True)
    timezone: str = Field(default="UTC", max_length=64)
    note: str = Field(default="", max_length=1000)
    source_type: str = Field(default=SourceType.APP.value, max_length=30)
    source_device_public_id: Optional[str] = Field(default=None, max_length=36, index=True)
    external_event_id: Optional[str] = Field(default=None, max_length=200)
    recorded_by_user_id: Optional[int] = Field(default=None, foreign_key="users.id", index=True)
    reverts_event_id: Optional[int] = Field(default=None, foreign_key="activity_events.id", index=True)
    payload_json: str = Field(default="{}")
    received_at: datetime = Field(default_factory=utc_now)


class TrackedMetric(SyncableFields, table=True):
    __tablename__ = "tracked_metrics"
    __table_args__ = (
        UniqueConstraint("owner_user_id", "public_id", name="uq_tracked_metric_owner_public_id"),
        CheckConstraint("decimal_places BETWEEN 0 AND 6", name="ck_tracked_metric_decimal_places"),
    )

    id: Optional[int] = Field(default=None, primary_key=True)
    owner_user_id: int = Field(foreign_key="users.id", nullable=False, index=True)
    created_by_user_id: int = Field(foreign_key="users.id", nullable=False, index=True)
    name: str = Field(min_length=1, max_length=100)
    description: str = Field(default="", max_length=1000)
    unit: str = Field(min_length=1, max_length=50)
    decimal_places: int = Field(default=0)
    aggregation_type: str = Field(default="average", max_length=30)
    target_direction: Optional[str] = Field(default=None, max_length=30)
    target_value: Optional[Decimal] = Field(default=None, decimal_places=6, max_digits=20)
    target_value_upper: Optional[Decimal] = Field(default=None, decimal_places=6, max_digits=20)
    icon: str = Field(default="favorite", max_length=100)
    color_hex: str = Field(default="#2196F3", max_length=20)
    status: str = Field(default="active", max_length=20, index=True)


class MetricObservation(SyncableFields, table=True):
    __tablename__ = "metric_observations"
    __table_args__ = (
        UniqueConstraint("owner_user_id", "public_id", name="uq_metric_observation_owner_public_id"),
        UniqueConstraint(
            "source_device_public_id",
            "external_event_id",
            name="uq_metric_observation_external_source",
        ),
    )

    id: Optional[int] = Field(default=None, primary_key=True)
    owner_user_id: int = Field(foreign_key="users.id", nullable=False, index=True)
    metric_id: int = Field(foreign_key="tracked_metrics.id", nullable=False, index=True)
    value: Decimal = Field(decimal_places=6, max_digits=20)
    unit: str = Field(max_length=50)
    occurred_at: datetime = Field(default_factory=utc_now, index=True)
    local_date: date = Field(index=True)
    timezone: str = Field(default="UTC", max_length=64)
    note: str = Field(default="", max_length=1000)
    source_type: str = Field(default=SourceType.APP.value, max_length=30)
    source_device_public_id: Optional[str] = Field(default=None, max_length=36, index=True)
    external_event_id: Optional[str] = Field(default=None, max_length=200)
    recorded_by_user_id: Optional[int] = Field(default=None, foreign_key="users.id", index=True)
    payload_json: str = Field(default="{}")
    received_at: datetime = Field(default_factory=utc_now)


class ActivityMetricLinkV2(SyncableFields, table=True):
    __tablename__ = "activity_metric_links_v2"
    __table_args__ = (
        UniqueConstraint("owner_user_id", "public_id", name="uq_activity_metric_link_owner_public_id"),
        Index(
            "uq_activity_metric_link_active_pair",
            "activity_node_id",
            "metric_id",
            unique=True,
            sqlite_where=text("deleted_at IS NULL"),
            postgresql_where=text("deleted_at IS NULL"),
        ),
    )

    id: Optional[int] = Field(default=None, primary_key=True)
    owner_user_id: int = Field(foreign_key="users.id", nullable=False, index=True)
    activity_node_id: int = Field(foreign_key="activity_details.node_id", nullable=False, index=True)
    metric_id: int = Field(foreign_key="tracked_metrics.id", nullable=False, index=True)
    coefficient: Decimal = Field(default=Decimal("1"), decimal_places=6, max_digits=20)
    show_in_activity_detail: bool = Field(default=True)
    prompt_on_complete: bool = Field(default=False)
    is_active: bool = Field(default=True)


class TimerSession(SQLModel, table=True):
    __tablename__ = "timer_sessions"
    __table_args__ = (
        UniqueConstraint("owner_user_id", "public_id", name="uq_timer_session_owner_public_id"),
        Index(
            "uq_timer_session_owner_active",
            "owner_user_id",
            unique=True,
            sqlite_where=text("state IN ('running','paused')"),
            postgresql_where=text("state IN ('running','paused')"),
        ),
        CheckConstraint(
            "state IN ('running','paused','completed','cancelled')",
            name="ck_timer_session_state",
        ),
        CheckConstraint("control_generation >= 1", name="ck_timer_session_generation"),
        CheckConstraint("revision >= 1", name="ck_timer_session_revision"),
        CheckConstraint("next_command_sequence >= 2", name="ck_timer_session_next_sequence"),
        CheckConstraint("active_elapsed_ms >= 0", name="ck_timer_session_elapsed"),
        CheckConstraint("max_duration_seconds > 0", name="ck_timer_session_max_duration"),
    )

    id: Optional[int] = Field(default=None, primary_key=True)
    public_id: str = Field(default_factory=new_uuid, max_length=36, index=True)
    owner_user_id: int = Field(foreign_key="users.id", nullable=False, index=True)
    activity_node_id: int = Field(foreign_key="activity_details.node_id", nullable=False, index=True)
    state: str = Field(default=TimerState.RUNNING.value, max_length=20, index=True)
    controller_device_id: int = Field(foreign_key="client_devices.id", nullable=False, index=True)
    control_generation: int = Field(default=1)
    revision: int = Field(default=1)
    next_command_sequence: int = Field(default=2)
    started_at: datetime
    state_changed_at: datetime
    ended_at: Optional[datetime] = Field(default=None)
    timezone: str = Field(max_length=64)
    is_countdown: bool = Field(default=False)
    target_seconds: int = Field(default=0, ge=0)
    max_duration_seconds: int = Field(default=86_400, gt=0)
    active_elapsed_ms: int = Field(default=0, ge=0)
    last_heartbeat_at: Optional[datetime] = Field(default=None)
    completed_event_id: Optional[int] = Field(default=None, foreign_key="activity_events.id", index=True)
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)


class TimerSegment(SQLModel, table=True):
    __tablename__ = "timer_segments"
    __table_args__ = (
        UniqueConstraint("session_id", "sequence", name="uq_timer_segment_session_sequence"),
        Index(
            "uq_timer_segment_session_open",
            "session_id",
            unique=True,
            sqlite_where=text("ended_at IS NULL"),
            postgresql_where=text("ended_at IS NULL"),
        ),
        CheckConstraint("sequence >= 1", name="ck_timer_segment_sequence"),
        CheckConstraint("duration_ms IS NULL OR duration_ms >= 0", name="ck_timer_segment_duration"),
    )

    id: Optional[int] = Field(default=None, primary_key=True)
    session_id: int = Field(foreign_key="timer_sessions.id", nullable=False, index=True)
    sequence: int = Field(ge=1)
    started_at: datetime
    ended_at: Optional[datetime] = Field(default=None)
    duration_ms: Optional[int] = Field(default=None, ge=0)


class TimerCommand(SQLModel, table=True):
    __tablename__ = "timer_commands"
    __table_args__ = (
        UniqueConstraint("device_id", "command_id", name="uq_timer_command_device_command"),
        CheckConstraint(
            "command_type IN ('start','pause','resume','stop','cancel','takeover')",
            name="ck_timer_command_type",
        ),
    )

    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(foreign_key="users.id", nullable=False, index=True)
    device_id: int = Field(foreign_key="client_devices.id", nullable=False, index=True)
    command_id: str = Field(max_length=36, index=True)
    session_public_id: str = Field(max_length=36, index=True)
    command_sequence: int = Field(ge=1, index=True)
    command_type: str = Field(max_length=20)
    request_hash: str = Field(max_length=64)
    status: str = Field(max_length=30, index=True)
    result_json: str = Field(default="{}")
    error_code: Optional[str] = Field(default=None, max_length=100)
    received_at: datetime = Field(default_factory=utc_now)
    completed_at: Optional[datetime] = Field(default=None)


class DurationDayAllocation(SQLModel, table=True):
    __tablename__ = "duration_day_allocations"
    __table_args__ = (
        UniqueConstraint("activity_event_id", "local_date", name="uq_duration_allocation_event_date"),
        CheckConstraint("duration_ms > 0", name="ck_duration_allocation_positive"),
    )

    id: Optional[int] = Field(default=None, primary_key=True)
    activity_event_id: int = Field(foreign_key="activity_events.id", nullable=False, index=True)
    local_date: date = Field(index=True)
    timezone: str = Field(max_length=64)
    duration_ms: int = Field(gt=0)


class SyncOperation(SQLModel, table=True):
    __tablename__ = "sync_operations"
    __table_args__ = (
        UniqueConstraint("device_id", "operation_id", name="uq_sync_operation_device_operation"),
    )

    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(foreign_key="users.id", nullable=False, index=True)
    device_id: int = Field(foreign_key="client_devices.id", nullable=False, index=True)
    operation_id: str = Field(max_length=36, index=True)
    request_hash: str = Field(max_length=64)
    status: str = Field(max_length=30, index=True)
    entity_type: str = Field(max_length=40)
    entity_uuid: str = Field(max_length=36, index=True)
    action: str = Field(max_length=20)
    base_revision: Optional[int] = Field(default=None)
    result_json: str = Field(default="{}")
    error_code: Optional[str] = Field(default=None, max_length=100)
    received_at: datetime = Field(default_factory=utc_now)
    completed_at: Optional[datetime] = Field(default=None)


class EntityRevisionSnapshot(SQLModel, table=True):
    """Canonical immutable payload for deterministic three-way merges."""

    __tablename__ = "entity_revision_snapshots"
    __table_args__ = (
        UniqueConstraint(
            "owner_user_id",
            "entity_type",
            "entity_uuid",
            "revision",
            name="uq_entity_revision_snapshot",
        ),
        CheckConstraint("revision >= 1", name="ck_entity_revision_snapshot_revision"),
        CheckConstraint(
            "operation IN ('upsert','delete')",
            name="ck_entity_revision_snapshot_operation",
        ),
    )

    id: Optional[int] = Field(default=None, primary_key=True)
    owner_user_id: int = Field(foreign_key="users.id", nullable=False, index=True)
    entity_type: str = Field(max_length=40, index=True)
    entity_uuid: str = Field(max_length=36, index=True)
    revision: int = Field(ge=1)
    operation: str = Field(max_length=20)
    payload_json: str = Field(default="{}")
    payload_hash: str = Field(max_length=64)
    origin_device_id: Optional[int] = Field(default=None, foreign_key="client_devices.id", index=True)
    origin_operation_id: Optional[str] = Field(default=None, max_length=36, index=True)
    created_at: datetime = Field(default_factory=utc_now, index=True)


class SyncChange(SQLModel, table=True):
    __tablename__ = "sync_changes"
    __table_args__ = (
        Index("ix_sync_changes_recipient_sequence", "recipient_user_id", "sequence"),
        {"sqlite_autoincrement": True},
    )

    sequence: Optional[int] = Field(default=None, primary_key=True)
    recipient_user_id: int = Field(foreign_key="users.id", nullable=False, index=True)
    entity_type: str = Field(max_length=40, index=True)
    entity_uuid: str = Field(max_length=36, index=True)
    operation: str = Field(max_length=20)
    revision: int = Field(ge=1)
    payload_json: str = Field(default="{}")
    origin_user_id: int = Field(foreign_key="users.id", nullable=False, index=True)
    origin_device_id: Optional[int] = Field(default=None, foreign_key="client_devices.id", index=True)
    origin_operation_id: Optional[str] = Field(default=None, max_length=36, index=True)
    changed_at: datetime = Field(default_factory=utc_now, index=True)


class SyncCursor(SQLModel, table=True):
    __tablename__ = "sync_cursors"
    __table_args__ = (
        UniqueConstraint("user_id", "device_id", name="uq_sync_cursor_user_device"),
    )

    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(foreign_key="users.id", nullable=False, index=True)
    device_id: int = Field(foreign_key="client_devices.id", nullable=False, index=True)
    last_pulled_sequence: int = Field(default=0, ge=0)
    last_pull_at: Optional[datetime] = Field(default=None)
    last_push_at: Optional[datetime] = Field(default=None)


__all__ = [
    "ActivityDetail",
    "ActivityEvent",
    "ActivityEventType",
    "ActivityMetricLinkV2",
    "ClientDevice",
    "CompletionPolicy",
    "DurationDayAllocation",
    "EntityRevisionSnapshot",
    "GoalDetail",
    "Household",
    "HouseholdMembership",
    "MetricObservation",
    "NodeKind",
    "NodeStatus",
    "PlanNode",
    "ServerInstance",
    "SourceType",
    "SyncChange",
    "SyncCursor",
    "SyncOperation",
    "TimerCommand",
    "TimerSegment",
    "TimerSession",
    "TimerState",
    "TrackedMetric",
    "TrackingMode",
    "UserProfile",
    "UserSyncPolicy",
    "new_uuid",
    "utc_now",
]
