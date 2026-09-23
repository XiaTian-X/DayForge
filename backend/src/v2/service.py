"""Sync push orchestration with caller-owned transactions and idempotency."""

from __future__ import annotations

from typing import Any, Literal, Optional, overload

from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import aliased
from sqlmodel import col, select

from src.auth.models import User
from src.v2.entity_snapshots import current_entity_snapshot
from src.v2.encoding import canonical_json, operation_hash, parse_json
from src.v2.errors import DomainError
from src.v2.invariants import require_internal
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
from src.v2.next_sync_contract import (
    NextSyncOperationResult,
    NextSyncPushResponse,
    validate_task_result_binding,
)
from src.v2.one_time_storage import OneTimeStateConflict


async def _prepare_three_way_merge(
    session: AsyncSession,
    user_id: int,
    operation: SyncOperationRequest,
    *,
    next_protocol: bool = False,
) -> tuple[SyncOperationRequest, Optional[tuple[int, dict[str, Any]]]]:
    if (
        operation.entity_type not in MERGE_PATHS
        or operation.action != "upsert"
        or not operation.base_revision
    ):
        return operation, None

    current_revision, server_payload = await current_entity_snapshot(
        session, user_id, operation, next_protocol=next_protocol
    )
    if (
        current_revision is None
        or server_payload is None
        or current_revision == operation.base_revision
    ):
        return operation, None
    if server_payload.get("deleted_at") is not None:
        return operation, None

    snapshot_result = await session.execute(
        select(EntityRevisionSnapshot).where(
            col(EntityRevisionSnapshot.owner_user_id) == user_id,
            col(EntityRevisionSnapshot.entity_type) == operation.entity_type,
            col(EntityRevisionSnapshot.entity_uuid) == str(operation.entity_uuid),
            col(EntityRevisionSnapshot.revision) == operation.base_revision,
        )
    )
    base_snapshot = snapshot_result.scalar_one_or_none()
    return merge_structural_payload(
        operation,
        current_revision=current_revision,
        server_payload=server_payload,
        base_snapshot_json=base_snapshot.payload_json
        if base_snapshot is not None
        else None,
        next_protocol=next_protocol,
    )


async def _dispatch_operation(
    session: AsyncSession,
    user_id: int,
    device: ClientDevice,
    operation: SyncOperationRequest,
    *,
    next_protocol: bool = False,
) -> tuple[int, dict[str, Any]]:
    if next_protocol and operation.entity_type == "plan_node":
        return await mutate_plan_node(
            session, user_id, device, operation, next_protocol=True
        )
    if next_protocol and operation.entity_type == "metric":
        return await mutate_metric(
            session, user_id, device, operation, next_protocol=True
        )
    if next_protocol and operation.entity_type == "activity_event":
        return await mutate_activity_event(
            session, user_id, device, operation, one_time_contract=True
        )
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
        .join(ActivityDetail, col(ActivityDetail.node_id) == col(PlanNode.id))
        .where(
            col(PlanNode.owner_user_id) == user_id,
            col(PlanNode.public_id) == str(operation.entity_uuid),
            col(PlanNode.deleted_at).is_(None),
            col(ActivityDetail.completion_policy) == "one_and_done",
            col(ActivityDetail.one_time_version).is_(None),
        )
    )
    row = result.one_or_none()
    if row is None:
        return False
    node, _ = row
    revert = aliased(ActivityEvent)
    has_revert = (
        select(revert.id)
        .where(
            revert.owner_user_id == user_id,
            revert.reverts_event_id == col(ActivityEvent.id),
            revert.event_type == "revert",
            col(revert.deleted_at).is_(None),
        )
        .correlate(ActivityEvent)
        .exists()
    )
    event_result = await session.execute(
        select(col(ActivityEvent.id)).where(
            col(ActivityEvent.owner_user_id) == user_id,
            col(ActivityEvent.activity_node_id) == node.id,
            col(ActivityEvent.event_type) == "check_in",
            col(ActivityEvent.deleted_at).is_(None),
            ~has_revert,
        )
    )
    return event_result.first() is not None


def _replay_result(
    operation: SyncOperationRequest,
    previous: SyncOperation,
    request_hash: str,
    *,
    next_protocol: bool = False,
) -> SyncOperationResult:
    """Apply identical replay rules to normal lookups and unique-insert races."""
    if previous.request_hash != request_hash:
        return SyncOperationResult(
            operation_id=operation.operation_id,
            entity_type=operation.entity_type,
            entity_uuid=operation.entity_uuid,
            status="rejected",
            error_code="OPERATION_ID_REUSED",
            message="operation_id was already used with a different request",
        )
    stored = parse_json(previous.result_json)
    if not stored or previous.status == "processing":
        # Abort the outer request, including earlier batch items, so the client
        # keeps its outbox and retries after the winning transaction completes.
        raise DomainError(
            "OPERATION_IN_PROGRESS",
            "The same operation is still being processed; retry later",
        )
    if stored.get("status") == "applied":
        stored["status"] = "already_applied"
    if next_protocol:
        result = NextSyncOperationResult.model_validate(stored)
        validate_task_result_binding(operation, result)
        return result
    return SyncOperationResult.model_validate(stored)


