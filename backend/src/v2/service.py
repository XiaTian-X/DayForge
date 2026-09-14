"""Application services for v2 domain mutations and incremental sync."""

from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal
from typing import Any, Optional

from pydantic import ValidationError
from sqlalchemy import func, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import select

from src.auth.models import User
from src.v2.change_log import append_change
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
from src.v2.metric_mutations import get_metric, mutate_metric, mutate_observation
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
    GoalDetail,
    MetricObservation,
    PlanNode,
    SyncChange,
    SyncCursor,
    SyncOperation,
    TrackedMetric,
    utc_now,
)
from src.v2.schemas import (
    ActivityEventPayload,
    ActivityMetricLinkPayload,
    DeviceRegisterRequest,
    DeviceResponse,
    PlanNodePayload,
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


async def _get_plan_node(
    session: AsyncSession,
    owner_user_id: int,
    public_id: str,
    *,
    include_deleted: bool = True,
) -> Optional[PlanNode]:
    query = select(PlanNode).where(
        PlanNode.owner_user_id == owner_user_id,
        PlanNode.public_id == public_id,
    )
    if not include_deleted:
        query = query.where(PlanNode.deleted_at.is_(None))
    result = await session.execute(query)
    return result.scalar_one_or_none()


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


async def _resolve_parent(
    session: AsyncSession,
    user_id: int,
    parent_uuid: Optional[str],
) -> Optional[PlanNode]:
    if parent_uuid is None:
        return None
    parent = await _get_plan_node(session, user_id, parent_uuid, include_deleted=False)
    if parent is None:
        raise DomainError("PARENT_NOT_FOUND", "Parent goal was not found")
    if parent.node_kind != "goal" or parent.parent_node_id is not None:
        raise DomainError("INVALID_PARENT", "An activity parent must be a top-level goal")
    return parent


async def _ensure_unique_plan_node_title(
    session: AsyncSession,
    user_id: int,
    title: str,
    existing_id: Optional[int],
) -> None:
    statement = select(PlanNode.id).where(
        PlanNode.owner_user_id == user_id,
        PlanNode.title == title,
        PlanNode.deleted_at.is_(None),
    )
    if existing_id is not None:
        statement = statement.where(PlanNode.id != existing_id)
    if (await session.execute(statement.limit(1))).scalar_one_or_none() is not None:
        raise DomainError("DUPLICATE_TITLE", "An active habit or goal with this title already exists")


async def _mutate_plan_node(
    session: AsyncSession,
    user_id: int,
    device: ClientDevice,
    operation: SyncOperationRequest,
) -> tuple[int, dict[str, Any]]:
    existing = await _get_plan_node(session, user_id, str(operation.entity_uuid))

    if operation.action == "delete":
        if existing is None:
            raise DomainError("ENTITY_NOT_FOUND", "Plan node was not found")
        if existing.deleted_at is not None:
            return existing.revision, await serialize_plan_node(session, existing)
        if operation.base_revision != existing.revision:
            entity = await serialize_plan_node(session, existing)
            raise DomainError(
                "REVISION_CONFLICT",
                "Plan node changed on another client",
                conflict=True,
                entity=entity,
                revision=existing.revision,
            )

        now = utc_now()
        if existing.node_kind == "goal":
            children_result = await session.execute(
                select(PlanNode).where(
                    PlanNode.owner_user_id == user_id,
                    PlanNode.parent_node_id == existing.id,
                    PlanNode.deleted_at.is_(None),
                )
            )
            children = list(children_result.scalars().all())
            policy = operation.payload.get("child_policy")
            if children and policy not in {"cascade_children", "detach_children"}:
                raise DomainError(
                    "CHILD_POLICY_REQUIRED",
                    "Deleting a goal with children requires child_policy",
                )
            for child in children:
                child.revision += 1
                child.updated_at = now
                if policy == "cascade_children":
                    child.deleted_at = now
                    child_payload = await serialize_plan_node(session, child)
                    await append_change(
                        session,
                        user_id=user_id,
                        device_id=device.id,
                        operation_id=str(operation.operation_id),
                        entity_type="plan_node",
                        entity_uuid=child.public_id,
                        operation="delete",
                        revision=child.revision,
                        payload=child_payload,
                    )
                else:
                    child.parent_node_id = None
                    child_payload = await serialize_plan_node(session, child)
                    await append_change(
                        session,
                        user_id=user_id,
                        device_id=device.id,
                        operation_id=str(operation.operation_id),
                        entity_type="plan_node",
                        entity_uuid=child.public_id,
                        operation="upsert",
                        revision=child.revision,
                        payload=child_payload,
                    )

        result = await session.execute(
            update(PlanNode)
            .where(
                PlanNode.id == existing.id,
                PlanNode.owner_user_id == user_id,
                PlanNode.revision == operation.base_revision,
                PlanNode.deleted_at.is_(None),
            )
            .values(
                revision=PlanNode.revision + 1,
                updated_at=now,
                deleted_at=now,
            )
        )
        if result.rowcount != 1:
            raise DomainError("REVISION_CONFLICT", "Plan node changed concurrently", conflict=True)
        await session.flush()
        existing = await _get_plan_node(session, user_id, str(operation.entity_uuid))
        entity = await serialize_plan_node(session, existing)
        await append_change(
            session,
            user_id=user_id,
            device_id=device.id,
            operation_id=str(operation.operation_id),
            entity_type="plan_node",
            entity_uuid=existing.public_id,
            operation="delete",
            revision=existing.revision,
            payload=entity,
        )
        return existing.revision, entity

    try:
        payload = PlanNodePayload.model_validate(operation.payload)
    except ValidationError as exc:
        raise DomainError("INVALID_PAYLOAD", str(exc)) from exc

    parent = await _resolve_parent(
        session,
        user_id,
        str(payload.parent_uuid) if payload.parent_uuid else None,
    )

    await _ensure_unique_plan_node_title(
        session,
        user_id,
        payload.title,
        existing.id if existing else None,
    )

    if existing is None:
        if operation.base_revision not in (None, 0):
            raise DomainError("ENTITY_NOT_FOUND", "Cannot update a plan node that does not exist")
        created_at = payload.created_at or utc_now()
        node = PlanNode(
            public_id=str(operation.entity_uuid),
            owner_user_id=user_id,
            created_by_user_id=user_id,
            parent_node_id=parent.id if parent else None,
            node_kind=payload.node_kind,
            title=payload.title,
            description=payload.description,
            icon=payload.icon,
            color_hex=payload.color_hex,
            status=payload.status,
            visibility=payload.visibility,
            sort_order=payload.sort_order,
            created_at=created_at,
            updated_at=created_at,
        )
        session.add(node)
        await session.flush()
        if payload.node_kind == "goal":
            detail = payload.goal
            session.add(
                GoalDetail(
                    node_id=node.id,
                    start_date=detail.start_date,
                    due_date=detail.due_date,
                    target_cycles=detail.target_cycles,
                    failure_policy_json=canonical_json(detail.failure_policy.model_dump(mode="json")),
                    evaluation_policy_json=canonical_json(detail.evaluation_policy.model_dump(mode="json")),
                    manual_result=detail.manual_result,
                )
            )
        else:
            detail = payload.activity
            session.add(
                ActivityDetail(
                    node_id=node.id,
                    tracking_mode=detail.tracking_mode,
                    is_countdown=detail.is_countdown,
                    recurrence_rule_json=canonical_json(detail.recurrence_rule.model_dump(mode="json")),
                    completion_policy=detail.completion_policy,
                    target_value=detail.target_value,
                    target_unit=detail.target_unit,
                    target_cycles=detail.target_cycles,
                    failure_policy_json=canonical_json(detail.failure_policy.model_dump(mode="json")),
                    preferred_local_time=detail.preferred_local_time,
                    timezone=detail.timezone,
                    origin_assignment_id=str(detail.origin_assignment_id) if detail.origin_assignment_id else None,
                )
            )
        await session.flush()
        entity = await serialize_plan_node(session, node)
        await append_change(
            session,
            user_id=user_id,
            device_id=device.id,
            operation_id=str(operation.operation_id),
            entity_type="plan_node",
            entity_uuid=node.public_id,
            operation="upsert",
            revision=node.revision,
            payload=entity,
        )
        return node.revision, entity

    if existing.deleted_at is not None:
        entity = await serialize_plan_node(session, existing)
        raise DomainError(
            "ENTITY_DELETED",
            "Deleted nodes cannot be implicitly restored",
            conflict=True,
            entity=entity,
            revision=existing.revision,
            conflict_kind="deleted_conflict",
        )
    if existing.node_kind != payload.node_kind:
        raise DomainError("IMMUTABLE_NODE_KIND", "node_kind cannot be changed")
    if operation.base_revision != existing.revision:
        entity = await serialize_plan_node(session, existing)
        raise DomainError(
            "REVISION_CONFLICT",
            "Plan node changed on another client",
            conflict=True,
            entity=entity,
            revision=existing.revision,
        )

    now = utc_now()
    node_values = {
        "parent_node_id": parent.id if parent else None,
        "title": payload.title,
        "description": payload.description,
        "icon": payload.icon,
        "color_hex": payload.color_hex,
        "status": payload.status,
        "visibility": payload.visibility,
        "revision": PlanNode.revision + 1,
        "updated_at": now,
    }
    if "sort_order" in payload.model_fields_set:
        node_values["sort_order"] = payload.sort_order
    result = await session.execute(
        update(PlanNode)
        .where(
            PlanNode.id == existing.id,
            PlanNode.owner_user_id == user_id,
            PlanNode.revision == operation.base_revision,
            PlanNode.deleted_at.is_(None),
        )
        .values(**node_values)
    )
    if result.rowcount != 1:
        raise DomainError("REVISION_CONFLICT", "Plan node changed concurrently", conflict=True)

    if existing.node_kind == "goal":
        detail = await session.get(GoalDetail, existing.id)
        goal_payload = payload.goal
        if "start_date" in goal_payload.model_fields_set:
            detail.start_date = goal_payload.start_date
        if "due_date" in goal_payload.model_fields_set:
            detail.due_date = goal_payload.due_date
        detail.target_cycles = goal_payload.target_cycles
        detail.failure_policy_json = canonical_json(goal_payload.failure_policy.model_dump(mode="json"))
        detail.evaluation_policy_json = canonical_json(goal_payload.evaluation_policy.model_dump(mode="json"))
        detail.manual_result = goal_payload.manual_result
    else:
        detail = await session.get(ActivityDetail, existing.id)
        activity_payload = payload.activity
        detail.tracking_mode = activity_payload.tracking_mode
        detail.is_countdown = activity_payload.is_countdown
        recurrence_rule = activity_payload.recurrence_rule.model_dump(mode="json")
        previous_recurrence_rule = parse_json(detail.recurrence_rule_json)
        if recurrence_rule.get("type") == previous_recurrence_rule.get("type"):
            for optional_date in ("start_date", "due_date"):
                if (
                    optional_date not in activity_payload.recurrence_rule.model_fields_set
                    and optional_date in previous_recurrence_rule
                ):
                    recurrence_rule[optional_date] = previous_recurrence_rule[optional_date]
        detail.recurrence_rule_json = canonical_json(recurrence_rule)
        detail.completion_policy = activity_payload.completion_policy
        detail.target_value = activity_payload.target_value
        detail.target_unit = activity_payload.target_unit
        detail.target_cycles = activity_payload.target_cycles
        detail.failure_policy_json = canonical_json(activity_payload.failure_policy.model_dump(mode="json"))
        detail.preferred_local_time = activity_payload.preferred_local_time
        detail.timezone = activity_payload.timezone
        if "origin_assignment_id" in activity_payload.model_fields_set:
            detail.origin_assignment_id = (
                str(activity_payload.origin_assignment_id) if activity_payload.origin_assignment_id else None
            )
    await session.flush()
    existing = await _get_plan_node(session, user_id, str(operation.entity_uuid))
    entity = await serialize_plan_node(session, existing)
    await append_change(
        session,
        user_id=user_id,
        device_id=device.id,
        operation_id=str(operation.operation_id),
        entity_type="plan_node",
        entity_uuid=existing.public_id,
        operation="upsert",
        revision=existing.revision,
        payload=entity,
    )
    return existing.revision, entity


async def _mutate_activity_event(
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

    activity = await _get_plan_node(session, user_id, str(payload.activity_uuid), include_deleted=False)
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


async def _mutate_link(
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
            return existing.revision, serialize_link(existing, activity.public_id, metric.public_id)
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
    activity = await _get_plan_node(session, user_id, str(payload.activity_uuid), include_deleted=False)
    if activity is None or activity.node_kind != "activity":
        raise DomainError("ACTIVITY_NOT_FOUND", "Activity was not found")
    metric = await get_metric(session, user_id, str(payload.metric_uuid), include_deleted=False)
    if metric is None:
        raise DomainError("METRIC_NOT_FOUND", "Metric was not found")

    if existing is None:
        if operation.base_revision not in (None, 0):
            raise DomainError("ENTITY_NOT_FOUND", "Cannot update a link that does not exist")
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
            raise DomainError("LINK_ALREADY_EXISTS", "This activity and metric are already linked") from exc
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
            raise DomainError("IMMUTABLE_LINK_ENDPOINTS", "Link endpoints cannot be changed")
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


async def _dispatch_operation(
    session: AsyncSession,
    user_id: int,
    device: ClientDevice,
    operation: SyncOperationRequest,
) -> tuple[int, dict[str, Any]]:
    handlers = {
        "plan_node": _mutate_plan_node,
        "activity_event": _mutate_activity_event,
        "metric": mutate_metric,
        "metric_observation": mutate_observation,
        "activity_metric_link": _mutate_link,
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
