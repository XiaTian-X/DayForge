"""Account-scoped authoritative entity snapshots shared by sync and timers.

These functions read through the caller's session and preserve protocol 4
payloads. Transaction ownership stays with the application service.
"""

from typing import Any, Optional

from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import col, select

from src.v2.encoding import jsonable_utc, parse_json
from src.v2.invariants import require_internal
from src.v2.models import (
    ActivityDetail,
    ActivityEvent,
    ActivityMetricLinkV2,
    DurationDayAllocation,
    GoalDetail,
    MetricObservation,
    PlanNode,
    TrackedMetric,
)
from src.v2.schemas import SyncOperationRequest


async def serialize_plan_node(session: AsyncSession, node: PlanNode) -> dict[str, Any]:
    parent_uuid = None
    if node.parent_node_id is not None:
        parent = await session.get(PlanNode, node.parent_node_id)
        parent_uuid = parent.public_id if parent else None

    result: dict[str, Any] = {
        "public_id": node.public_id,
        "revision": node.revision,
        "created_at": node.created_at,
        "updated_at": node.updated_at,
        "deleted_at": node.deleted_at,
        "node_kind": node.node_kind,
        "title": node.title,
        "description": node.description,
        "icon": node.icon,
        "color_hex": node.color_hex,
        "status": node.status,
        "visibility": node.visibility,
        "sort_order": node.sort_order,
        "parent_uuid": parent_uuid,
        "goal": None,
        "activity": None,
    }
    if node.node_kind == "goal":
        detail = await session.get(GoalDetail, node.id)
        if detail:
            result["goal"] = {
                "start_date": detail.start_date,
                "due_date": detail.due_date,
                "target_cycles": detail.target_cycles,
                "failure_policy": parse_json(detail.failure_policy_json),
                "evaluation_policy": parse_json(detail.evaluation_policy_json),
                "manual_result": detail.manual_result,
            }
    else:
        activity_detail = await session.get(ActivityDetail, node.id)
        if activity_detail:
            result["activity"] = {
                "tracking_mode": activity_detail.tracking_mode,
                "is_countdown": activity_detail.is_countdown,
                "recurrence_rule": parse_json(activity_detail.recurrence_rule_json),
                "completion_policy": activity_detail.completion_policy,
                "target_value": activity_detail.target_value,
                "target_unit": activity_detail.target_unit,
                "target_cycles": activity_detail.target_cycles,
                "failure_policy": parse_json(activity_detail.failure_policy_json),
                "preferred_local_time": activity_detail.preferred_local_time,
                "timezone": activity_detail.timezone,
                "origin_assignment_id": activity_detail.origin_assignment_id,
            }
    return jsonable_utc(result)


def serialize_activity_event(
    event: ActivityEvent, activity_uuid: str, revert_uuid: Optional[str]
) -> dict[str, Any]:
    return jsonable_utc(
        {
            "public_id": event.public_id,
            "revision": event.revision,
            "created_at": event.created_at,
            "updated_at": event.updated_at,
            "deleted_at": event.deleted_at,
            "activity_uuid": activity_uuid,
            "event_type": event.event_type,
            "value": event.value,
            "duration_seconds": event.duration_seconds,
            "duration_milliseconds": event.duration_milliseconds,
            "started_at": event.started_at,
            "ended_at": event.ended_at,
            "occurred_at": event.occurred_at,
            "local_date": event.local_date,
            "timezone": event.timezone,
            "note": event.note,
            "source_type": event.source_type,
            "source_device_id": event.source_device_public_id,
            "external_event_id": event.external_event_id,
            "reverts_event_uuid": revert_uuid,
            "metadata": parse_json(event.payload_json),
            "received_at": event.received_at,
        }
    )


async def serialize_activity_event_with_allocations(
    session: AsyncSession,
    event: ActivityEvent,
    activity_uuid: str,
    revert_uuid: Optional[str],
) -> dict[str, Any]:
    payload = serialize_activity_event(event, activity_uuid, revert_uuid)
    if event.event_type != "duration_session":
        return payload
    result = await session.execute(
        select(DurationDayAllocation)
        .where(DurationDayAllocation.activity_event_id == event.id)
        .order_by(col(DurationDayAllocation.local_date))
    )
    payload["day_allocations"] = [
        {
            "local_date": allocation.local_date.isoformat(),
            "timezone": allocation.timezone,
            "duration_milliseconds": allocation.duration_ms,
        }
        for allocation in result.scalars().all()
    ]
    return payload


def serialize_metric(metric: TrackedMetric) -> dict[str, Any]:
    return jsonable_utc(
        {
            "public_id": metric.public_id,
            "revision": metric.revision,
            "created_at": metric.created_at,
            "updated_at": metric.updated_at,
            "deleted_at": metric.deleted_at,
            "name": metric.name,
            "description": metric.description,
            "unit": metric.unit,
            "decimal_places": metric.decimal_places,
            "aggregation_type": metric.aggregation_type,
            "target_direction": metric.target_direction,
            "target_value": metric.target_value,
            "target_value_upper": metric.target_value_upper,
            "icon": metric.icon,
            "color_hex": metric.color_hex,
            "status": metric.status,
        }
    )


