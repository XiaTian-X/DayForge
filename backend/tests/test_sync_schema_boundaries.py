"""Preserve validation behavior while tightening sync contract type coverage."""

from datetime import datetime, timedelta, timezone
from uuid import UUID

import pytest
from pydantic import ValidationError

from src.v2.schemas import (
    ActivityEventPayload,
    MetricObservationPayload,
    PlanNodePayload,
    TimerCommandRequest,
    require_aware_utc,
)


ENTITY_ID = UUID("11111111-1111-4111-8111-111111111111")


@pytest.mark.parametrize("manual_result", [None, "succeeded", "failed"])
@pytest.mark.parametrize(
    "status", ["active", "paused", "completed", "failed", "archived"]
)
def test_goal_status_and_manual_result_matrix(manual_result, status):
    payload = {
        "node_kind": "goal",
        "title": "Goal",
        "status": status,
        "goal": {"manual_result": manual_result},
    }
    valid = (manual_result, status) in {
        (None, "active"),
        (None, "archived"),
        ("succeeded", "completed"),
        ("failed", "failed"),
    }
    if valid:
        model = PlanNodePayload.model_validate(payload)
        assert model.status == status
        assert model.goal.manual_result == manual_result
    else:
        message = (
            "goal statuses completed and failed require a manual result"
            if manual_result is None
            else "goal status must match its manual result"
        )
        with pytest.raises(ValidationError, match=message):
            PlanNodePayload.model_validate(payload)


def duration_payload():
    return {
        "activity_uuid": ENTITY_ID,
        "event_type": "duration_session",
        "duration_seconds": 60,
        "started_at": "2026-09-19T23:59:30.123456+08:00",
        "ended_at": "2026-09-20T00:00:30.123456+08:00",
        "occurred_at": "2026-09-20T00:00:30.123456+08:00",
        "local_date": "2026-09-19",
        "timezone": "Asia/Shanghai",
    }


def test_cross_midnight_duration_keeps_start_date_and_normalizes_utc():
    event = ActivityEventPayload.model_validate(duration_payload())
    assert event.duration_milliseconds == 60_000
    wire = event.model_dump(mode="json")
    assert wire["started_at"] == "2026-09-19T15:59:30.123456Z"
    assert wire["ended_at"] == wire["occurred_at"] == "2026-09-19T16:00:30.123456Z"
    assert wire["local_date"] == "2026-09-19"
    with pytest.raises(ValidationError, match="local_date does not match"):
        ActivityEventPayload.model_validate(
            {**duration_payload(), "local_date": "2026-09-20"}
        )


@pytest.mark.parametrize("field", ["started_at", "ended_at", "duration_seconds"])
@pytest.mark.parametrize("explicit_null", [False, True])
def test_incomplete_duration_is_rejected_before_business_date(field, explicit_null):
    payload = {**duration_payload(), "local_date": "2026-09-20"}
    if explicit_null:
        payload[field] = None
    else:
        del payload[field]
    with pytest.raises(
        ValidationError,
        match="duration_session requires duration_seconds, started_at and ended_at",
    ):
        ActivityEventPayload.model_validate(payload)


def test_duration_interval_error_precedes_business_date_error():
    with pytest.raises(
        ValidationError, match="active duration must not exceed the wall-clock interval"
    ):
        ActivityEventPayload.model_validate(
            {**duration_payload(), "duration_seconds": 61, "local_date": "2026-09-20"}
        )


def test_non_duration_event_uses_occurrence_date_even_with_optional_start():
    payload = {
        **duration_payload(),
        "event_type": "check_in",
        "local_date": "2026-09-20",
    }
    assert (
        ActivityEventPayload.model_validate(payload).local_date.isoformat()
        == "2026-09-20"
    )
    with pytest.raises(ValidationError, match="local_date does not match"):
        ActivityEventPayload.model_validate({**payload, "local_date": "2026-09-19"})


def test_utc_helper_preserves_optional_none_and_rejects_naive_timestamp():
    assert require_aware_utc(None) is None
    instant = datetime(
        2026, 9, 20, 0, 0, 30, 123456, tzinfo=timezone(timedelta(hours=8))
    )
    assert require_aware_utc(instant) == instant
    assert require_aware_utc(instant).tzinfo is timezone.utc
    with pytest.raises(
        ValueError, match="timestamp must include a UTC offset or Z suffix"
    ):
        require_aware_utc(instant.replace(tzinfo=None))


@pytest.mark.parametrize(
    "model,payload",
    [
        (
            MetricObservationPayload,
            {
                "metric_uuid": ENTITY_ID,
                "value": 1,
                "unit": "kg",
                "local_date": "2026-09-20",
                "timezone": "Asia/Shanghai",
            },
        ),
        (
            TimerCommandRequest,
            {
                "command_id": ENTITY_ID,
                "session_id": ENTITY_ID,
                "sequence": 2,
                "command_type": "pause",
            },
        ),
    ],
)
def test_required_timestamp_models_keep_utc_precision_and_reject_null(model, payload):
    value = model.model_validate(
        {**payload, "occurred_at": "2026-09-20T00:00:30.123456+08:00"}
    )
    assert value.model_dump(mode="json")["occurred_at"] == "2026-09-19T16:00:30.123456Z"
    for invalid in (None, "2026-09-20T00:00:30.123456"):
        with pytest.raises(ValidationError) as error:
            model.model_validate({**payload, "occurred_at": invalid})
        assert error.value.errors()[0]["loc"] == ("occurred_at",)
