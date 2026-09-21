"""Goal and activity structure mutations within the caller's sync transaction.

Parent resolution and title validation use the authenticated account scope.
Deletion cascades/detaches children and journals their revisions in the same
savepoint as the parent; this module does not own sessions or commits.
"""

from __future__ import annotations

from typing import Any, Optional, cast

from pydantic import ValidationError
from sqlalchemy import update
from sqlalchemy.engine import CursorResult
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import col, select

from src.v2.change_log import append_change
from src.v2.encoding import canonical_json, parse_json
from src.v2.entity_snapshots import serialize_plan_node
from src.v2.errors import DomainError
from src.v2.invariants import require_internal
from src.v2.models import ActivityDetail, ClientDevice, GoalDetail, PlanNode, utc_now
from src.v2.schemas import GoalPayload, PlanNodePayload, SyncOperationRequest


async def get_plan_node(
    session: AsyncSession,
    owner_user_id: int,
    public_id: str,
    *,
    include_deleted: bool = True,
) -> Optional[PlanNode]:
    query = select(PlanNode).where(
        col(PlanNode.owner_user_id) == owner_user_id,
        col(PlanNode.public_id) == public_id,
    )
    if not include_deleted:
        query = query.where(col(PlanNode.deleted_at).is_(None))
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
        raise DomainError(
            "INVALID_PARENT", "An activity parent must be a top-level goal"
        )
    return parent