def serialize_observation(
    observation: MetricObservation, metric_uuid: str
) -> dict[str, Any]:
    return jsonable_utc(
        {
            "public_id": observation.public_id,
            "revision": observation.revision,
            "created_at": observation.created_at,
            "updated_at": observation.updated_at,
            "deleted_at": observation.deleted_at,
            "metric_uuid": metric_uuid,
            "value": observation.value,
            "unit": observation.unit,
            "occurred_at": observation.occurred_at,
            "local_date": observation.local_date,
            "timezone": observation.timezone,
            "note": observation.note,
            "source_type": observation.source_type,
            "source_device_id": observation.source_device_public_id,
            "external_event_id": observation.external_event_id,
            "metadata": parse_json(observation.payload_json),
            "received_at": observation.received_at,
        }
    )


def serialize_link(
    link: ActivityMetricLinkV2, activity_uuid: str, metric_uuid: str
) -> dict[str, Any]:
    return jsonable_utc(
        {
            "public_id": link.public_id,
            "revision": link.revision,
            "created_at": link.created_at,
            "updated_at": link.updated_at,
            "deleted_at": link.deleted_at,
            "activity_uuid": activity_uuid,
            "metric_uuid": metric_uuid,
            "coefficient": link.coefficient,
            "show_in_activity_detail": link.show_in_activity_detail,
            "prompt_on_complete": link.prompt_on_complete,
            "is_active": link.is_active,
        }
    )


async def current_entity_snapshot(
    session: AsyncSession,
    user_id: int,
    operation: SyncOperationRequest,
) -> tuple[Optional[int], Optional[dict[str, Any]]]:
    """Load the authoritative entity for a conflict response.

    Every conflict must be actionable by a sync client. Returning only an
    error code would be cached by operation-id idempotency and leave the
    client unable to advance or merge the server revision.
    """
    entity_uuid = str(operation.entity_uuid)
    if operation.entity_type == "plan_node":
        result = await session.execute(
            select(PlanNode)
            .where(
                col(PlanNode.owner_user_id) == user_id,
                col(PlanNode.public_id) == entity_uuid,
            )
            .execution_options(populate_existing=True)
        )
        entity = result.scalar_one_or_none()
        return (
            (entity.revision, await serialize_plan_node(session, entity))
            if entity
            else (None, None)
        )

    if operation.entity_type == "activity_event":
        event_result = await session.execute(
            select(ActivityEvent)
            .where(
                col(ActivityEvent.owner_user_id) == user_id,
                col(ActivityEvent.public_id) == entity_uuid,
            )
            .execution_options(populate_existing=True)
        )
        event = event_result.scalar_one_or_none()
        if event is None:
            return None, None
        activity = require_internal(
            await session.get(PlanNode, event.activity_node_id),
            "ActivityEvent.activity_node_id",
        )
        revert = (
            await session.get(ActivityEvent, event.reverts_event_id)
            if event.reverts_event_id
            else None
        )
        return event.revision, await serialize_activity_event_with_allocations(
            session,
            event,
            activity.public_id,
            revert.public_id if revert else None,
        )

    if operation.entity_type == "metric":
        metric_result = await session.execute(
            select(TrackedMetric)
            .where(
                col(TrackedMetric.owner_user_id) == user_id,
                col(TrackedMetric.public_id) == entity_uuid,
            )
            .execution_options(populate_existing=True)
        )
        metric = metric_result.scalar_one_or_none()
        return (metric.revision, serialize_metric(metric)) if metric else (None, None)

    if operation.entity_type == "metric_observation":
        observation_result = await session.execute(
            select(MetricObservation)
            .where(
                col(MetricObservation.owner_user_id) == user_id,
                col(MetricObservation.public_id) == entity_uuid,
            )
            .execution_options(populate_existing=True)
        )
        observation = observation_result.scalar_one_or_none()
        if observation is None:
            return None, None
        metric = require_internal(
            await session.get(TrackedMetric, observation.metric_id),
            "MetricObservation.metric_id",
        )
        return observation.revision, serialize_observation(
            observation, metric.public_id
        )

    if operation.entity_type == "activity_metric_link":
        link_result = await session.execute(
            select(ActivityMetricLinkV2)
            .where(
                col(ActivityMetricLinkV2.owner_user_id) == user_id,
                col(ActivityMetricLinkV2.public_id) == entity_uuid,
            )
            .execution_options(populate_existing=True)
        )
        link = link_result.scalar_one_or_none()
        if link is None:
            return None, None
        activity = require_internal(
            await session.get(PlanNode, link.activity_node_id),
            "ActivityMetricLinkV2.activity_node_id",
        )
        metric = require_internal(
            await session.get(TrackedMetric, link.metric_id),
            "ActivityMetricLinkV2.metric_id",
        )
        return link.revision, serialize_link(link, activity.public_id, metric.public_id)

    return None, None
