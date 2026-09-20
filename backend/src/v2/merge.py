"""Pure structural three-way merge policy for protocol 4.

The caller owns account-scoped snapshot reads and the transaction. This module
only transforms the supplied snapshots and request; it never writes revisions.
"""

from __future__ import annotations

from copy import deepcopy
from typing import Any, Optional

from pydantic import ValidationError

from src.v2.encoding import jsonable_utc, parse_json
from src.v2.errors import DomainError
from src.v2.schemas import (
    ActivityMetricLinkPayload,
    MetricPayload,
    PlanNodePayload,
    SyncOperationRequest,
)


MERGE_PATHS: dict[str, tuple[tuple[str, ...], ...]] = {
    "plan_node": tuple(
        tuple(path.split("."))
        for path in [
            "parent_uuid", "title", "description", "icon", "color_hex", "status",
            "visibility", "sort_order", "goal.start_date", "goal.due_date",
            "goal.target_cycles", "goal.failure_policy", "goal.evaluation_policy",
            "goal.manual_result", "activity.tracking_mode", "activity.is_countdown",
            "activity.recurrence_rule", "activity.completion_policy",
            "activity.target_value", "activity.target_unit", "activity.target_cycles",
            "activity.failure_policy", "activity.preferred_local_time", "activity.timezone",
            "activity.origin_assignment_id",
        ]
    ),
    "metric": tuple(
        (field,)
        for field in [
            "name", "description", "unit", "decimal_places", "aggregation_type",
            "target_direction", "target_value", "target_value_upper", "icon",
            "color_hex", "status",
        ]
    ),
    "activity_metric_link": tuple(
        (field,)
        for field in [
            "activity_uuid", "metric_uuid", "coefficient", "show_in_activity_detail",
            "prompt_on_complete", "is_active",
        ]
    ),
}
_MISSING = object()


def _get_path(payload: dict[str, Any], path: tuple[str, ...]) -> Any:
    value: Any = payload
    for part in path:
        if not isinstance(value, dict) or part not in value:
            return _MISSING
        value = value[part]
    return value


def _set_path(payload: dict[str, Any], path: tuple[str, ...], value: Any) -> None:
    target = payload
    for part in path[:-1]:
        child = target.get(part)
        if not isinstance(child, dict):
            child = {}
            target[part] = child
        target = child
    target[path[-1]] = deepcopy(value)


def _delete_path(payload: dict[str, Any], path: tuple[str, ...]) -> None:
    target: Any = payload
    for part in path[:-1]:
        if not isinstance(target, dict) or part not in target:
            return
        target = target[part]
    if isinstance(target, dict):
        target.pop(path[-1], None)


def _normalized_operation_payload(operation: SyncOperationRequest) -> tuple[dict[str, Any], dict[str, Any]]:
    models = {
        "plan_node": PlanNodePayload,
        "metric": MetricPayload,
        "activity_metric_link": ActivityMetricLinkPayload,
    }
    try:
        model = models[operation.entity_type].model_validate(operation.payload)
    except ValidationError as exc:
        raise DomainError("INVALID_PAYLOAD", str(exc)) from exc
    return (
        jsonable_utc(model.model_dump(mode="python", exclude_unset=False)),
        jsonable_utc(model.model_dump(mode="python", exclude_unset=True)),
    )


def _same_merge_projection(
    left: dict[str, Any],
    right: dict[str, Any],
    paths: tuple[tuple[str, ...], ...],
) -> bool:
    return all(_get_path(left, path) == _get_path(right, path) for path in paths)


def _inherit_omitted_recurrence_dates(
    local_full: dict[str, Any],
    local_set: dict[str, Any],
    base_payload: dict[str, Any],
) -> None:
    """Apply the existing same-type recurrence update rule to local intent.

    Optional dates omitted by a client retain their base values; explicit null
    clears them. Use the base, not the current server, so this does not merge
    concurrent edits within an otherwise atomic recurrence policy.
    """
    path = ("activity", "recurrence_rule")
    local_rule = _get_path(local_full, path)
    submitted_rule = _get_path(local_set, path)
    base_rule = _get_path(base_payload, path)
    if not all(isinstance(rule, dict) for rule in (local_rule, submitted_rule, base_rule)):
        return
    if local_rule.get("type") != base_rule.get("type"):
        return
    for field in ("start_date", "due_date"):
        if field in local_rule and field not in submitted_rule and field in base_rule:
            local_rule[field] = deepcopy(base_rule[field])


def merge_structural_payload(
    operation: SyncOperationRequest,
    *,
    current_revision: int,
    server_payload: dict[str, Any],
    base_snapshot_json: Optional[str],
) -> tuple[SyncOperationRequest, Optional[tuple[int, dict[str, Any]]]]:
    """Rebase a stale structural upsert, or return the existing no-op result.

    The caller must first establish that this is a mergeable stale upsert against
    a live entity. The base is the stored JSON for this account/entity/revision,
    or None if unavailable. Keeping normalization before base decoding preserves
    validation order and the original missing-base conflict representation.
    """
    paths = MERGE_PATHS[operation.entity_type]
    local_full, local_set = _normalized_operation_payload(operation)
    if base_snapshot_json is None:
        raise DomainError(
            "BASE_SNAPSHOT_UNAVAILABLE",
            "The edit base is no longer available for a safe merge",
            conflict=True,
            entity=server_payload,
            revision=current_revision,
            local_entity=local_full,
            conflict_kind="base_snapshot_unavailable",
        )

    base_payload = parse_json(base_snapshot_json)
    _inherit_omitted_recurrence_dates(local_full, local_set, base_payload)
    local_changes: set[tuple[str, ...]] = set()
    remote_changes: set[tuple[str, ...]] = set()
    for path in paths:
        # Presence is sparse, but values must use the same normalized defaults
        # as snapshots. Comparing sparse nested dictionaries fabricates edits.
        submitted = _get_path(local_set, path) is not _MISSING
        local_value = _get_path(local_full, path)
        base_value = _get_path(base_payload, path)
        server_value = _get_path(server_payload, path)
        if submitted and local_value != base_value:
            local_changes.add(path)
        if server_value != base_value:
            remote_changes.add(path)

    conflicts = sorted(
        path
        for path in local_changes & remote_changes
        if _get_path(local_full, path) != _get_path(server_payload, path)
    )
    if conflicts:
        raise DomainError(
            "REVISION_CONFLICT",
            "The same fields changed on another device",
            conflict=True,
            entity=server_payload,
            revision=current_revision,
            base_entity=base_payload,
            local_entity=local_full,
            conflicting_fields=[".".join(path) for path in conflicts],
            conflict_kind="overlapping_fields",
        )

    merged = deepcopy(local_full)
    for path in paths:
        if path in local_changes:
            continue
        server_value = _get_path(server_payload, path)
        if server_value is _MISSING:
            _delete_path(merged, path)
        else:
            _set_path(merged, path, server_value)

    if _same_merge_projection(merged, server_payload, paths):
        return operation, (current_revision, server_payload)
    return operation.model_copy(
        update={"base_revision": current_revision, "payload": merged}
    ), None
