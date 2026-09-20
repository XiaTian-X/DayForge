"""Typed API contracts for DayForge v2 devices, domain data and sync."""

from datetime import date, datetime, time, timezone
from decimal import Decimal
from typing import Annotated, Any, Literal, Optional, Union
from uuid import UUID

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    field_serializer,
    field_validator,
    model_validator,
)

from src.v2.time_utils import local_date_at, require_iana_timezone


ANDROID_ICON_NAMES = frozenset(
    {
        "water",
        "exercise",
        "sleep",
        "food",
        "book",
        "meditation",
        "work",
        "health",
        "fitness_center",
        "directions_bike",
        "sports_gymnastics",
        "sports",
        "pool",
        "hiking",
        "directions_walk",
        "sports_soccer",
        "sports_basketball",
        "local_hospital",
        "medical_services",
        "healing",
        "bloodtype",
        "sanitizer",
        "restaurant",
        "local_pharmacy",
        "vaccines",
        "school",
        "menu_book",
        "lightbulb",
        "calculate",
        "translate",
        "science",
        "edit_note",
        "psychology",
        "code",
        "spa",
        "sentiment_satisfied",
        "mood",
        "psychology_alt",
        "sentiment_very_satisfied",
        "nature",
        "forest",
        "grain",
        "self_improvement",
        "home",
        "shopping_bag",
        "shopping_cart",
        "cleaning_services",
        "local_laundry_service",
        "pets",
        "family_restroom",
        "celebration",
        "nightlife",
        "task_alt",
        "shower",
        "bathtub",
        "wash",
        "brush_teeth",
        "face_wash",
        "hair_brush",
        "shave",
        "nail_care",
        "people",
        "group",
        "chat",
        "forum",
        "handshake",
        "account_balance",
        "savings",
        "payment",
        "receipt",
        "attach_money",
        "brush",
        "music_note",
        "camera",
        "edit",
        "design_services",
        "directions_car",
        "train",
        "flight",
        "movie",
        "tv",
        "sports_esports",
        "kitchen",
        "build",
        "iron",
        "park",
        "landscape",
        "terrain",
        "phone",
        "computer",
        "devices",
    }
)


def normalize_android_icon(value: str) -> str:
    normalized = value.lower()
    if normalized not in ANDROID_ICON_NAMES:
        raise ValueError("icon is not supported by the Android client")
    return normalized


def utc_iso(value: datetime) -> str:
    """Serialize every API timestamp as an unambiguous UTC instant."""
    if value.tzinfo is None or value.utcoffset() is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def require_aware_utc(value: Optional[datetime]) -> Optional[datetime]:
    """Reject ambiguous client timestamps and normalize accepted values to UTC."""
    if value is None:
        return None
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must include a UTC offset or Z suffix")
    return value.astimezone(timezone.utc)


class ApiModel(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
        populate_by_name=True,
    )

    @field_serializer("*", when_used="json", check_fields=False)
    def serialize_api_field(self, value: Any) -> Any:
        if isinstance(value, datetime):
            return utc_iso(value)
        return value


class DailyRule(ApiModel):
    schema_version: Literal[1] = 1
    type: Literal["daily"] = "daily"
    interval: Literal[1] = 1
    start_date: Optional[date] = None


class WeeklyRule(ApiModel):
    schema_version: Literal[1] = 1
    type: Literal["weekly"] = "weekly"
    interval: Literal[1] = 1
    weekdays: list[int] = Field(min_length=1, max_length=7)
    start_date: Optional[date] = None

    @model_validator(mode="after")
    def validate_weekdays(self):
        if any(day < 1 or day > 7 for day in self.weekdays):
            raise ValueError("weekdays must use ISO values 1..7")
        if len(set(self.weekdays)) != len(self.weekdays):
            raise ValueError("weekdays must not contain duplicates")
        self.weekdays.sort()
        return self


class MonthlyRule(ApiModel):
    schema_version: Literal[1] = 1
    type: Literal["monthly"] = "monthly"
    interval: Literal[1] = 1
    day_of_month: int = Field(ge=1, le=31)
    start_date: Optional[date] = None


class IntervalRule(ApiModel):
    schema_version: Literal[1] = 1
    type: Literal["interval"] = "interval"
    every_days: int = Field(ge=1, le=3650)
    start_date: date


class OnceRule(ApiModel):
    schema_version: Literal[1] = 1
    type: Literal["once"] = "once"
    due_date: Optional[date] = None


