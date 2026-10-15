"""Incremental sync reads and full recovery within the caller-owned transaction."""

from __future__ import annotations

from typing import Any, Literal, Optional, overload

from sqlalchemy import func
from sqlalchemy.engine import Result
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import col, select

from src.auth.models import User
from src.v2.device_service import require_device
from src.v2.encoding import parse_json
from src.v2.entity_snapshots import (
    serialize_activity_event,
    serialize_day_allocation,
    serialize_link,
    serialize_metric_snapshot,
    serialize_observation,
    serialize_plan_node,
)
from src.v2.errors import DomainError
from src.v2.invariants import require_internal
from src.v2.models import (
    ActivityEvent,
    ActivityMetricLinkV2,
    ClientDevice,
    DurationDayAllocation,
    MetricObservation,
    PlanNode,
    SyncChange,
    SyncCursor,
    TrackedMetric,
    utc_now,
)
from src.v2.schemas import SyncBootstrapResponse, SyncChangeResponse, SyncPullResponse
from src.v2.next_sync_contract import NextSyncBootstrapResponse, NextSyncPullResponse
from src.v2.one_time_recovery import read_one_time_checkpoints
from src.v2.count_recovery import require_count_history
from src.v2.challenge_recovery import require_roundless_view, require_challenge_history


async def _bootstrap_day_allocations(
    session: AsyncSession, owner: int
) -> dict[int, list[dict[str, Any]]]:
    """Use the same live owner/parent selection and transaction as bootstrap facts."""
    result = await session.execute(
        select(DurationDayAllocation)
        .join(
            ActivityEvent,
            col(DurationDayAllocation.activity_event_id) == col(ActivityEvent.id),
        )
        .join(PlanNode, col(ActivityEvent.activity_node_id) == col(PlanNode.id))
        .where(
            col(ActivityEvent.owner_user_id) == owner,
            col(ActivityEvent.deleted_at).is_(None),
            col(ActivityEvent.event_type) == "duration_session",
            col(PlanNode.owner_user_id) == owner,
            col(PlanNode.deleted_at).is_(None),
        )
        .order_by(
            col(DurationDayAllocation.activity_event_id),
            col(DurationDayAllocation.local_date),
        )
    )
    grouped: dict[int, list[dict[str, Any]]] = {}
    for allocation in result.scalars():
        grouped.setdefault(allocation.activity_event_id, []).append(
            serialize_day_allocation(allocation)
        )
    return grouped


def _change_response(
    change: SyncChange,
    origin_device_public_id: Optional[str],
) -> SyncChangeResponse:
    return SyncChangeResponse.model_validate(
        {
            "sequence": change.sequence,
            "entity_type": change.entity_type,
            "entity_uuid": change.entity_uuid,
            "operation": change.operation,
            "revision": change.revision,
            "payload": parse_json(change.payload_json),
            "changed_at": change.changed_at,
            "origin_device_id": origin_device_public_id,
        }
    )


@overload
async def pull_changes(
    user: User,
    device_public_id: str,
    cursor_value: int,
    limit: int,
    session: AsyncSession,
    *,
    next_protocol: Literal[False] = False,
    round_profile: bool = False,
) -> SyncPullResponse: ...


@overload
async def pull_changes(
    user: User,
    device_public_id: str,
    cursor_value: int,
    limit: int,
    session: AsyncSession,
    *,
    next_protocol: Literal[True],
    round_profile: bool = False,
) -> NextSyncPullResponse: ...


async def pull_changes(
    user: User,
    device_public_id: str,
    cursor_value: int,
    limit: int,
    session: AsyncSession,
    *,
    next_protocol: bool = False,
    round_profile: bool = False,
) -> SyncPullResponse:
    device = await require_device(
        require_internal(user.id, "User.id"), device_public_id, session
    )
    if round_profile and not next_protocol:
        raise DomainError(
            "CLIENT_UPGRADE_REQUIRED", "Challenge profile requires protocol 5"
        )
    if not round_profile:
        await require_roundless_view(session, require_internal(user.id, "User.id"))
    if next_protocol:
        await require_challenge_history(session, require_internal(user.id, "User.id"))
    max_result: Result[tuple[int]] = await session.execute(
        select(func.coalesce(func.max(col(SyncChange.sequence)), 0)).where(
            col(SyncChange.recipient_user_id) == user.id
        )
    )
    max_sequence = int(max_result.scalar_one())
    if cursor_value > max_sequence:
        raise DomainError("INVALID_CURSOR", "Cursor is ahead of the server change log")

    rows_result = await session.execute(
        select(SyncChange, col(ClientDevice.public_id))
        .outerjoin(
            ClientDevice, col(SyncChange.origin_device_id) == col(ClientDevice.id)
        )
        .where(
            col(SyncChange.recipient_user_id) == user.id,
            col(SyncChange.sequence) > cursor_value,
        )
        .order_by(col(SyncChange.sequence))
        .limit(limit + 1)
    )
    rows = list(rows_result.all())
    has_more = len(rows) > limit
    page = rows[:limit]
    changes = [_change_response(row[0], row[1]) for row in page]
    next_cursor = changes[-1].sequence if changes else cursor_value

    cursor_result = await session.execute(
        select(SyncCursor).where(
            SyncCursor.user_id == user.id,
            SyncCursor.device_id == device.id,
        )
    )
    cursor_row = cursor_result.scalar_one_or_none()
    if cursor_row is None:
        cursor_row = SyncCursor(user_id=user.id, device_id=device.id)
        session.add(cursor_row)
    # Never move a server-side observation cursor backwards.
    cursor_row.last_pulled_sequence = max(cursor_row.last_pulled_sequence, next_cursor)
    cursor_row.last_pull_at = utc_now()
    response_type = NextSyncPullResponse if next_protocol else SyncPullResponse
    return response_type(
        changes=changes,
        next_cursor=next_cursor,
        has_more=has_more,
        server_time=utc_now(),
    )


