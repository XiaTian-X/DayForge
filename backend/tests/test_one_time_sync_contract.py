"""Forward wire/merge rules, not a claim that the v4 HTTP service supports them."""

from copy import deepcopy
import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from src.v2.one_time import OneTimeState, OneTimeTransitionError
from src.v2.one_time_sync import (
    NextActivityEventPayload,
    OneTimeEventProof,
    OneTimeProjection,
    PendingOneTimeIntent,
    merge_one_time_projection,
    project_pending_one_time,
    rebuild_one_time_history,
    validate_one_time_binding,
)
from src.v2.schemas import ActivityEventPayload, SyncOperationRequest


FIXTURE = json.loads(
    (
        Path(__file__).resolve().parents[2] / "contracts/next/one-time-sync.json"
    ).read_text()
)


def projection(index, *, other=False):
    return OneTimeProjection(
        activity_uuid=FIXTURE["other_activity_uuid" if other else "activity_uuid"],
        state=OneTimeState.model_validate(FIXTURE["states"][index]),
    )


@pytest.mark.parametrize("case", FIXTURE["merges"], ids=lambda case: case["name"])
def test_monotonic_projection(case):
    current = projection(case["current"])
    incoming = projection(case["incoming"], other=case.get("other_activity", False))
    before = (current.model_dump_json(), incoming.model_dump_json())
    if "error" in case:
        with pytest.raises(OneTimeTransitionError, match="^" + case["error"] + "$"):
            merge_one_time_projection(
                current, incoming, deleted=case.get("deleted", False)
            )
    else:
        assert merge_one_time_projection(current, incoming) == projection(
            case["result"]
        )
    assert before == (current.model_dump_json(), incoming.model_dump_json())


@pytest.mark.parametrize("case", FIXTURE["histories"], ids=lambda case: case["name"])
def test_complete_history_is_verified_before_projection_activation(case):
    events = [
        OneTimeEventProof.model_validate(FIXTURE["proofs"][i]) for i in case["events"]
    ]
    before = [event.model_dump_json() for event in events]
    checkpoint = projection(case["checkpoint"], other=case.get("other_activity", False))
    if "error" in case:
        with pytest.raises(OneTimeTransitionError, match="^" + case["error"] + "$"):
            rebuild_one_time_history(checkpoint, events)
    else:
        assert rebuild_one_time_history(checkpoint, events) == projection(
            case["result"]
        )
    assert before == [event.model_dump_json() for event in events]


@pytest.mark.parametrize("case", FIXTURE["queues"], ids=lambda case: case["name"])
def test_pending_chain_never_rewrites_or_acknowledges_operations(case):
    state = projection(case["confirmed"]).state
    pending = [
        PendingOneTimeIntent.model_validate(FIXTURE["pending"][i])
        for i in case["pending"]
    ]
    before = [item.model_dump_json() for item in pending]
    if "error" in case:
        with pytest.raises(OneTimeTransitionError, match="^" + case["error"] + "$"):
            project_pending_one_time(
                state, pending, rejected_operation_id=case["rejected"]
            )
    else:
        result = project_pending_one_time(
            state, pending, rejected_operation_id=case["rejected"]
        )
        assert result.model_dump(mode="json") == case["result"]
    assert before == [item.model_dump_json() for item in pending]


@pytest.mark.parametrize(
    "case", FIXTURE["invalid_proofs"], ids=lambda case: case["name"]
)
def test_invalid_snapshot_proof(case):
    raw = deepcopy(FIXTURE["proofs"][case["proof"]])
    cursor = raw
    for key in case["path"][:-1]:
        cursor = cursor[key]
    cursor[case["path"][-1]] = case["value"]
    with pytest.raises(ValidationError):
        OneTimeEventProof.model_validate(raw)