@overload
async def process_push(
    user: User,
    request: SyncPushRequest,
    session: AsyncSession,
    *,
    next_protocol: Literal[False] = False,
) -> SyncPushResponse: ...


@overload
async def process_push(
    user: User,
    request: SyncPushRequest,
    session: AsyncSession,
    *,
    next_protocol: Literal[True],
) -> NextSyncPushResponse: ...


async def process_push(
    user: User,
    request: SyncPushRequest,
    session: AsyncSession,
    *,
    next_protocol: bool = False,
) -> SyncPushResponse | NextSyncPushResponse:
    """Share transaction/replay orchestration without activating v5 HTTP.

    The internal switch enables explicit appearance and item policy, new fact
    semantics and result context. Protocol negotiation remains a separate rollout
    step. No current route takes this switch from client input or enables it.
    """
    user_id = require_internal(user.id, "User.id")
    device = await require_device(user_id, str(request.device_id), session)
    device_capabilities, _ = await capabilities_for_device(session, device)
    can_write_structure = STRUCTURE_CAPABILITY in device_capabilities
    results: list[SyncOperationResult] = []

    for operation in request.operations:
        request_hash = operation_hash(operation)
        previous_result = await session.execute(
            select(SyncOperation).where(
                col(SyncOperation.device_id) == device.id,
                col(SyncOperation.operation_id) == str(operation.operation_id),
            )
        )
        previous = previous_result.scalar_one_or_none()
        if previous is not None:
            results.append(
                _replay_result(
                    operation, previous, request_hash, next_protocol=next_protocol
                )
            )
            continue

        try:
            # The initial lookup and insert are necessarily racy. Isolate the
            # unique-key insert in a savepoint so a simultaneous retry can be
            # resolved as an idempotent replay without poisoning the request
            # transaction.
            async with session.begin_nested():
                operation_record = SyncOperation(
                    user_id=user_id,
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
                    col(SyncOperation.device_id) == device.id,
                    col(SyncOperation.operation_id) == str(operation.operation_id),
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
            else:
                results.append(
                    _replay_result(
                        operation, raced, request_hash, next_protocol=next_protocol
                    )
                )
            continue

        try:
            async with session.begin_nested():
                if (
                    operation.entity_type in STRUCTURAL_ENTITY_TYPES
                    and not can_write_structure
                    and (
                        next_protocol
                        or not await _is_fact_derived_one_time_delete(
                            session, user_id, operation
                        )
                    )
                ):
                    raise DomainError(
                        "DEVICE_CAPABILITY_DENIED",
                        "This device is not allowed to edit goals, habits or metric configuration",
                    )
                prepared_operation, no_op = await _prepare_three_way_merge(
                    session,
                    user_id,
                    operation,
                    next_protocol=next_protocol,
                )
                if no_op is not None:
                    revision, entity = no_op
                else:
                    revision, entity = await _dispatch_operation(
                        session,
                        user_id,
                        device,
                        prepared_operation,
                        next_protocol=next_protocol,
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
        except OneTimeStateConflict as exc:
            if not next_protocol:
                raise  # Never serialize a new task conflict through the old DTO.
            result = NextSyncOperationResult(
                operation_id=operation.operation_id,
                entity_type=operation.entity_type,
                entity_uuid=operation.entity_uuid,
                status="conflict",
                error_code=exc.code,
                message=exc.message,
                one_time_conflict=exc.projection,
            )
            operation_record.status = result.status
            operation_record.error_code = exc.code
        except DomainError as exc:
            if exc.conflict and (exc.revision is None or exc.entity is None):
                current_revision, current_entity = await current_entity_snapshot(
                    session,
                    user_id,
                    operation,
                    next_protocol=next_protocol,
                )
                exc.revision = (
                    exc.revision if exc.revision is not None else current_revision
                )
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

        if next_protocol:
            result = NextSyncOperationResult.model_validate(
                result.model_dump(mode="json")
            )
            validate_task_result_binding(operation, result)
        operation_record.result_json = canonical_json(result.model_dump(mode="json"))
        operation_record.completed_at = utc_now()
        results.append(result)

    cursor_result = await session.execute(
        select(SyncCursor).where(
            SyncCursor.user_id == user_id,
            SyncCursor.device_id == device.id,
        )
    )
    cursor = cursor_result.scalar_one_or_none()
    if cursor is None:
        cursor = SyncCursor(user_id=user_id, device_id=device.id)
        session.add(cursor)
    cursor.last_push_at = utc_now()
    await session.flush()
    if next_protocol:
        return NextSyncPushResponse.model_validate(
            {"results": [result.model_dump(mode="json") for result in results]}
        )
    return SyncPushResponse(results=results)


__all__ = ["process_push"]
