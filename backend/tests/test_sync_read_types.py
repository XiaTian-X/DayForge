"""Runtime contracts retained while typing persisted sync read boundaries."""

from datetime import UTC, datetime
from unittest.mock import AsyncMock, Mock
from uuid import UUID

import pytest
from pydantic import ValidationError

from src.v2.entity_snapshots import current_entity_snapshot
from src.v2.invariants import require_internal
from src.v2.models import (
    ActivityEvent,
    ActivityMetricLinkV2,
    MetricObservation,
    PlanNode,
    SyncChange,
)
from src.v2.read_service import _change_response
from src.v2.schemas import SyncOperationRequest


@pytest.mark.parametrize(
    "field,value,error",
    [
        ("sequence", None, "int_type"),
        ("entity_uuid", "bad", "uuid_parsing"),
        ("operation", "bad", "literal_error"),
    ],
)
def test_change_response_retains_invalid_stored_value_rejection(field, value, error):
    row = SyncChange(
        sequence=1,
        recipient_user_id=1,
        entity_type="metric",
        entity_uuid=str(UUID(int=1)),
        operation="upsert",
        revision=1,
        payload_json="{}",
    )
    setattr(row, field, value)
    with pytest.raises(ValidationError) as caught:
        _change_response(row, None)
    assert caught.value.errors()[0]["loc"] == (field,)
    assert caught.value.errors()[0]["type"] == error


def test_change_response_normalizes_public_uuid_and_preserves_payload():
    row = SyncChange(
        sequence=7,
        recipient_user_id=1,
        entity_type="metric",
        entity_uuid="ABCDEF12-3456-4789-ABCD-123456789012",
        operation="delete",
        revision=3,
        payload_json='{"deleted_at":"2026-09-20T00:00:00Z"}',
        changed_at=datetime(2026, 9, 20, tzinfo=UTC),
    )
    response = _change_response(row, str(UUID(int=2)))
    assert response.entity_uuid == UUID("abcdef12-3456-4789-abcd-123456789012")
    assert response.origin_device_id == UUID(int=2)
    assert (
        response.sequence == 7
        and response.revision == 3
        and response.operation == "delete"
    )
    assert response.payload == {"deleted_at": "2026-09-20T00:00:00Z"}
    assert _change_response(row, None).origin_device_id is None


@pytest.mark.parametrize("value", [0, False, "", [], {}])
def test_internal_guard_preserves_non_null_values_by_identity(value):
    assert require_internal(value, "probe") is value


def test_internal_guard_rejects_missing_value():
    with pytest.raises(RuntimeError, match="Required internal value"):
        require_internal(None, "probe")


@pytest.mark.parametrize(
    "entity_type,entity,related",
    [
        ("activity_event", ActivityEvent(activity_node_id=1), [None]),
        ("metric_observation", MetricObservation(metric_id=1), [None]),
        (
            "activity_metric_link",
            ActivityMetricLinkV2(activity_node_id=1, metric_id=2),
            [None],
        ),
        (
            "activity_metric_link",
            ActivityMetricLinkV2(activity_node_id=1, metric_id=2),
            [PlanNode(id=1), None],
        ),
    ],
)
async def test_missing_snapshot_reference_is_internal_failure_not_partial_success(
    entity_type, entity, related
):
    session = AsyncMock()
    session.execute.return_value = Mock()
    session.execute.return_value.scalar_one_or_none.return_value = entity
    session.get.side_effect = related
    operation = SyncOperationRequest(
        operation_id=UUID(int=10),
        entity_uuid=UUID(int=20),
        entity_type=entity_type,
        action="upsert",
        payload={},
    )
    with pytest.raises(RuntimeError, match="Required internal value"):
        await current_entity_snapshot(session, 1, operation)
    session.commit.assert_not_awaited()
