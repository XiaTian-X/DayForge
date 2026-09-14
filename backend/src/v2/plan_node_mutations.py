"""Goal and activity structure mutations within the caller's sync transaction.

Parent resolution and title validation use the authenticated account scope.
Deletion cascades/detaches children and journals their revisions in the same
savepoint as the parent; this module does not own sessions or commits.
"""

from __future__ import annotations

from typing import Any, Optional

from pydantic import ValidationError
from sqlalchemy import update
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import select

from src.v2.change_log import append_change
from src.v2.encoding import canonical_json, parse_json
from src.v2.entity_snapshots import serialize_plan_node
from src.v2.errors import DomainError
from src.v2.models import ActivityDetail, ClientDevice, GoalDetail, PlanNode, utc_now
from src.v2.schemas import PlanNodePayload, SyncOperationRequest


async def get_plan_node(
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


async def _resolve_parent(
    session: AsyncSession,
    user_id: int,
    parent_uuid: Optional[str],
) -> Optional[PlanNode]:
    if parent_uuid is None:
        return None
    parent = await get_plan_node(session, user_id, parent_uuid, include_deleted=False)
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


async def mutate_plan_node(
    session: AsyncSession,
    user_id: int,
    device: ClientDevice,
    operation: SyncOperationRequest,
) -> tuple[int, dict[str, Any]]:
    existing = await get_plan_node(session, user_id, str(operation.entity_uuid))

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
            if children and (
                not isinstance(policy, str) or policy not in {"cascade_children", "detach_children"}
            ):
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
        existing = await get_plan_node(session, user_id, str(operation.entity_uuid))
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
    existing = await get_plan_node(session, user_id, str(operation.entity_uuid))
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