RecurrenceRule = Annotated[
    Union[DailyRule, WeeklyRule, MonthlyRule, IntervalRule, OnceRule],
    Field(discriminator="type"),
]


class StrictFailurePolicy(ApiModel):
    schema_version: Literal[1] = 1
    type: Literal["strict"] = "strict"


class LooseFailurePolicy(ApiModel):
    schema_version: Literal[1] = 1
    type: Literal["loose"] = "loose"


FailurePolicy = Annotated[
    Union[StrictFailurePolicy, LooseFailurePolicy],
    Field(discriminator="type"),
]


class ManualGoalEvaluation(ApiModel):
    schema_version: Literal[1] = 1
    type: Literal["manual"] = "manual"


GoalEvaluationPolicy = Annotated[
    Union[ManualGoalEvaluation],
    Field(discriminator="type"),
]


class GoalPayload(ApiModel):
    start_date: Optional[date] = None
    due_date: Optional[date] = None
    target_cycles: Optional[int] = Field(default=None, gt=0, le=2_147_483_647)
    failure_policy: FailurePolicy = Field(default_factory=StrictFailurePolicy)
    evaluation_policy: GoalEvaluationPolicy = Field(
        default_factory=ManualGoalEvaluation
    )
    manual_result: Optional[Literal["succeeded", "failed"]] = None

    @model_validator(mode="after")
    def validate_dates(self):
        if self.start_date and self.due_date and self.due_date < self.start_date:
            raise ValueError("due_date must not be before start_date")
        return self


class ActivityPayload(ApiModel):
    tracking_mode: Literal["check", "count", "duration"]
    is_countdown: bool = False
    recurrence_rule: RecurrenceRule = Field(default_factory=DailyRule)
    completion_policy: Literal["recurring", "one_and_done"] = "recurring"
    target_value: Decimal = Field(default=Decimal("1"), ge=0)
    target_unit: Optional[str] = Field(default=None, max_length=50)
    target_cycles: Optional[int] = Field(default=None, gt=0, le=2_147_483_647)
    failure_policy: FailurePolicy = Field(default_factory=StrictFailurePolicy)
    preferred_local_time: Optional[time] = None
    timezone: str = Field(default="UTC", min_length=1, max_length=64)
    origin_assignment_id: Optional[UUID] = None

    @field_validator("timezone")
    @classmethod
    def validate_timezone(cls, value: str) -> str:
        return require_iana_timezone(value)

    @model_validator(mode="after")
    def validate_task_and_target(self):
        is_once = self.recurrence_rule.type == "once"
        if self.completion_policy == "one_and_done" and not is_once:
            raise ValueError("one_and_done activities must use an once recurrence")
        if is_once and self.completion_policy != "one_and_done":
            raise ValueError("once recurrence must use one_and_done completion policy")
        if self.tracking_mode in {"count", "duration"} and self.target_value <= 0:
            raise ValueError("count and duration targets must be greater than zero")
        if self.is_countdown and self.tracking_mode not in {"count", "duration"}:
            raise ValueError(
                "countdown mode is only valid for count and duration activities"
            )
        if (
            self.tracking_mode == "count"
            and self.target_value != self.target_value.to_integral_value()
        ):
            raise ValueError(
                "count targets must be whole numbers for Android compatibility"
            )
        if self.tracking_mode == "count" and self.target_value > 2_147_483_647:
            raise ValueError("count targets must fit the Android integer range")
        if self.tracking_mode == "duration":
            if self.target_unit not in (None, "second"):
                raise ValueError("duration targets must use seconds")
            if (
                self.target_value != self.target_value.to_integral_value()
                or self.target_value % 60 != 0
            ):
                raise ValueError(
                    "duration targets must be whole minutes expressed in seconds"
                )
            if self.target_value > 2_147_483_640:
                raise ValueError("duration targets must fit the Android integer range")
            self.target_unit = "second"
        return self


