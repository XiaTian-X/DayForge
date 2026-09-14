"""Immutable activity fact mutations within the caller-owned sync transaction."""

from __future__ import annotations

from decimal import Decimal
from typing import Any

from pydantic import ValidationError
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import select

from src.v2.change_log import append_change
from src.v2.encoding import canonical_json
from src.v2.entity_snapshots import serialize_activity_event_with_allocations
from src.v2.errors import DomainError
from src.v2.models import ActivityDetail, ActivityEvent, ClientDevice, PlanNode
from src.v2.plan_node_mutations import get_plan_node
from src.v2.schemas import ActivityEventPayload, SyncOperationRequest


async def mutate_activity_event(
    session: AsyncSession,
    user_id: int,
    device: ClientDevice,
    operation: SyncOperationRequest,
) -> tuple[int, dict[str, Any]]:
    result = await session.execute(
        select(ActivityEvent).where(
            ActivityEvent.owner_user_id == user_id,
            ActivityEvent.public_id == str(operation.entity_uuid),
        )
    )
    existing = result.scalar_one_or_none()
    if operation.action == "delete":
        raise DomainError("USE_REVERT_EVENT", "Activity events are immutable; append a revert event")
    if existing is not None:
        activity = await session.get(PlanNode, existing.activity_node_id)
        revert = await session.get(ActivityEvent, existing.reverts_event_id) if existing.reverts_event_id else None
        entity = await serialize_activity_event_with_allocations(
            session, existing, activity.public_id, revert.public_id if revert else None
        )
        raise DomainError(
            "ENTITY_ALREADY_EXISTS",
            "An event with this UUID already exists",
            conflict=True,
            revision=existing.revision,
            entity=entity,
        )
    if operation.base_revision not in (None, 0):
        raise DomainError("INVALID_BASE_REVISION", "New events must not have a positive base revision")
    try:
        payload = ActivityEventPayload.model_validate(operation.payload)
    except ValidationError as exc:
        raise DomainError("INVALID_PAYLOAD", str(exc)) from exc

    activity = await get_plan_node(session, user_id, str(payload.activity_uuid), include_deleted=False)
    if activity is None or activity.node_kind != "activity":
        raise DomainError("ACTIVITY_NOT_FOUND", "Activity was not found")
    detail = await session.get(ActivityDetail, activity.id)
    allowed_types = {
        "check": {"check_in", "revert"},
        "count": {"count_delta", "count_snapshot", "revert"},
        "duration": {"duration_session", "revert"},
    }[detail.tracking_mode]
    if payload.event_type not in allowed_types:
        raise DomainError("EVENT_TYPE_MISMATCH", "Event type does not match activity tracking mode")
    if payload.event_type == "duration_session":
        raise DomainError(
            "TIMER_COMMAND_REQUIRED",
            "Timer sessions must be completed through the timer command protocol",
        )
    if (
        detail.tracking_mode == "count"
        and payload.value is not None
        and (
            payload.value != payload.value.to_integral_value()
            or payload.value < -2_147_483_648
            or payload.value > 2_147_483_647
        )
    ):
        raise DomainError("INVALID_COUNT_VALUE", "Count events must use Android-range whole numbers")

    revert_event = None
    if payload.reverts_event_uuid:
        revert_result = await session.execute(
            select(ActivityEvent).where(
                ActivityEvent.owner_user_id == user_id,
                ActivityEvent.public_id == str(payload.reverts_event_uuid),
                ActivityEvent.deleted_at.is_(None),
            )
        )
        revert_event = revert_result.scalar_one_or_none()
        if revert_event is None or revert_event.activity_node_id != activity.id:
            raise DomainError("REVERT_TARGET_NOT_FOUND", "Revert target was not found for this activity")
        duplicate_revert = await session.execute(
            select(ActivityEvent).where(
                ActivityEvent.owner_user_id == user_id,
                ActivityEvent.reverts_event_id == revert_event.id,
                ActivityEvent.deleted_at.is_(None),
            )
        )
        if duplicate_revert.scalar_one_or_none() is not None:
            raise DomainError("EVENT_ALREADY_REVERTED", "The target event has already been reverted")

    source_device_id = str(payload.source_device_id) if payload.source_device_id else device.public_id
    if source_device_id != device.public_id:
        raise DomainError("SOURCE_DEVICE_MISMATCH", "A client may not impersonate another device")

    event = ActivityEvent(
        public_id=str(operation.entity_uuid),
        owner_user_id=user_id,
        activity_node_id=activity.id,
        event_type=payload.event_type,
        value=payload.value if payload.value is not None else (Decimal("1") if payload.event_type == "check_in" else None),
        duration_seconds=payload.duration_seconds,
        duration_milliseconds=payload.duration_milliseconds,
        started_at=payload.started_at,
        ended_at=payload.ended_at,
        occurred_at=payload.occurred_at,
        local_date=payload.local_date,
        timezone=payload.timezone,
        note=payload.note,
        source_type=payload.source_type,
        source_device_public_id=source_device_id,
        external_event_id=payload.external_event_id,
        recorded_by_user_id=user_id,
        reverts_event_id=revert_event.id if revert_event else None,
        payload_json=canonical_json(payload.metadata),
    )
    session.add(event)
    try:
        await session.flush()
    except IntegrityError as exc:
        if payload.event_type == "revert":
            raise DomainError("EVENT_ALREADY_REVERTED", "The target event has already been reverted") from exc
        if payload.external_event_id:
            raise DomainError("DUPLICATE_EXTERNAL_EVENT", "This source event was already recorded") from exc
        raise DomainError("CONSTRAINT_VIOLATION", "The activity event violated a database constraint") from exc
    entity = await serialize_activity_event_with_allocations(
        session, event, activity.public_id, revert_event.public_id if revert_event else None
    )
    await append_change(
        session,
        user_id=user_id,
        device_id=device.id,
        operation_id=str(operation.operation_id),
        entity_type="activity_event",
        entity_uuid=event.public_id,
        operation="upsert",
        revision=event.revision,
        payload=entity,
    )
    return event.revision, entity