async def _ensure_unique_plan_node_title(
    session: AsyncSession,
    user_id: int,
    title: str,
    existing_id: Optional[int],
) -> None:
    statement = select(col(PlanNode.id)).where(
        col(PlanNode.owner_user_id) == user_id,
        col(PlanNode.title) == title,
        col(PlanNode.deleted_at).is_(None),
    )
    if existing_id is not None:
        statement = statement.where(col(PlanNode.id) != existing_id)
    if (await session.execute(statement.limit(1))).scalar_one_or_none() is not None:
        raise DomainError(
            "DUPLICATE_TITLE", "An active habit or goal with this title already exists"
        )


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
                    col(PlanNode.owner_user_id) == user_id,
                    col(PlanNode.parent_node_id) == existing.id,
                    col(PlanNode.deleted_at).is_(None),
                )
            )
            children = list(children_result.scalars().all())
            policy = operation.payload.get("child_policy")
            if children and (
                not isinstance(policy, str)
                or policy not in {"cascade_children", "detach_children"}
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
                col(PlanNode.id) == existing.id,
                col(PlanNode.owner_user_id) == user_id,
                col(PlanNode.revision) == operation.base_revision,
                col(PlanNode.deleted_at).is_(None),
            )
            .values(
                revision=col(PlanNode.revision) + 1,
                updated_at=now,
                deleted_at=now,
            )
        )
        # Single non-bulk UPDATE returns CursorResult, unlike generic execute.
        if cast(CursorResult, result).rowcount != 1:
            raise DomainError(
                "REVISION_CONFLICT", "Plan node changed concurrently", conflict=True
            )
        await session.flush()
        existing = require_internal(
            await get_plan_node(session, user_id, str(operation.entity_uuid)),
            "updated PlanNode",
        )
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
            raise DomainError(
                "ENTITY_NOT_FOUND", "Cannot update a plan node that does not exist"
            )
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
            created_goal = require_internal(payload.goal, "validated goal payload")
            session.add(
                GoalDetail(
                    node_id=node.id,
                    start_date=created_goal.start_date,
                    due_date=created_goal.due_date,
                    target_cycles=created_goal.target_cycles,
                    failure_policy_json=canonical_json(
                        created_goal.failure_policy.model_dump(mode="json")
                    ),
                    evaluation_policy_json=canonical_json(
                        created_goal.evaluation_policy.model_dump(mode="json")
                    ),
                    manual_result=created_goal.manual_result,
                )
            )
        else:
            created_activity = require_internal(
                payload.activity, "validated activity payload"
            )
            session.add(
                ActivityDetail(
                    node_id=node.id,
                    tracking_mode=created_activity.tracking_mode,
                    is_countdown=created_activity.is_countdown,
                    recurrence_rule_json=canonical_json(
                        created_activity.recurrence_rule.model_dump(mode="json")
                    ),
                    completion_policy=created_activity.completion_policy,
                    target_value=created_activity.target_value,
                    target_unit=created_activity.target_unit,
                    target_cycles=created_activity.target_cycles,
                    failure_policy_json=canonical_json(
                        created_activity.failure_policy.model_dump(mode="json")
                    ),
                    preferred_local_time=created_activity.preferred_local_time,
                    timezone=created_activity.timezone,
                    origin_assignment_id=str(created_activity.origin_assignment_id)
                    if created_activity.origin_assignment_id
                    else None,
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

    goal_payload = None
    if existing.node_kind == "goal":
        previous_goal = require_internal(
            await session.get(GoalDetail, existing.id), "GoalDetail"
        )
        input_goal = require_internal(payload.goal, "validated goal payload")
        goal_values = input_goal.model_dump()
        # Validate the actual resulting range, not just the sparse input. An
        # omitted endpoint is retained; explicit null still clears it.
        for field in ("start_date", "due_date"):
            if field not in input_goal.model_fields_set:
                goal_values[field] = getattr(previous_goal, field)
        try:
            goal_payload = GoalPayload.model_validate(goal_values)
        except ValidationError as exc:
            raise DomainError("INVALID_PAYLOAD", str(exc)) from exc

    now = utc_now()
    node_values = {
        "parent_node_id": parent.id if parent else None,
        "title": payload.title,
        "description": payload.description,
        "icon": payload.icon,
        "color_hex": payload.color_hex,
        "status": payload.status,
        "visibility": payload.visibility,
        "revision": col(PlanNode.revision) + 1,
        "updated_at": now,
    }
    if "sort_order" in payload.model_fields_set:
        node_values["sort_order"] = payload.sort_order
    result = await session.execute(
        update(PlanNode)
        .where(
            col(PlanNode.id) == existing.id,
            col(PlanNode.owner_user_id) == user_id,
            col(PlanNode.revision) == operation.base_revision,
            col(PlanNode.deleted_at).is_(None),
        )
        .values(**node_values)
    )
    # Preserve affected-row conflict detection without introducing RETURNING.
    if cast(CursorResult, result).rowcount != 1:
        raise DomainError(
            "REVISION_CONFLICT", "Plan node changed concurrently", conflict=True
        )

    if existing.node_kind == "goal":
        goal_payload = require_internal(goal_payload, "validated merged goal payload")
        stored_goal = require_internal(
            await session.get(GoalDetail, existing.id), "GoalDetail"
        )
        stored_goal.start_date = goal_payload.start_date
        stored_goal.due_date = goal_payload.due_date
        stored_goal.target_cycles = goal_payload.target_cycles
        stored_goal.failure_policy_json = canonical_json(
            goal_payload.failure_policy.model_dump(mode="json")
        )
        stored_goal.evaluation_policy_json = canonical_json(
            goal_payload.evaluation_policy.model_dump(mode="json")
        )
        stored_goal.manual_result = goal_payload.manual_result
    else:
        stored_activity = require_internal(
            await session.get(ActivityDetail, existing.id), "ActivityDetail"
        )
        activity_payload = require_internal(
            payload.activity, "validated activity payload"
        )
        stored_activity.tracking_mode = activity_payload.tracking_mode
        stored_activity.is_countdown = activity_payload.is_countdown
        recurrence_rule = activity_payload.recurrence_rule.model_dump(mode="json")
        previous_recurrence_rule = parse_json(stored_activity.recurrence_rule_json)
        if recurrence_rule.get("type") == previous_recurrence_rule.get("type"):
            for optional_date in ("start_date", "due_date"):
                if (
                    optional_date
                    not in activity_payload.recurrence_rule.model_fields_set
                    and optional_date in previous_recurrence_rule
                ):
                    recurrence_rule[optional_date] = previous_recurrence_rule[
                        optional_date
                    ]
        stored_activity.recurrence_rule_json = canonical_json(recurrence_rule)
        stored_activity.completion_policy = activity_payload.completion_policy
        stored_activity.target_value = activity_payload.target_value
        stored_activity.target_unit = activity_payload.target_unit
        stored_activity.target_cycles = activity_payload.target_cycles
        stored_activity.failure_policy_json = canonical_json(
            activity_payload.failure_policy.model_dump(mode="json")
        )
        stored_activity.preferred_local_time = activity_payload.preferred_local_time
        stored_activity.timezone = activity_payload.timezone
        if "origin_assignment_id" in activity_payload.model_fields_set:
            stored_activity.origin_assignment_id = (
                str(activity_payload.origin_assignment_id)
                if activity_payload.origin_assignment_id
                else None
            )
    await session.flush()
    existing = require_internal(
        await get_plan_node(session, user_id, str(operation.entity_uuid)),
        "updated PlanNode",
    )
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
