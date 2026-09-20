"""Activity/metric link mutations within the caller-owned sync transaction."""

from __future__ import annotations

from typing import Any

from pydantic import ValidationError
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import select

from src.v2.change_log import append_change
from src.v2.entity_snapshots import serialize_link
from src.v2.errors import DomainError
from src.v2.metric_mutations import get_metric
from src.v2.models import (
    ActivityMetricLinkV2,
    ClientDevice,
    PlanNode,
    TrackedMetric,
    utc_now,
)
from src.v2.plan_node_mutations import get_plan_node
from src.v2.schemas import ActivityMetricLinkPayload, SyncOperationRequest


async def mutate_link(
    session: AsyncSession,
    user_id: int,
    device: ClientDevice,
    operation: SyncOperationRequest,
) -> tuple[int, dict[str, Any]]:
    result = await session.execute(
        select(ActivityMetricLinkV2).where(
            ActivityMetricLinkV2.owner_user_id == user_id,
            ActivityMetricLinkV2.public_id == str(operation.entity_uuid),
        )
    )
    existing = result.scalar_one_or_none()
    if operation.action == "delete":
        if existing is None:
            raise DomainError("ENTITY_NOT_FOUND", "Activity-metric link was not found")
        activity = await session.get(PlanNode, existing.activity_node_id)
        metric = await session.get(TrackedMetric, existing.metric_id)
        if existing.deleted_at is not None:
            return existing.revision, serialize_link(
                existing, activity.public_id, metric.public_id
            )
        if operation.base_revision != existing.revision:
            raise DomainError(
                "REVISION_CONFLICT",
                "Link changed on another client",
                conflict=True,
                revision=existing.revision,
            )
        existing.revision += 1
        existing.updated_at = utc_now()
        existing.deleted_at = existing.updated_at
        entity = serialize_link(existing, activity.public_id, metric.public_id)
        await append_change(
            session,
            user_id=user_id,
            device_id=device.id,
            operation_id=str(operation.operation_id),
            entity_type="activity_metric_link",
            entity_uuid=existing.public_id,
            operation="delete",
            revision=existing.revision,
            payload=entity,
        )
        return existing.revision, entity

    try:
        payload = ActivityMetricLinkPayload.model_validate(operation.payload)
    except ValidationError as exc:
        raise DomainError("INVALID_PAYLOAD", str(exc)) from exc
    activity = await get_plan_node(
        session, user_id, str(payload.activity_uuid), include_deleted=False
    )
    if activity is None or activity.node_kind != "activity":
        raise DomainError("ACTIVITY_NOT_FOUND", "Activity was not found")
    metric = await get_metric(
        session, user_id, str(payload.metric_uuid), include_deleted=False
    )
    if metric is None:
        raise DomainError("METRIC_NOT_FOUND", "Metric was not found")

    if existing is None:
        if operation.base_revision not in (None, 0):
            raise DomainError(
                "ENTITY_NOT_FOUND", "Cannot update a link that does not exist"
            )
        link = ActivityMetricLinkV2(
            public_id=str(operation.entity_uuid),
            owner_user_id=user_id,
            activity_node_id=activity.id,
            metric_id=metric.id,
            coefficient=payload.coefficient,
            show_in_activity_detail=payload.show_in_activity_detail,
            prompt_on_complete=payload.prompt_on_complete,
            is_active=payload.is_active,
        )
        session.add(link)
        try:
            await session.flush()
        except IntegrityError as exc:
            raise DomainError(
                "LINK_ALREADY_EXISTS", "This activity and metric are already linked"
            ) from exc
    else:
        if existing.deleted_at is not None:
            raise DomainError(
                "ENTITY_DELETED",
                "Deleted links cannot be implicitly restored",
                conflict=True,
                revision=existing.revision,
                conflict_kind="deleted_conflict",
            )
        if operation.base_revision != existing.revision:
            raise DomainError(
                "REVISION_CONFLICT",
                "Link changed on another client",
                conflict=True,
                revision=existing.revision,
            )
        if existing.activity_node_id != activity.id or existing.metric_id != metric.id:
            raise DomainError(
                "IMMUTABLE_LINK_ENDPOINTS", "Link endpoints cannot be changed"
            )
        existing.coefficient = payload.coefficient
        existing.show_in_activity_detail = payload.show_in_activity_detail
        existing.prompt_on_complete = payload.prompt_on_complete
        existing.is_active = payload.is_active
        existing.revision += 1
        existing.updated_at = utc_now()
        link = existing
    entity = serialize_link(link, activity.public_id, metric.public_id)
    await append_change(
        session,
        user_id=user_id,
        device_id=device.id,
        operation_id=str(operation.operation_id),
        entity_type="activity_metric_link",
        entity_uuid=link.public_id,
        operation="upsert",
        revision=link.revision,
        payload=entity,
    )
    return link.revision, entity
