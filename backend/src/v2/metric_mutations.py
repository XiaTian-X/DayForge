"""Metric configuration and immutable observation mutations for sync.

The caller supplies the authenticated owner, registered device and session.
Capability checks, merge preparation, savepoints and commits belong to the
sync orchestration layer; handlers write entities and history in its transaction.
"""

from __future__ import annotations

from typing import Any, Optional

from pydantic import ValidationError
from sqlalchemy import update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import select

from src.v2.change_log import append_change
from src.v2.encoding import canonical_json
from src.v2.entity_snapshots import serialize_metric, serialize_observation
from src.v2.errors import DomainError
from src.v2.models import ClientDevice, MetricObservation, TrackedMetric, utc_now
from src.v2.schemas import MetricObservationPayload, MetricPayload, SyncOperationRequest


async def get_metric(
    session: AsyncSession,
    owner_user_id: int,
    public_id: str,
    *,
    include_deleted: bool = True,
) -> Optional[TrackedMetric]:
    query = select(TrackedMetric).where(
        TrackedMetric.owner_user_id == owner_user_id,
        TrackedMetric.public_id == public_id,
    )
    if not include_deleted:
        query = query.where(TrackedMetric.deleted_at.is_(None))
    result = await session.execute(query)
    return result.scalar_one_or_none()


async def mutate_metric(
    session: AsyncSession,
    user_id: int,
    device: ClientDevice,
    operation: SyncOperationRequest,
) -> tuple[int, dict[str, Any]]:
    existing = await get_metric(session, user_id, str(operation.entity_uuid))
    if operation.action == "delete":
        if existing is None:
            raise DomainError("ENTITY_NOT_FOUND", "Metric was not found")
        if existing.deleted_at is not None:
            return existing.revision, serialize_metric(existing)
        if operation.base_revision != existing.revision:
            entity = serialize_metric(existing)
            raise DomainError(
                "REVISION_CONFLICT",
                "Metric changed on another client",
                conflict=True,
                revision=existing.revision,
                entity=entity,
            )
        now = utc_now()
        result = await session.execute(
            update(TrackedMetric)
            .where(
                TrackedMetric.id == existing.id,
                TrackedMetric.owner_user_id == user_id,
                TrackedMetric.revision == operation.base_revision,
                TrackedMetric.deleted_at.is_(None),
            )
            .values(revision=TrackedMetric.revision + 1, updated_at=now, deleted_at=now)
        )
        if result.rowcount != 1:
            raise DomainError(
                "REVISION_CONFLICT", "Metric changed concurrently", conflict=True
            )
        await session.flush()
        existing = await get_metric(session, user_id, str(operation.entity_uuid))
        entity = serialize_metric(existing)
        await append_change(
            session,
            user_id=user_id,
            device_id=device.id,
            operation_id=str(operation.operation_id),
            entity_type="metric",
            entity_uuid=existing.public_id,
            operation="delete",
            revision=existing.revision,
            payload=entity,
        )
        return existing.revision, entity

    try:
        payload = MetricPayload.model_validate(operation.payload)
    except ValidationError as exc:
        raise DomainError("INVALID_PAYLOAD", str(exc)) from exc

    if existing is None:
        if operation.base_revision not in (None, 0):
            raise DomainError(
                "ENTITY_NOT_FOUND", "Cannot update a metric that does not exist"
            )
        metric = TrackedMetric(
            public_id=str(operation.entity_uuid),
            owner_user_id=user_id,
            created_by_user_id=user_id,
            **payload.model_dump(),
        )
        session.add(metric)
        await session.flush()
    else:
        if existing.deleted_at is not None:
            raise DomainError(
                "ENTITY_DELETED",
                "Deleted metrics cannot be implicitly restored",
                conflict=True,
                revision=existing.revision,
                entity=serialize_metric(existing),
                conflict_kind="deleted_conflict",
            )
        if operation.base_revision != existing.revision:
            raise DomainError(
                "REVISION_CONFLICT",
                "Metric changed on another client",
                conflict=True,
                revision=existing.revision,
                entity=serialize_metric(existing),
            )
        now = utc_now()
        values = payload.model_dump()
        values.update(revision=TrackedMetric.revision + 1, updated_at=now)
        result = await session.execute(
            update(TrackedMetric)
            .where(
                TrackedMetric.id == existing.id,
                TrackedMetric.owner_user_id == user_id,
                TrackedMetric.revision == operation.base_revision,
                TrackedMetric.deleted_at.is_(None),
            )
            .values(**values)
        )
        if result.rowcount != 1:
            raise DomainError(
                "REVISION_CONFLICT", "Metric changed concurrently", conflict=True
            )
        await session.flush()
        metric = await get_metric(session, user_id, str(operation.entity_uuid))

    entity = serialize_metric(metric)
    await append_change(
        session,
        user_id=user_id,
        device_id=device.id,
        operation_id=str(operation.operation_id),
        entity_type="metric",
        entity_uuid=metric.public_id,
        operation="upsert",
        revision=metric.revision,
        payload=entity,
    )
    return metric.revision, entity