@overload
async def bootstrap(
    user: User,
    device_public_id: str,
    session: AsyncSession,
    *,
    next_protocol: Literal[False] = False,
    round_profile: bool = False,
) -> SyncBootstrapResponse: ...


@overload
async def bootstrap(
    user: User,
    device_public_id: str,
    session: AsyncSession,
    *,
    next_protocol: Literal[True],
    round_profile: bool = False,
) -> NextSyncBootstrapResponse: ...


async def bootstrap(
    user: User,
    device_public_id: str,
    session: AsyncSession,
    *,
    next_protocol: bool = False,
    round_profile: bool = False,
) -> SyncBootstrapResponse:
    device = await require_device(
        require_internal(user.id, "User.id"), device_public_id, session
    )
    response = await read_bootstrap_snapshot(
        user, session, next_protocol=next_protocol, round_profile=round_profile
    )
    cursor_result = await session.execute(
        select(SyncCursor).where(
            SyncCursor.user_id == user.id,
            SyncCursor.device_id == device.id,
        )
    )
    cursor = cursor_result.scalar_one_or_none()
    if cursor is None:
        cursor = SyncCursor(user_id=user.id, device_id=device.id)
        session.add(cursor)
    cursor.last_pulled_sequence = response.next_cursor
    cursor.last_pull_at = utc_now()
    return response


