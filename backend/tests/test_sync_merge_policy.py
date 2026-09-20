"""The extracted merge policy is pure and keeps nested policy fields atomic."""

from copy import deepcopy

import pytest

from src.v2.encoding import canonical_json, jsonable_utc
from src.v2.errors import DomainError
from src.v2.merge import merge_structural_payload
from src.v2.schemas import PlanNodePayload, SyncOperationRequest
from tests.test_sync_v2 import activity_operation


def base_activity():
    operation = activity_operation(tracking_mode="count")
    operation["payload"]["activity"]["recurrence_rule"] = {
        "schema_version": 1,
        "type": "weekly",
        "interval": 1,
        "weekdays": [1],
    }
    operation["base_revision"] = 1
    request = SyncOperationRequest.model_validate(operation)
    snapshot = jsonable_utc(
        PlanNodePayload.model_validate(request.payload).model_dump()
    )
    return request, snapshot


def test_rebase_does_not_mutate_inputs_or_alias_nested_server_values():
    operation, base = base_activity()
    operation.payload["title"] = "Local title"
    server = deepcopy(base)
    server["activity"]["recurrence_rule"]["weekdays"] = [2, 4]
    original_request = operation.model_dump()
    original_server = deepcopy(server)
    original_base = deepcopy(base)

    prepared, no_op = merge_structural_payload(
        operation,
        current_revision=2,
        server_payload=server,
        base_snapshot_json=canonical_json(base),
    )
    assert no_op is None
    assert prepared.base_revision == 2
    assert prepared.operation_id == operation.operation_id
    assert prepared.payload["title"] == "Local title"
    assert prepared.payload["activity"]["recurrence_rule"]["weekdays"] == [2, 4]
    prepared.payload["activity"]["recurrence_rule"]["weekdays"].append(6)
    assert server == original_server
    assert base == original_base
    assert operation.model_dump() == original_request


def test_nested_recurrence_rule_is_one_conflict_field_not_a_recursive_dict_merge():
    operation, base = base_activity()
    operation.payload["activity"]["recurrence_rule"]["start_date"] = "2026-09-14"
    server = deepcopy(base)
    server["activity"]["recurrence_rule"]["weekdays"] = [2, 4]
    original_request = operation.model_dump()
    original_server = deepcopy(server)

    with pytest.raises(DomainError) as caught:
        merge_structural_payload(
            operation,
            current_revision=2,
            server_payload=server,
            base_snapshot_json=canonical_json(base),
        )
    error = caught.value
    assert error.code == "REVISION_CONFLICT"
    assert error.conflicting_fields == ["activity.recurrence_rule"]
    assert error.base_entity["activity"]["recurrence_rule"]["weekdays"] == [1]
    assert (
        error.local_entity["activity"]["recurrence_rule"]["start_date"] == "2026-09-14"
    )
    assert error.entity["activity"]["recurrence_rule"]["weekdays"] == [2, 4]
    assert operation.model_dump() == original_request
    assert server == original_server


def test_inherited_date_does_not_mutate_the_sparse_request_or_base_snapshot():
    operation, base = base_activity()
    base["activity"]["recurrence_rule"]["start_date"] = "2026-09-01"
    operation.payload["title"] = "Local title"
    server = deepcopy(base)
    server["description"] = "Remote description"
    original_request = operation.model_dump()
    original_base = deepcopy(base)
    original_server = deepcopy(server)

    prepared, no_op = merge_structural_payload(
        operation,
        current_revision=2,
        server_payload=server,
        base_snapshot_json=canonical_json(base),
    )
    assert no_op is None
    assert prepared.payload["activity"]["recurrence_rule"]["start_date"] == "2026-09-01"
    assert prepared.payload["title"] == "Local title"
    assert prepared.payload["description"] == "Remote description"
    assert "start_date" not in operation.payload["activity"]["recurrence_rule"]
    assert operation.model_dump() == original_request
    assert base == original_base
    assert server == original_server
