"""Portable config-v2 metadata. Import remaps every package identity; no history."""

from datetime import date
from typing import Annotated, Literal

from pydantic import Field, field_validator, model_validator

from src.v2.appearance import (
    AssetIcon,
    IconPack,
    IconReference,
    RoleIcon,
    RoleKey,
    icon_allowed,
)
from src.v2.contract_types import (
    AccentColor,
    ContractModel,
    exact_integer,
    visible_name,
)
from src.v2.theme_contract import ThemeDefinition
from src.v2.time_utils import require_iana_timezone


LocalId = Annotated[str, Field(pattern=r"^[a-z][a-z0-9_-]{0,63}$")]
Day = Annotated[str, Field(pattern=r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$")]


class DatedSchedule(ContractModel):
    start_date: Day | None

    @field_validator("start_date")
    @classmethod
    def valid_day(cls, value):
        if value is not None:
            date.fromisoformat(value)
        return value


class DailySchedule(DatedSchedule):
    type: Literal["daily"]


class WeeklySchedule(DatedSchedule):
    type: Literal["weekly"]
    weekdays: list[Annotated[int, Field(ge=1, le=7)]] = Field(
        min_length=1, max_length=7
    )

    @model_validator(mode="after")
    def unique_days(self):
        if len(set(self.weekdays)) != len(self.weekdays):
            raise ValueError("duplicate weekdays")
        return self


class MonthlySchedule(DatedSchedule):
    type: Literal["monthly"]
    day_of_month: int = Field(ge=1, le=31)


class IntervalSchedule(DatedSchedule):
    type: Literal["interval"]
    start_date: Day
    every_days: int = Field(ge=1, le=3650)


class OnceSchedule(ContractModel):
    type: Literal["once"]
    due_date: Day | None

    @field_validator("due_date")
    @classmethod
    def valid_day(cls, value):
        if value is not None:
            date.fromisoformat(value)
        return value


ConfigSchedule = Annotated[
    DailySchedule | WeeklySchedule | MonthlySchedule | IntervalSchedule | OnceSchedule,
    Field(discriminator="type"),
]


class ConfigAppearance(ContractModel):
    icon: IconReference
    accent_color: AccentColor
    icon_tint: Literal["theme", "object"]


class ConfigActivity(ContractModel):
    tracking_mode: Literal["check", "count", "duration"]
    completion_policy: Literal["recurring", "one_and_done"]
    is_countdown: bool
    target_value: int = Field(ge=0, le=2_147_483_647)
    target_cycles: int | None = Field(ge=1, le=2_147_483_647)
    fail_mode: Literal["strict", "loose"]
    preferred_minute: int | None = Field(ge=0, le=1439)
    timezone: str = Field(min_length=1, max_length=64)
    schedule: ConfigSchedule

    @field_validator("timezone")
    @classmethod
    def valid_timezone(cls, value):
        return require_iana_timezone(value)

    @model_validator(mode="after")
    def valid_mode(self):
        once = self.completion_policy == "one_and_done"
        if once != (self.schedule.type == "once"):
            raise ValueError("completion policy and schedule disagree")
        if self.tracking_mode == "check" and self.is_countdown:
            raise ValueError("check activities cannot count down")
        if self.tracking_mode != "check" and self.target_value <= 0:
            raise ValueError("count and duration targets must be positive")
        if self.tracking_mode == "duration" and (
            self.target_value % 60 != 0 or self.target_value > 2_147_483_640
        ):
            raise ValueError("duration target must be whole minutes in seconds")
        if once and (
            self.tracking_mode != "check"
            or self.target_value != 1
            or self.target_cycles is not None
            or self.fail_mode != "loose"
            or self.preferred_minute is not None
        ):
            raise ValueError("one-time items only support a single unplanned check")
        return self


class ConfigGoal(ContractModel):
    start_date: Day | None
    due_date: Day | None
    target_cycles: int | None = Field(ge=1, le=2_147_483_647)
    fail_mode: Literal["strict", "loose"]

    @model_validator(mode="after")
    def valid_dates(self):
        start = date.fromisoformat(self.start_date) if self.start_date else None
        end = date.fromisoformat(self.due_date) if self.due_date else None
        if start and end and start > end:
            raise ValueError("goal date range is reversed")
        return self


class ConfigNode(ContractModel):
    key: LocalId
    kind: Literal["goal", "activity"]
    name: str = Field(min_length=1, max_length=100)
    description: str = Field(max_length=1000)
    is_active: bool
    parent_key: LocalId | None
    appearance: ConfigAppearance
    goal: ConfigGoal | None
    activity: ConfigActivity | None

    @model_validator(mode="after")
    def valid_kind(self):
        visible_name(self.name, 100)
        if self.kind == "goal":
            if (
                self.parent_key is not None
                or self.goal is None
                or self.activity is not None
            ):
                raise ValueError(
                    "goals must be top-level and contain only goal details"
                )
        elif self.activity is None or self.goal is not None:
            raise ValueError("activities require activity details only")
        return self


class ConfigMetric(ContractModel):
    key: LocalId
    name: str = Field(min_length=1, max_length=100)
    description: str = Field(max_length=1000)
    unit: str = Field(min_length=1, max_length=50)
    is_active: bool
    decimal_places: int = Field(ge=0, le=6)
    aggregation_type: Literal["average", "sum", "by_time"]
    target_direction: Literal["increase", "decrease", "range"] | None
    target_value: float | None = Field(allow_inf_nan=False)
    target_value_upper: float | None = Field(allow_inf_nan=False)
    appearance: ConfigAppearance

    @model_validator(mode="after")
    def valid_metric(self):
        visible_name(self.name, 100)
        visible_name(self.unit, 50)
        if self.target_direction == "range" and (
            self.target_value is None
            or self.target_value_upper is None
            or self.target_value_upper < self.target_value
        ):
            raise ValueError("invalid metric target range")
        return self


class ConfigLink(ContractModel):
    key: LocalId
    activity_key: LocalId
    metric_key: LocalId
    coefficient: float = Field(allow_inf_nan=False)
    show_in_detail: bool
    prompt_on_complete: bool
    is_active: bool


class ConfigBundle(ContractModel):
    format: Literal["dayforge.config"]
    format_version: Literal[2]
    nodes: list[ConfigNode] = Field(max_length=1000)
    metrics: list[ConfigMetric] = Field(max_length=1000)
    links: list[ConfigLink] = Field(max_length=5000)
    icon_pack: IconPack | None
    unresolved_roles: list[RoleKey] = Field(max_length=256)
    themes: list[ThemeDefinition] = Field(max_length=16)

    @field_validator("format_version", mode="before")
    @classmethod
    def exact_version_type(cls, value):
        return exact_integer(value)

    @model_validator(mode="after")
    def valid_graph(self):
        keys = (
            [node.key for node in self.nodes]
            + [metric.key for metric in self.metrics]
            + [link.key for link in self.links]
        )
        if len(set(keys)) != len(keys):
            raise ValueError("all package keys must be unique")
        for items in (self.nodes, self.metrics):
            if len({item.name for item in items}) != len(items):
                raise ValueError("duplicate names")
        nodes = {node.key: node for node in self.nodes}
        metrics = {metric.key for metric in self.metrics}
        for node in self.nodes:
            if node.parent_key is not None:
                parent = nodes.get(node.parent_key)
                if parent is None or parent.kind != "goal":
                    raise ValueError("parent must reference a package goal")
        pairs = {(link.activity_key, link.metric_key) for link in self.links}
        if len(pairs) != len(self.links):
            raise ValueError("duplicate metric links")
        for link in self.links:
            linked_node = nodes.get(link.activity_key)
            if (
                linked_node is None
                or linked_node.kind != "activity"
                or link.metric_key not in metrics
            ):
                raise ValueError("invalid link endpoints")
        assets = (
            {a.asset_id: a for a in self.icon_pack.assets} if self.icon_pack else {}
        )
        roles = self.icon_pack.roles if self.icon_pack else {}
        unresolved = set(self.unresolved_roles)
        if len(unresolved) != len(self.unresolved_roles) or unresolved & roles.keys():
            raise ValueError("ambiguous unresolved role declaration")
        used_roles: set[str] = set()
        appearance_items: list[ConfigNode | ConfigMetric] = [*self.nodes, *self.metrics]
        for item in appearance_items:
            once = (
                isinstance(item, ConfigNode)
                and item.activity is not None
                and item.activity.completion_policy == "one_and_done"
            )
            icon = item.appearance.icon
            if isinstance(icon, RoleIcon):
                used_roles.add(icon.role)
                if icon.role not in roles and icon.role not in unresolved:
                    raise ValueError(
                        "role has no captured mapping or missing-role marker"
                    )
                if not icon_allowed(icon, one_time=once):
                    raise ValueError("icon purpose does not match the object")
            elif isinstance(icon, AssetIcon) and not icon_allowed(
                icon, one_time=once, asset=assets.get(icon.asset_id)
            ):
                raise ValueError("missing or wrong-purpose pinned asset")
        if not unresolved <= used_roles:
            raise ValueError("unused unresolved roles")
        theme_ids = [(theme.theme_id, theme.revision) for theme in self.themes]
        if len(set(theme_ids)) != len(theme_ids):
            raise ValueError("duplicate theme version")
        return self