class PlanNodePayload(ApiModel):
    node_kind: Literal["goal", "activity"]
    title: str = Field(min_length=1, max_length=100)
    description: str = Field(default="", max_length=1000)
    icon: str = Field(default="health", max_length=100)
    color_hex: str = Field(default="#2196F3", pattern=r"^#[0-9A-Fa-f]{6,8}$")
    status: Literal["active", "paused", "completed", "failed", "archived"] = "active"
    visibility: Literal["private"] = "private"
    sort_order: int = 0
    created_at: Optional[datetime] = None
    parent_uuid: Optional[UUID] = None
    goal: Optional[GoalPayload] = None
    activity: Optional[ActivityPayload] = None

    @model_validator(mode="after")
    def validate_kind_details(self):
        if self.node_kind == "goal":
            if self.parent_uuid is not None:
                raise ValueError("goal nodes must be top-level")
            if self.goal is None or self.activity is not None:
                raise ValueError("goal nodes require goal details only")
            expected_status = {
                "succeeded": "completed",
                "failed": "failed",
            }.get(self.goal.manual_result)
            if expected_status is not None and self.status != expected_status:
                raise ValueError("goal status must match its manual result")
            if expected_status is None and self.status not in {"active", "archived"}:
                raise ValueError(
                    "goal statuses completed and failed require a manual result"
                )
        else:
            if self.activity is None or self.goal is not None:
                raise ValueError("activity nodes require activity details only")
            if self.status not in {"active", "archived"}:
                raise ValueError(
                    "Android activities only support active or archived status"
                )
        return self

    @field_validator("icon")
    @classmethod
    def validate_icon(cls, value: str) -> str:
        return normalize_android_icon(value)

    @field_validator("created_at")
    @classmethod
    def normalize_created_at(cls, value: Optional[datetime]) -> Optional[datetime]:
        return require_aware_utc(value)


class ActivityEventPayload(ApiModel):
    activity_uuid: UUID
    event_type: Literal[
        "check_in", "count_delta", "count_snapshot", "duration_session", "revert"
    ]
    value: Optional[Decimal] = None
    duration_seconds: Optional[int] = Field(default=None, ge=0, le=2_147_483_647)
    duration_milliseconds: Optional[int] = Field(default=None, ge=0, le=86_400_000)
    started_at: Optional[datetime] = None
    ended_at: Optional[datetime] = None
    occurred_at: datetime
    local_date: date
    timezone: str = Field(min_length=1, max_length=64)
    note: str = Field(default="", max_length=1000)
    source_type: Literal[
        "app", "widget", "api", "smart_device", "automation", "import"
    ] = "app"
    source_device_id: Optional[UUID] = None
    external_event_id: Optional[str] = Field(default=None, max_length=200)
    reverts_event_uuid: Optional[UUID] = None
    metadata: dict[str, Any] = Field(default_factory=dict)

    @field_validator("started_at", "ended_at", "occurred_at")
    @classmethod
    def normalize_timestamps(cls, value: Optional[datetime]) -> Optional[datetime]:
        return require_aware_utc(value)

    @field_validator("timezone")
    @classmethod
    def validate_timezone(cls, value: str) -> str:
        return require_iana_timezone(value)

    @model_validator(mode="after")
    def validate_event_shape(self):
        if self.event_type == "check_in" and self.value not in (None, Decimal("1")):
            raise ValueError("check_in value must be omitted or 1")
        if self.event_type in {"count_delta", "count_snapshot"} and self.value is None:
            raise ValueError("count events require value")
        if self.event_type == "duration_session":
            if (
                self.duration_seconds is None
                or self.started_at is None
                or self.ended_at is None
            ):
                raise ValueError(
                    "duration_session requires duration_seconds, started_at and ended_at"
                )
            if self.ended_at < self.started_at:
                raise ValueError("ended_at must not be before started_at")
            if self.duration_seconds > 86_400:
                raise ValueError("duration_session must not exceed 24 hours")
            if self.duration_milliseconds is None:
                self.duration_milliseconds = self.duration_seconds * 1000
            if self.duration_milliseconds // 1000 != self.duration_seconds:
                raise ValueError(
                    "duration milliseconds must agree with duration seconds"
                )
            if self.duration_milliseconds > int(
                (self.ended_at - self.started_at).total_seconds() * 1000
            ):
                raise ValueError(
                    "active duration must not exceed the wall-clock interval"
                )
        if self.event_type == "revert" and self.reverts_event_uuid is None:
            raise ValueError("revert events require reverts_event_uuid")
        if self.event_type != "revert" and self.reverts_event_uuid is not None:
            raise ValueError("reverts_event_uuid is only valid for revert events")
        if (
            self.source_type in {"smart_device", "automation"}
            and not self.external_event_id
        ):
            raise ValueError("automated sources require external_event_id")
        date_basis = (
            self.started_at
            if self.event_type == "duration_session"
            else self.occurred_at
        )
        if local_date_at(date_basis, self.timezone) != self.local_date:
            raise ValueError(
                "local_date does not match the event timestamp and timezone"
            )
        return self


