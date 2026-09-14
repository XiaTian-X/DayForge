"""Application services for v2 domain mutations and incremental sync."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Optional

from sqlalchemy import func
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import select

from src.auth.models import User
from src.v2.entity_snapshots import (
    current_entity_snapshot,
    serialize_activity_event_with_allocations,
    serialize_link,
    serialize_metric,
    serialize_observation,
    serialize_plan_node,
)
from src.v2.encoding import canonical_json, operation_hash, parse_json
from src.v2.errors import DomainError
from src.v2.merge import MERGE_PATHS, merge_structural_payload
from src.v2.metric_mutations import mutate_metric, mutate_observation
from src.v2.plan_node_mutations import mutate_plan_node
from src.v2.event_mutations import mutate_activity_event
from src.v2.link_mutations import mutate_link
from src.v2.device_service import (
    STRUCTURAL_ENTITY_TYPES,
    STRUCTURE_CAPABILITY,
    assign_first_primary,
    capabilities_for_device,
    to_device_response,
)
from src.v2.models import (
    ActivityDetail,
    ActivityEvent,
    ActivityMetricLinkV2,
    ClientDevice,
    EntityRevisionSnapshot,
    MetricObservation,
    PlanNode,
    SyncChange,
    SyncCursor,
    SyncOperation,
    TrackedMetric,
    utc_now,
)
from src.v2.schemas import (
    DeviceRegisterRequest,
    DeviceResponse,
    SyncBootstrapResponse,
    SyncChangeResponse,
    SyncOperationRequest,
    SyncOperationResult,
    SyncPullResponse,
    SyncPushRequest,
    SyncPushResponse,
)


async def register_device(
    user: User,
    request: DeviceRegisterRequest,
    session: AsyncSession,
) -> DeviceResponse:
    if request.protocol_version < 4 or "protocol_version" not in request.model_fields_set:
        raise DomainError(
            "CLIENT_UPGRADE_REQUIRED",
            "This DayForge client must be upgraded before it can synchronize",
        )
    result = await session.execute(
        select(ClientDevice).where(
            ClientDevice.user_id == user.id,
            ClientDevice.installation_id == request.installation_id,
        )
    )
    device = result.scalar_one_or_none()
    now = utc_now()
    if device is None:
        if request.device_class != "interactive":
            raise DomainError(
                "DEVICE_PROVISIONING_REQUIRED",
                "Hardware and automation devices must be provisioned by an administrator",
            )
        device = ClientDevice(
            user_id=user.id,
            installation_id=request.installation_id,
            platform=request.platform,
            device_class=request.device_class,
            app_version=request.app_version,
            display_name=request.display_name,
            last_seen_at=now,
        )
        session.add(device)
    else:
        if device.revoked_at is not None:
            raise DomainError(
                "DEVICE_REVOKED",
                "This device was revoked and must be re-enabled by an administrator",
            )
        if device.platform != request.platform or device.device_class != request.device_class:
            raise DomainError(
                "DEVICE_IDENTITY_MISMATCH",
                "A registered device cannot change its platform or device class",
            )
        device.app_version = request.app_version
        device.display_name = request.display_name
        device.last_seen_at = now
    await session.flush()
    await assign_first_primary(session, device)
    await session.flush()
    return await to_device_response(session, device)


async def require_device(
    user_id: int,
    device_public_id: str,
    session: AsyncSession,
) -> ClientDevice:
    result = await session.execute(
        select(ClientDevice).where(
            ClientDevice.user_id == user_id,
            ClientDevice.public_id == device_public_id,
            ClientDevice.revoked_at.is_(None),
        )
    )
    device = result.scalar_one_or_none()
    if device is None:
        raise DomainError("DEVICE_NOT_FOUND", "Device is not registered or has been revoked")
    device.last_seen_at = utc_now()
    return device


async def _prepare_three_way_merge(
    session: AsyncSession,
    user_id: int,
    operation: SyncOperationRequest,
) -> tuple[SyncOperationRequest, Optional[tuple[int, dict[str, Any]]]]:
    if operation.entity_type not in MERGE_PATHS or operation.action != "upsert" or not operation.base_revision:
        return operation, None

    current_revision, server_payload = await current_entity_snapshot(session, user_id, operation)
    if current_revision is None or server_payload is None or current_revision == operation.base_revision:
        return operation, None
    if server_payload.get("deleted_at") is not None:
        return operation, None

    snapshot_result = await session.execute(
        select(EntityRevisionSnapshot).where(
            EntityRevisionSnapshot.owner_user_id == user_id,
            EntityRevisionSnapshot.entity_type == operation.entity_type,
            EntityRevisionSnapshot.entity_uuid == str(operation.entity_uuid),
            EntityRevisionSnapshot.revision == operation.base_revision,
        )
    )
    base_snapshot = snapshot_result.scalar_one_or_none()
    return merge_structural_payload(
        operation,
        current_revision=current_revision,
        server_payload=server_payload,
        base_snapshot_json=base_snapshot.payload_json if base_snapshot is not None else None,
    )


async def _dispatch_operation(
    session: AsyncSession,
    user_id: int,
    device: ClientDevice,
    operation: SyncOperationRequest,
) -> tuple[int, dict[str, Any]]:
    handlers = {
        "plan_node": mutate_plan_node,
        "activity_event": mutate_activity_event,
        "metric": mutate_metric,
        "metric_observation": mutate_observation,
        "activity_metric_link": mutate_link,
    }
    return await handlers[operation.entity_type](session, user_id, device, operation)


async def _is_fact_derived_one_time_delete(
    session: AsyncSession,
    user_id: int,
    operation: SyncOperationRequest,
) -> bool:
    """Allow a facts-only device to finalize an already completed one-time task.

    Android currently removes a temporary task immediately after appending its
    check-in. Treating that narrowly validated delete as task finalization keeps
    secondary-device check-in functional without granting arbitrary structure writes.
    """
    if operation.entity_type != "plan_node" or operation.action != "delete":
        return False
    result = await session.execute(
        select(PlanNode, ActivityDetail)
        .join(ActivityDetail, ActivityDetail.node_id == PlanNode.id)
        .where(
            PlanNode.owner_user_id == user_id,
            PlanNode.public_id == str(operation.entity_uuid),
            PlanNode.deleted_at.is_(None),
            ActivityDetail.completion_policy == "one_and_done",
        )
    )
    row = result.one_or_none()
    if row is None:
        return False
    node, _ = row
    event_result = await session.execute(
        select(ActivityEvent.id).where(
            ActivityEvent.owner_user_id == user_id,
            ActivityEvent.activity_node_id == node.id,
            ActivityEvent.event_type == "check_in",
            ActivityEvent.deleted_at.is_(None),
        )
    )
    return event_result.first() is not None


async def process_push(
    user: User,
    request: SyncPushRequest,
    session: AsyncSession,
) -> SyncPushResponse:
    device = await require_device(user.id, str(request.device_id), session)
    device_capabilities, _ = await capabilities_for_device(session, device)
    can_write_structure = STRUCTURE_CAPABILITY in device_capabilities
    results: list[SyncOperationResult] = []

    for operation in request.operations:
        request_hash = operation_hash(operation)
        previous_result = await session.execute(
            select(SyncOperation).where(
                SyncOperation.device_id == device.id,
                SyncOperation.operation_id == str(operation.operation_id),
            )
        )
        previous = previous_result.scalar_one_or_none()
        if previous is not None:
            if previous.request_hash != request_hash:
                results.append(
                    SyncOperationResult(
                        operation_id=operation.operation_id,
                        entity_type=operation.entity_type,
                        entity_uuid=operation.entity_uuid,
                        status="rejected",
                        error_code="OPERATION_ID_REUSED",
                        message="operation_id was already used with a different request",
                    )
                )
            else:
                stored = parse_json(previous.result_json)
                if stored.get("status") == "applied":
                    stored["status"] = "already_applied"
                results.append(SyncOperationResult.model_validate(stored))
            continue

        operation_record: Optional[SyncOperation] = None
        try:
            # The initial lookup and insert are necessarily racy. Isolate the
            # unique-key insert in a savepoint so a simultaneous retry can be
            # resolved as an idempotent replay without poisoning the request
            # transaction.
            async with session.begin_nested():
                operation_record = SyncOperation(
                    user_id=user.id,
                    device_id=device.id,
                    operation_id=str(operation.operation_id),
                    request_hash=request_hash,
                    status="processing",
                    entity_type=operation.entity_type,
                    entity_uuid=str(operation.entity_uuid),
                    action=operation.action,
                    base_revision=operation.base_revision,
                )
                session.add(operation_record)
                await session.flush()
        except IntegrityError:
            raced_result = await session.execute(
                select(SyncOperation).where(
                    SyncOperation.device_id == device.id,
                    SyncOperation.operation_id == str(operation.operation_id),
                )
            )
            raced = raced_result.scalar_one_or_none()
            if raced is None:
                results.append(
                    SyncOperationResult(
                        operation_id=operation.operation_id,
                        entity_type=operation.entity_type,
                        entity_uuid=operation.entity_uuid,
                        status="rejected",
                        error_code="CONSTRAINT_VIOLATION",
                        message="The operation record violated a database constraint",
                    )
                )
            elif raced.request_hash != request_hash:
                results.append(
                    SyncOperationResult(
                        operation_id=operation.operation_id,
                        entity_type=operation.entity_type,
                        entity_uuid=operation.entity_uuid,
                        status="rejected",
                        error_code="OPERATION_ID_REUSED",
                        message="operation_id was already used with a different request",
                    )
                )
            else:
                stored = parse_json(raced.result_json)
                if not stored or raced.status == "processing":
                    # This is transient, not a permanently invalid operation.
                    # Abort the batch so clients retain every outbox row and
                    # can retry once the winning transaction has committed.
                    raise DomainError(
                        "OPERATION_IN_PROGRESS",
                        "The same operation is still being processed; retry later",
                    )
                else:
                    if stored.get("status") == "applied":
                        stored["status"] = "already_applied"
                    results.append(SyncOperationResult.model_validate(stored))
            continue

        if operation_record is None:  # Defensive guard for static type safety.
            raise RuntimeError("sync operation record was not created")

        try:
            async with session.begin_nested():
                if (
                    operation.entity_type in STRUCTURAL_ENTITY_TYPES
                    and not can_write_structure
                    and not await _is_fact_derived_one_time_delete(session, user.id, operation)
                ):
                    raise DomainError(
                        "DEVICE_CAPABILITY_DENIED",
                        "This device is not allowed to edit goals, habits or metric configuration",
                    )
                prepared_operation, no_op = await _prepare_three_way_merge(
                    session,
                    user.id,
                    operation,
                )
                if no_op is not None:
                    revision, entity = no_op
                else:
                    revision, entity = await _dispatch_operation(
                        session,
                        user.id,
                        device,
                        prepared_operation,
                    )
            result = SyncOperationResult(
                operation_id=operation.operation_id,
                entity_type=operation.entity_type,
                entity_uuid=operation.entity_uuid,
                status="applied",
                revision=revision,
                entity=entity,
            )
            operation_record.status = "applied"
        except DomainError as exc:
            if exc.conflict and (exc.revision is None or exc.entity is None):
                current_revision, current_entity = await current_entity_snapshot(
                    session,
                    user.id,
                    operation,
                )
                exc.revision = exc.revision if exc.revision is not None else current_revision
                exc.entity = exc.entity if exc.entity is not None else current_entity
            result = SyncOperationResult(
                operation_id=operation.operation_id,
                entity_type=operation.entity_type,
                entity_uuid=operation.entity_uuid,
                status="conflict" if exc.conflict else "rejected",
                revision=exc.revision,
                error_code=exc.code,
                message=exc.message,
                entity=exc.entity,
                base_entity=exc.base_entity,
                local_entity=exc.local_entity,
                conflicting_fields=exc.conflicting_fields,
                conflict_kind=exc.conflict_kind,
            )
            operation_record.status = result.status
            operation_record.error_code = exc.code
        except IntegrityError:
            result = SyncOperationResult(
                operation_id=operation.operation_id,
                entity_type=operation.entity_type,
                entity_uuid=operation.entity_uuid,
                status="rejected",
                error_code="CONSTRAINT_VIOLATION",
                message="The operation violated a database constraint",
            )
            operation_record.status = "rejected"
            operation_record.error_code = "CONSTRAINT_VIOLATION"

        operation_record.result_json = canonical_json(result.model_dump(mode="json"))
        operation_record.completed_at = utc_now()
        results.append(result)

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
    cursor.last_push_at = utc_now()
    await session.flush()
    return SyncPushResponse(results=results)


async def _change_response(
    session: AsyncSession,
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
    changes = [await _change_response(session, row[0], row[1]) for row in page]
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


__all__ = [
    "bootstrap",
    "process_push",
    "pull_changes",
    "register_device",
    "require_device",
]