async def read_bootstrap_snapshot(
    user: User,
    session: AsyncSession,
    *,
    next_protocol: bool = False,
    round_profile: bool = False,
) -> SyncBootstrapResponse:
    """Pure projection within the caller's authenticated transaction.

    Unlike bootstrap this does not register/touch a device or advance a cursor.
    Admission belongs to the caller; this helper is not an HTTP authority gate.
    """
    if next_protocol:
        await require_count_history(session, require_internal(user.id, "User.id"))
        await require_challenge_history(session, require_internal(user.id, "User.id"))
    if round_profile and not next_protocol:
        raise DomainError(
            "CLIENT_UPGRADE_REQUIRED", "Challenge profile requires protocol 5"
        )
    if not round_profile:
        await require_roundless_view(session, require_internal(user.id, "User.id"))
    max_result: Result[tuple[int]] = await session.execute(
        select(func.coalesce(func.max(col(SyncChange.sequence)), 0)).where(
            col(SyncChange.recipient_user_id) == user.id
        )
    )
    high_watermark = int(max_result.scalar_one())
    synthetic: list[SyncChangeResponse] = []

    nodes_result = await session.execute(
        select(PlanNode)
        .where(
            col(PlanNode.owner_user_id) == user.id, col(PlanNode.deleted_at).is_(None)
        )
        .order_by(col(PlanNode.node_kind), col(PlanNode.id))
    )
    nodes = list(nodes_result.scalars().all())
    # Goals must arrive before child activities.
    nodes.sort(key=lambda node: (0 if node.node_kind == "goal" else 1, node.id))
    for node in nodes:
        synthetic.append(
            SyncChangeResponse.model_validate(
                {
                    "sequence": 0,
                    "entity_type": "plan_node",
                    "entity_uuid": node.public_id,
                    "operation": "upsert",
                    "revision": node.revision,
                    "payload": await serialize_plan_node(
                        session, node, next_protocol=next_protocol
                    ),
                    "changed_at": node.updated_at,
                    "origin_device_id": None,
                }
            )
        )

    events_result = await session.execute(
        select(ActivityEvent, col(PlanNode.public_id))
        .join(PlanNode, col(ActivityEvent.activity_node_id) == col(PlanNode.id))
        .where(
            col(ActivityEvent.owner_user_id) == user.id,
            col(ActivityEvent.deleted_at).is_(None),
            col(PlanNode.owner_user_id) == user.id,
            col(PlanNode.deleted_at).is_(None),
        )
        .order_by(col(ActivityEvent.id))
    )
    events = events_result.all()
    # Bootstrap already reads the complete live account snapshot. One scoped join
    # replaces one allocation query per timer, without an unbounded IN parameter list.
    allocations = (
        await _bootstrap_day_allocations(session, require_internal(user.id, "User.id"))
        if any(event.event_type == "duration_session" for event, _ in events)
        else {}
    )
    for event, activity_uuid in events:
        revert = (
            await session.get(ActivityEvent, event.reverts_event_id)
            if event.reverts_event_id
            else None
        )
        payload = serialize_activity_event(
            event, activity_uuid, revert.public_id if revert else None
        )
        if event.event_type == "duration_session":
            payload["day_allocations"] = allocations.get(
                require_internal(event.id, "ActivityEvent.id"), []
            )
        synthetic.append(
            SyncChangeResponse.model_validate(
                {
                    "sequence": 0,
                    "entity_type": "activity_event",
                    "entity_uuid": event.public_id,
                    "operation": "upsert",
                    "revision": event.revision,
                    "payload": payload,
                    "changed_at": event.updated_at,
                    "origin_device_id": None,
                }
            )
        )

    metrics_result = await session.execute(
        select(TrackedMetric)
        .where(
            col(TrackedMetric.owner_user_id) == user.id,
            col(TrackedMetric.deleted_at).is_(None),
        )
        .order_by(col(TrackedMetric.id))
    )
    metrics = list(metrics_result.scalars().all())
    for metric in metrics:
        synthetic.append(
            SyncChangeResponse.model_validate(
                {
                    "sequence": 0,
                    "entity_type": "metric",
                    "entity_uuid": metric.public_id,
                    "operation": "upsert",
                    "revision": metric.revision,
                    "payload": await serialize_metric_snapshot(
                        session, metric, next_protocol=next_protocol
                    ),
                    "changed_at": metric.updated_at,
                    "origin_device_id": None,
                }
            )
        )

    observations_result = await session.execute(
        select(MetricObservation, col(TrackedMetric.public_id))
        .join(TrackedMetric, col(MetricObservation.metric_id) == col(TrackedMetric.id))
        .where(
            col(MetricObservation.owner_user_id) == user.id,
            col(MetricObservation.deleted_at).is_(None),
            col(TrackedMetric.owner_user_id) == user.id,
            col(TrackedMetric.deleted_at).is_(None),
        )
        .order_by(col(MetricObservation.id))
    )
    for observation, metric_uuid in observations_result.all():
        synthetic.append(
            SyncChangeResponse.model_validate(
                {
                    "sequence": 0,
                    "entity_type": "metric_observation",
                    "entity_uuid": observation.public_id,
                    "operation": "upsert",
                    "revision": observation.revision,
                    "payload": serialize_observation(observation, metric_uuid),
                    "changed_at": observation.updated_at,
                    "origin_device_id": None,
                }
            )
        )

    links_result = await session.execute(
        select(
            ActivityMetricLinkV2, col(PlanNode.public_id), col(TrackedMetric.public_id)
        )
        .join(PlanNode, col(ActivityMetricLinkV2.activity_node_id) == col(PlanNode.id))
        .join(
            TrackedMetric, col(ActivityMetricLinkV2.metric_id) == col(TrackedMetric.id)
        )
        .where(
            col(ActivityMetricLinkV2.owner_user_id) == user.id,
            col(ActivityMetricLinkV2.deleted_at).is_(None),
            col(PlanNode.owner_user_id) == user.id,
            col(PlanNode.deleted_at).is_(None),
            col(TrackedMetric.owner_user_id) == user.id,
            col(TrackedMetric.deleted_at).is_(None),
        )
        .order_by(col(ActivityMetricLinkV2.id))
    )
    for link, activity_uuid, metric_uuid in links_result.all():
        synthetic.append(
            SyncChangeResponse.model_validate(
                {
                    "sequence": 0,
                    "entity_type": "activity_metric_link",
                    "entity_uuid": link.public_id,
                    "operation": "upsert",
                    "revision": link.revision,
                    "payload": serialize_link(link, activity_uuid, metric_uuid),
                    "changed_at": link.updated_at,
                    "origin_device_id": None,
                }
            )
        )

    if next_protocol:
        return NextSyncBootstrapResponse(
            changes=synthetic,
            next_cursor=high_watermark,
            server_time=utc_now(),
            one_time_checkpoints=await read_one_time_checkpoints(
                session, require_internal(user.id, "User.id")
            ),
        )
    return SyncBootstrapResponse(
        changes=synthetic,
        next_cursor=high_watermark,
        server_time=utc_now(),
    )


__all__ = ["bootstrap", "pull_changes", "read_bootstrap_snapshot"]