def test_request_binds_operation_identity_and_preserves_existing_time_rules():
    operation = SyncOperationRequest.model_validate(FIXTURE["request"])
    payload = NextActivityEventPayload.model_validate(operation.payload)
    validate_one_time_binding(
        entity_uuid=str(operation.entity_uuid),
        event_type=payload.event_type,
        reverts_event_uuid=None,
        intent=payload.one_time,
        completion_policy="one_and_done",
    )
    assert payload.occurred_at.isoformat() == "2026-09-22T16:00:01+00:00"
    assert payload.local_date.isoformat() == "2026-09-23"
    assert payload.timezone == "Asia/Shanghai"
    assert payload.metadata == {}
    # Existing v4 still rejects the new field; no accidental feature activation.
    with pytest.raises(ValidationError):
        ActivityEventPayload.model_validate(operation.payload)
    for change in (
        {"local_date": "2026-09-22"},
        {"occurred_at": "2026-09-22T16:00:01"},
        {"source_type": "automation", "external_event_id": None},
        {"duration_seconds": 60},
        {"event_type": "count_snapshot", "value": 1},
        {"one_time_state_after": FIXTURE["states"][1]},
    ):
        with pytest.raises(ValidationError):
            NextActivityEventPayload.model_validate({**operation.payload, **change})


def test_binding_cannot_be_bypassed_by_metadata_or_stored_policy():
    payload = NextActivityEventPayload.model_validate(FIXTURE["request"]["payload"])
    assert payload.one_time is not None
    arguments = dict(
        entity_uuid=FIXTURE["request"]["entity_uuid"],
        event_type="check_in",
        reverts_event_uuid=None,
        intent=payload.one_time,
        completion_policy="one_and_done",
    )
    for change in (
        {"intent": None},
        {"completion_policy": "recurring"},
        {"completion_policy": "unknown"},
        {"entity_uuid": FIXTURE["other_activity_uuid"]},
        {"event_type": "duration_session"},
        {"reverts_event_uuid": FIXTURE["other_activity_uuid"]},
    ):
        with pytest.raises(OneTimeTransitionError, match="INVALID_PAYLOAD"):
            validate_one_time_binding(**{**arguments, **change})
    ordinary = {
        **FIXTURE["request"]["payload"],
        "one_time": None,
        "metadata": {"one_time": payload.one_time.model_dump()},
    }
    parsed = NextActivityEventPayload.model_validate(ordinary)
    assert parsed.one_time is None
    validate_one_time_binding(
        **{**arguments, "intent": None, "completion_policy": "recurring"}
    )
    with pytest.raises(OneTimeTransitionError, match="INVALID_PAYLOAD"):
        validate_one_time_binding(**{**arguments, "intent": parsed.one_time})


def test_valid_undo_proof_keeps_root_target_and_implied_before_state_consistent():
    for raw in FIXTURE["proofs"]:
        assert OneTimeEventProof.model_validate(raw).model_dump(mode="json") == raw
    bad = deepcopy(FIXTURE["proofs"][1])
    bad["one_time"]["reverts_event_uuid"] = FIXTURE["proofs"][2]["public_id"]
    bad["reverts_event_uuid"] = bad["one_time"]["reverts_event_uuid"]
    with pytest.raises(ValidationError):
        OneTimeEventProof.model_validate(bad)


def test_duplicate_event_under_different_operation_and_wrong_causal_head_are_rejected():
    invalid_action = deepcopy(FIXTURE["pending"][0])
    invalid_action["intent"]["action"] = "undo"
    invalid_action["intent"]["reverts_event_uuid"] = FIXTURE["proofs"][2]["public_id"]
    with pytest.raises(OneTimeTransitionError, match="INVALID_PENDING_CHAIN"):
        project_pending_one_time(
            projection(0).state, [PendingOneTimeIntent.model_validate(invalid_action)]
        )
    pending = deepcopy(FIXTURE["pending"])
    pending[1]["intent"] = pending[0]["intent"]
    with pytest.raises(OneTimeTransitionError, match="INVALID_PENDING_CHAIN"):
        project_pending_one_time(
            projection(0).state,
            [PendingOneTimeIntent.model_validate(p) for p in pending],
        )
    wrong_branch = deepcopy(FIXTURE["proofs"][1])
    other_head = FIXTURE["states"][4]["head_event_uuid"]
    wrong_branch["reverts_event_uuid"] = other_head
    wrong_branch["one_time"]["reverts_event_uuid"] = other_head
    wrong_branch["one_time"]["expected_head_event_uuid"] = other_head
    with pytest.raises(OneTimeTransitionError, match="TASK_STATE_CONFLICT"):
        rebuild_one_time_history(
            projection(2),
            [
                OneTimeEventProof.model_validate(FIXTURE["proofs"][0]),
                OneTimeEventProof.model_validate(wrong_branch),
            ],
        )