async def mutate_observation(
    session: AsyncSession,
    user_id: int,
    device: ClientDevice,
    operation: SyncOperationRequest,
) -> tuple[int, dict[str, Any]]:
    result = await session.execute(
        select(MetricObservation).where(
            MetricObservation.owner_user_id == user_id,
            MetricObservation.public_id == str(operation.entity_uuid),
        )
    )
    existing = result.scalar_one_or_none()
    if operation.action == "delete":
        if existing is None:
            raise DomainError("ENTITY_NOT_FOUND", "Metric observation was not found")
        if existing.deleted_at is not None:
            metric = await session.get(TrackedMetric, existing.metric_id)
            return existing.revision, serialize_observation(existing, metric.public_id)
        if operation.base_revision != existing.revision:
            raise DomainError(
                "REVISION_CONFLICT",
                "Observation changed on another client",
                conflict=True,
                revision=existing.revision,
            )
        existing.revision += 1
        existing.updated_at = utc_now()
        existing.deleted_at = existing.updated_at
        metric = await session.get(TrackedMetric, existing.metric_id)
        entity = serialize_observation(existing, metric.public_id)
        await append_change(
            session,
            user_id=user_id,
            device_id=device.id,
            operation_id=str(operation.operation_id),
            entity_type="metric_observation",
            entity_uuid=existing.public_id,
            operation="delete",
            revision=existing.revision,
            payload=entity,
        )
        return existing.revision, entity
    if existing is not None:
        metric = await session.get(TrackedMetric, existing.metric_id)
        raise DomainError(
            "ENTITY_ALREADY_EXISTS",
            "An observation with this UUID already exists",
            conflict=True,
            revision=existing.revision,
            entity=serialize_observation(existing, metric.public_id),
        )
    if operation.base_revision not in (None, 0):
        raise DomainError(
            "INVALID_BASE_REVISION",
            "New observations must not have a positive base revision",
        )
    try:
        payload = MetricObservationPayload.model_validate(operation.payload)
    except ValidationError as exc:
        raise DomainError("INVALID_PAYLOAD", str(exc)) from exc
    metric = await get_metric(
        session, user_id, str(payload.metric_uuid), include_deleted=False
    )
    if metric is None:
        raise DomainError("METRIC_NOT_FOUND", "Metric was not found")
    source_device_id = (
        str(payload.source_device_id) if payload.source_device_id else device.public_id
    )
    if source_device_id != device.public_id:
        raise DomainError(
            "SOURCE_DEVICE_MISMATCH", "A client may not impersonate another device"
        )
    observation = MetricObservation(
        public_id=str(operation.entity_uuid),
        owner_user_id=user_id,
        metric_id=metric.id,
        value=payload.value,
        unit=payload.unit,
        occurred_at=payload.occurred_at,
        local_date=payload.local_date,
        timezone=payload.timezone,
        note=payload.note,
        source_type=payload.source_type,
        source_device_public_id=source_device_id,
        external_event_id=payload.external_event_id,
        recorded_by_user_id=user_id,
        payload_json=canonical_json(payload.metadata),
    )
    session.add(observation)
    try:
        await session.flush()
    except IntegrityError as exc:
        if payload.external_event_id:
            raise DomainError(
                "DUPLICATE_EXTERNAL_EVENT",
                "This source observation was already recorded",
            ) from exc
        raise DomainError(
            "CONSTRAINT_VIOLATION", "The observation violated a database constraint"
        ) from exc
    entity = serialize_observation(observation, metric.public_id)
    await append_change(
        session,
        user_id=user_id,
        device_id=device.id,
        operation_id=str(operation.operation_id),
        entity_type="metric_observation",
        entity_uuid=observation.public_id,
        operation="upsert",
        revision=observation.revision,
        payload=entity,
    )
    return observation.revision, entity