class MetricPayload(ApiModel):
    name: str = Field(min_length=1, max_length=100)
    description: str = Field(default="", max_length=1000)
    unit: str = Field(min_length=1, max_length=50)
    decimal_places: int = Field(default=0, ge=0, le=6)
    aggregation_type: Literal["average", "sum", "by_time"] = "average"
    target_direction: Optional[Literal["increase", "decrease", "range"]] = None
    target_value: Optional[Decimal] = None
    target_value_upper: Optional[Decimal] = None
    icon: str = Field(default="health", max_length=100)
    color_hex: str = Field(default="#2196F3", pattern=r"^#[0-9A-Fa-f]{6,8}$")
    status: Literal["active", "archived"] = "active"

    @model_validator(mode="after")
    def validate_range(self):
        if self.target_direction == "range":
            if self.target_value is None or self.target_value_upper is None:
                raise ValueError("range targets require lower and upper values")
            if self.target_value_upper < self.target_value:
                raise ValueError(
                    "target_value_upper must not be less than target_value"
                )
        return self

    @field_validator("icon")
    @classmethod
    def validate_icon(cls, value: str) -> str:
        return normalize_android_icon(value)


class MetricObservationPayload(ApiModel):
    metric_uuid: UUID
    value: Decimal
    unit: str = Field(min_length=1, max_length=50)
    occurred_at: datetime
    local_date: date
    timezone: str = Field(min_length=1, max_length=64)
    note: str = Field(default="", max_length=1000)
    source_type: Literal[
        "app", "widget", "api", "smart_device", "automation", "import"
    ] = "app"
    source_device_id: Optional[UUID] = None
    external_event_id: Optional[str] = Field(default=None, max_length=200)
    metadata: dict[str, Any] = Field(default_factory=dict)

    @field_validator("occurred_at")
    @classmethod
    def normalize_timestamp(cls, value: datetime) -> datetime:
        return require_aware_utc(value)

    @field_validator("timezone")
    @classmethod
    def validate_timezone(cls, value: str) -> str:
        return require_iana_timezone(value)

    @model_validator(mode="after")
    def validate_local_date(self):
        if local_date_at(self.occurred_at, self.timezone) != self.local_date:
            raise ValueError("local_date does not match occurred_at and timezone")
        return self


class ActivityMetricLinkPayload(ApiModel):
    activity_uuid: UUID
    metric_uuid: UUID
    coefficient: Decimal = Decimal("1")
    show_in_activity_detail: bool = True
    prompt_on_complete: bool = False
    is_active: bool = True


class DeviceRegisterRequest(ApiModel):
    installation_id: str = Field(min_length=8, max_length=100)
    protocol_version: int = Field(default=3, ge=1)
    platform: Literal["android", "ios", "desktop", "hardware", "service"]
    device_class: Literal["interactive", "hardware", "automation"] = "interactive"
    app_version: Optional[str] = Field(default=None, max_length=50)
    display_name: Optional[str] = Field(default=None, max_length=100)

    @model_validator(mode="after")
    def validate_device_identity(self):
        if self.device_class == "interactive" and self.platform in {
            "hardware",
            "service",
        }:
            raise ValueError("interactive devices must use an app platform")
        if self.device_class != "interactive" and self.platform in {"android", "ios"}:
            raise ValueError(
                "hardware and automation devices must use hardware, service or desktop"
            )
        return self


class DeviceResponse(ApiModel):
    device_id: UUID
    installation_id: str
    platform: str
    device_class: str
    app_version: Optional[str] = None
    display_name: Optional[str] = None
    is_primary_editor: bool = False
    structural_edit_enabled: bool = False
    capability_revision: int = 1
    capabilities: list[str] = Field(default_factory=list)
    last_seen_at: datetime


class DeviceEditingUpdate(ApiModel):
    structural_edit_enabled: bool


class ServerIdentityResponse(ApiModel):
    server_instance_id: UUID
    sync_epoch: UUID
    protocol_version: int
    capabilities: list[str]
    server_time: datetime


class TimerSessionResponse(ApiModel):
    session_id: UUID
    activity_uuid: UUID
    state: Literal["running", "paused", "completed", "cancelled"]
    controller_device_id: UUID
    control_generation: int
    revision: int
    next_command_sequence: int
    started_at: datetime
    state_changed_at: datetime
    ended_at: Optional[datetime] = None
    timezone: str
    is_countdown: bool
    target_seconds: int
    max_duration_seconds: int
    active_elapsed_ms: int
    last_heartbeat_at: Optional[datetime] = None
    completed_event_id: Optional[UUID] = None


