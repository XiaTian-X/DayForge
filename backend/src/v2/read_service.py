"""Incremental sync reads and full recovery within the caller-owned transaction."""

from __future__ import annotations

from typing import Optional

from sqlalchemy import func
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import select

from src.auth.models import User
from src.v2.device_service import require_device
from src.v2.encoding import parse_json
from src.v2.entity_snapshots import (
    serialize_activity_event_with_allocations,
    serialize_link,
    serialize_metric,
    serialize_observation,
    serialize_plan_node,
)
from src.v2.errors import DomainError
from src.v2.models import (
    ActivityEvent,
    ActivityMetricLinkV2,
    ClientDevice,
    MetricObservation,
    PlanNode,
    SyncChange,
    SyncCursor,
    TrackedMetric,
    utc_now,
)
from src.v2.schemas import SyncBootstrapResponse, SyncChangeResponse, SyncPullResponse


def _change_response(
    change: SyncChange,
    origin_device_public_id: Optional[str],
) -> SyncChangeResponse:
    return SyncChangeResponse(
        sequence=change.sequence,
        entity_type=change.entity_type,
        entity_uuid=change.entity_uuid,
        operation=change.operation,
        revision=change.revision,
        payload=parse_json(change.payload_json),
        changed_at=change.changed_at,
        origin_device_id=origin_device_public_id,
    )


async def pull_changes(
    user: User,
    device_public_id: str,
    cursor_value: int,
    limit: int,
    session: AsyncSession,
) -> SyncPullResponse:
    device = await require_device(user.id, device_public_id, session)
    max_result = await session.execute(
        select(func.coalesce(func.max(SyncChange.sequence), 0)).where(
            SyncChange.recipient_user_id == user.id
        )
    )
    max_sequence = int(max_result.scalar_one())
    if cursor_value > max_sequence:
        raise DomainError("INVALID_CURSOR", "Cursor is ahead of the server change log")

    rows_result = await session.execute(
        select(SyncChange, ClientDevice.public_id)
        .outerjoin(ClientDevice, SyncChange.origin_device_id == ClientDevice.id)
        .where(
            SyncChange.recipient_user_id == user.id,
            SyncChange.sequence > cursor_value,
        )
        .order_by(SyncChange.sequence)
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
    return SyncPullResponse(
        changes=changes,
        next_cursor=next_cursor,
        has_more=has_more,
        server_time=utc_now(),
    )


async def bootstrap(
    user: User,
    device_public_id: str,
    session: AsyncSession,
) -> SyncBootstrapResponse:
    device = await require_device(user.id, device_public_id, session)
    max_result = await session.execute(
        select(func.coalesce(func.max(SyncChange.sequence), 0)).where(
            SyncChange.recipient_user_id == user.id
        )
    )
    high_watermark = int(max_result.scalar_one())
    synthetic: list[SyncChangeResponse] = []

    nodes_result = await session.execute(
        select(PlanNode)
        .where(PlanNode.owner_user_id == user.id, PlanNode.deleted_at.is_(None))
        .order_by(PlanNode.node_kind, PlanNode.id)
    )
    nodes = list(nodes_result.scalars().all())
    # Goals must arrive before child activities.
    nodes.sort(key=lambda node: (0 if node.node_kind == "goal" else 1, node.id))
    for node in nodes:
        synthetic.append(
            SyncChangeResponse(
                sequence=0,
                entity_type="plan_node",
                entity_uuid=node.public_id,
                operation="upsert",
                revision=node.revision,
                payload=await serialize_plan_node(session, node),
                changed_at=node.updated_at,
                origin_device_id=None,
            )
        )

    events_result = await session.execute(
        select(ActivityEvent, PlanNode.public_id)
        .join(PlanNode, ActivityEvent.activity_node_id == PlanNode.id)
        .where(
            ActivityEvent.owner_user_id == user.id,
            ActivityEvent.deleted_at.is_(None),
            PlanNode.owner_user_id == user.id,
            PlanNode.deleted_at.is_(None),
        )
        .order_by(ActivityEvent.id)
    )
    for event, activity_uuid in events_result.all():
        revert = await session.get(ActivityEvent, event.reverts_event_id) if event.reverts_event_id else None
        synthetic.append(
            SyncChangeResponse(
                sequence=0,
                entity_type="activity_event",
                entity_uuid=event.public_id,
                operation="upsert",
                revision=event.revision,
                payload=await serialize_activity_event_with_allocations(
                    session, event, activity_uuid, revert.public_id if revert else None
                ),
                changed_at=event.updated_at,
                origin_device_id=None,
            )
        )

    metrics_result = await session.execute(
        select(TrackedMetric)
        .where(TrackedMetric.owner_user_id == user.id, TrackedMetric.deleted_at.is_(None))
        .order_by(TrackedMetric.id)
    )
    metrics = list(metrics_result.scalars().all())
    for metric in metrics:
        synthetic.append(
            SyncChangeResponse(
                sequence=0,
                entity_type="metric",
                entity_uuid=metric.public_id,
                operation="upsert",
                revision=metric.revision,
                payload=serialize_metric(metric),
                changed_at=metric.updated_at,
                origin_device_id=None,
            )
        )

    observations_result = await session.execute(
        select(MetricObservation, TrackedMetric.public_id)
        .join(TrackedMetric, MetricObservation.metric_id == TrackedMetric.id)
        .where(
            MetricObservation.owner_user_id == user.id,
            MetricObservation.deleted_at.is_(None),
            TrackedMetric.owner_user_id == user.id,
            TrackedMetric.deleted_at.is_(None),
        )
        .order_by(MetricObservation.id)
    )
    for observation, metric_uuid in observations_result.all():
        synthetic.append(
            SyncChangeResponse(
                sequence=0,
                entity_type="metric_observation",
                entity_uuid=observation.public_id,
                operation="upsert",
                revision=observation.revision,
                payload=serialize_observation(observation, metric_uuid),
                changed_at=observation.updated_at,
                origin_device_id=None,
            )
        )

    links_result = await session.execute(
        select(ActivityMetricLinkV2, PlanNode.public_id, TrackedMetric.public_id)
        .join(PlanNode, ActivityMetricLinkV2.activity_node_id == PlanNode.id)
        .join(TrackedMetric, ActivityMetricLinkV2.metric_id == TrackedMetric.id)
        .where(
            ActivityMetricLinkV2.owner_user_id == user.id,
            ActivityMetricLinkV2.deleted_at.is_(None),
            PlanNode.owner_user_id == user.id,
            PlanNode.deleted_at.is_(None),
            TrackedMetric.owner_user_id == user.id,
            TrackedMetric.deleted_at.is_(None),
        )
        .order_by(ActivityMetricLinkV2.id)
    )
    for link, activity_uuid, metric_uuid in links_result.all():
        synthetic.append(
            SyncChangeResponse(
                sequence=0,
                entity_type="activity_metric_link",
                entity_uuid=link.public_id,
                operation="upsert",
                revision=link.revision,
                payload=serialize_link(link, activity_uuid, metric_uuid),
                changed_at=link.updated_at,
                origin_device_id=None,
            )
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
    cursor.last_pulled_sequence = high_watermark
    cursor.last_pull_at = utc_now()
    return SyncBootstrapResponse(
        changes=synthetic,
        next_cursor=high_watermark,
        server_time=utc_now(),
    )


__all__ = ["bootstrap", "pull_changes"]
