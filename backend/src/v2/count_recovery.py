"""Read-only counting proof validation for bootstrap and physical/logical restore."""

from collections.abc import Mapping, Sequence
from datetime import date
from typing import Any

from sqlalchemy import text
from sqlalchemy.engine import Connection
from sqlalchemy.ext.asyncio import AsyncSession

from src.v2.count_policy import CountDayPolicy
from src.v2.encoding import canonical_json, parse_json
from src.v2.errors import DomainError
from src.v2.one_time_recovery import ReadRows


class CountRecoveryError(ValueError):
    pass


def _date(value: Any) -> date:
    if type(value) is date:
        return value
    if type(value) is str:
        parsed = date.fromisoformat(value)
        if parsed.isoformat() == value:
            return parsed
    raise ValueError("noncanonical business date")


def decode_stored_policy(value: Any) -> CountDayPolicy:
    if type(value) is not str or len(value) > 256:
        raise ValueError("invalid original count policy")
    policy = CountDayPolicy.model_validate(parse_json(value))
    if canonical_json(policy.model_dump(mode="json")) != value:
        raise ValueError("noncanonical original count policy")
    return policy


def validate_count_rows(
    nodes: Sequence[Mapping[str, Any]],
    events: Sequence[Mapping[str, Any]],
    days: Sequence[Mapping[str, Any]],
) -> None:
    try:
        parents = {row["id"]: row for row in nodes}
        facts = {row["id"]: row for row in events}
        policies: dict[tuple[int, date], CountDayPolicy] = {}
        if len(parents) != len(nodes) or len(facts) != len(events):
            raise ValueError("duplicate original identity")
        for day in days:
            node = parents[day["activity_node_id"]]
            first = facts[day["first_event_id"]]
            key = (node["id"], _date(day["local_date"]))
            direction = day["is_countdown"]
            if type(direction) is not bool and (
                type(direction) is not int or direction not in (0, 1)
            ):
                raise ValueError("invalid direction storage")
            policy = CountDayPolicy(
                target_value=day["target_value"], is_countdown=bool(direction)
            )
            if (
                key in policies
                or node["node_kind"] != "activity"
                or node["owner_user_id"] != day["owner_user_id"]
                or first["owner_user_id"] != day["owner_user_id"]
                or first["activity_node_id"] != node["id"]
                or _date(first["local_date"]) != key[1]
                or first["event_type"] not in {"count_delta", "count_snapshot"}
                or first["deleted_at"] is not None
                or decode_stored_policy(first["count_policy_json"]) != policy
            ):
                raise ValueError("original day evidence mismatch")
            policies[key] = policy
        for fact in events:
            node = parents[fact["activity_node_id"]]
            if node["owner_user_id"] != fact["owner_user_id"]:
                raise ValueError("fact ownership differs from its parent")
            value = fact["count_policy_json"]
            key = (node["id"], _date(fact["local_date"]))
            if value is None:
                if (
                    fact["event_type"] in {"count_delta", "count_snapshot"}
                    and key in policies
                ):
                    raise ValueError("mixed unknown and captured count day")
                continue  # Legacy history remains unknown, without filling from mutable config.
            if (
                node["owner_user_id"] != fact["owner_user_id"]
                or fact["event_type"] not in {"count_delta", "count_snapshot"}
                or fact["deleted_at"] is not None
                or fact["one_time_expected_version"] is not None
                or decode_stored_policy(value) != policies.get(key)
            ):
                raise ValueError("count fact has no matching original day")
    except (ValueError, KeyError, TypeError) as error:
        raise CountRecoveryError("COUNT_DAY_INVALID") from error


def read_count_history(
    read_rows: ReadRows, *, owner_user_id: int | None = None
) -> None:
    suffix = ""
    parameters: dict[str, int] = {}
    if owner_user_id is not None:
        suffix = " WHERE n.owner_user_id=:owner AND n.deleted_at IS NULL"
        parameters["owner"] = owner_user_id
    nodes = read_rows(
        "SELECT n.id,n.owner_user_id,n.node_kind FROM plan_nodes n" + suffix, parameters
    )
    events = read_rows(
        "SELECT e.* FROM activity_events e JOIN plan_nodes n ON n.id=e.activity_node_id"
        + suffix,
        parameters,
    )
    days = read_rows(
        "SELECT d.* FROM activity_count_days d JOIN plan_nodes n ON n.id=d.activity_node_id"
        + suffix,
        parameters,
    )
    validate_count_rows(nodes, events, days)


def read_connection_count_history(
    connection: Connection, *, owner_user_id: int | None = None
) -> None:
    read_count_history(
        lambda statement, parameters: [
            dict(row)
            for row in connection.execute(text(statement), parameters).mappings()
        ],
        owner_user_id=owner_user_id,
    )


async def require_count_history(session: AsyncSession, owner_user_id: int) -> None:
    try:
        await session.run_sync(
            lambda current: read_connection_count_history(
                current.connection(), owner_user_id=owner_user_id
            )
        )
    except CountRecoveryError as error:
        raise DomainError(
            "COUNT_DAY_INVALID", "Original count day history cannot be recovered safely"
        ) from error