class TimerCommandRequest(ApiModel):
    command_id: UUID
    session_id: UUID
    sequence: int = Field(ge=1)
    command_type: Literal["start", "pause", "resume", "stop", "cancel", "takeover"]
    occurred_at: datetime
    expected_control_generation: int = Field(default=0, ge=0)
    expected_revision: Optional[int] = Field(default=None, ge=1)
    activity_uuid: Optional[UUID] = None
    timezone: Optional[str] = Field(default=None, min_length=1, max_length=64)
    active_elapsed_ms: Optional[int] = Field(default=None, ge=0, le=86_400_000)

    @field_validator("occurred_at")
    @classmethod
    def normalize_occurred_at(cls, value: datetime) -> datetime:
        return require_aware_utc(value)

    @field_validator("timezone")
    @classmethod
    def validate_optional_timezone(cls, value: Optional[str]) -> Optional[str]:
        return require_iana_timezone(value) if value is not None else None

    @model_validator(mode="after")
    def validate_command_shape(self):
        if self.command_type == "start":
            if (
                self.sequence != 1
                or self.activity_uuid is None
                or self.timezone is None
            ):
                raise ValueError(
                    "start requires sequence 1, activity_uuid and timezone"
                )
            if (
                self.expected_control_generation != 0
                or self.expected_revision is not None
            ):
                raise ValueError(
                    "start must not expect an existing generation or revision"
                )
        elif self.activity_uuid is not None or self.timezone is not None:
            raise ValueError("activity_uuid and timezone are only valid for start")
        if (
            self.command_type not in {"pause", "stop", "cancel"}
            and self.active_elapsed_ms is not None
        ):
            raise ValueError(
                "active_elapsed_ms is only valid for pause, stop or cancel"
            )
        return self


class TimerCommandBatchRequest(ApiModel):
    device_id: UUID
    commands: list[TimerCommandRequest] = Field(min_length=1, max_length=100)


class TimerCommandResult(ApiModel):
    command_id: UUID
    session_id: UUID
    status: Literal["applied", "already_applied", "conflict", "rejected"]
    error_code: Optional[str] = None
    message: Optional[str] = None
    session: Optional[TimerSessionResponse] = None


class TimerCommandBatchResponse(ApiModel):
    results: list[TimerCommandResult]
    server_time: datetime


class ActiveTimerResponse(ApiModel):
    session: Optional[TimerSessionResponse] = None
    server_time: datetime


class TimerHeartbeatRequest(ApiModel):
    device_id: UUID
    control_generation: int = Field(ge=1)


class TimerHeartbeatResponse(ApiModel):
    accepted: bool
    session: TimerSessionResponse
    server_time: datetime


class SyncOperationRequest(ApiModel):
    operation_id: UUID
    entity_type: Literal[
        "plan_node",
        "activity_event",
        "metric",
        "metric_observation",
        "activity_metric_link",
    ]
    entity_uuid: UUID
    action: Literal["upsert", "delete"]
    base_revision: Optional[int] = Field(default=None, ge=0)
    payload: dict[str, Any] = Field(default_factory=dict)


class SyncPushRequest(ApiModel):
    device_id: UUID
    operations: list[SyncOperationRequest] = Field(min_length=1, max_length=100)


class SyncOperationResult(ApiModel):
    operation_id: UUID
    entity_type: str
    entity_uuid: UUID
    status: Literal["applied", "already_applied", "conflict", "rejected"]
    revision: Optional[int] = None
    error_code: Optional[str] = None
    message: Optional[str] = None
    entity: Optional[dict[str, Any]] = None
    base_entity: Optional[dict[str, Any]] = None
    local_entity: Optional[dict[str, Any]] = None
    conflicting_fields: list[str] = Field(default_factory=list)
    conflict_kind: Optional[str] = None


class SyncPushResponse(ApiModel):
    results: list[SyncOperationResult]


class SyncChangeResponse(ApiModel):
    sequence: int
    entity_type: str
    entity_uuid: UUID
    operation: Literal["upsert", "delete"]
    revision: int
    payload: dict[str, Any]
    changed_at: datetime
    origin_device_id: Optional[UUID] = None


class SyncPullResponse(ApiModel):
    changes: list[SyncChangeResponse]
    next_cursor: int
    has_more: bool
    server_time: datetime


class SyncBootstrapResponse(ApiModel):
    changes: list[SyncChangeResponse]
    next_cursor: int
    server_time: datetime


__all__ = [name for name in globals() if not name.startswith("_")]
