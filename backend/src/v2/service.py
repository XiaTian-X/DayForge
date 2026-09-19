"""Sync push orchestration with caller-owned transactions and idempotency."""

from __future__ import annotations

from typing import Any, Optional

from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import select

from src.auth.models import User
from src.v2.entity_snapshots import current_entity_snapshot
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
    capabilities_for_device,
    require_device,
)
from src.v2.models import (
    ActivityDetail,
    ActivityEvent,
    ClientDevice,
    EntityRevisionSnapshot,
    PlanNode,
    SyncCursor,
    SyncOperation,
    utc_now,
)
from src.v2.schemas import (
    SyncOperationRequest,
    SyncOperationResult,
    SyncPushRequest,
    SyncPushResponse,
)


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


__all__ = ["process_push"]
