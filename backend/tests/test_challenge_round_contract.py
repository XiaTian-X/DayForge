"""Shared vectors for the staged D-018 contract, not authenticated storage/API tests."""

from copy import deepcopy
import json
from pathlib import Path

import pytest
from pydantic import BaseModel, ValidationError

from src.v2.challenge_round import (
    ChallengeRestartIntent,
    ChallengeRoundHead,
    ChallengeRoundRecord,
    ChallengeTransitionError,
    advance_challenge,
    apply_challenge_record,
    initial_round_head,
    initial_round_uuid,
    rebuild_challenge_history,
)
from src.v2.next_sync_contract import NextSyncPushRequest
from src.v2.schemas import SyncOperationRequest

FIXTURE = json.loads(
    (
        Path(__file__).resolve().parents[2] / "contracts/next/challenge-rounds.json"
    ).read_text()
)


def head(index):
    return ChallengeRoundHead.model_validate(FIXTURE["heads"][index])


def intent(index):
    return ChallengeRestartIntent.model_validate(FIXTURE["intents"][index])


def record(index):
    return ChallengeRoundRecord.model_validate(FIXTURE["records"][index])


@pytest.mark.parametrize("case", FIXTURE["initial_identities"])
def test_initial_identity_is_stable_and_not_current_pointer(case):
    assert initial_round_uuid(case["activity_uuid"]) == case["round_uuid"]
    assert initial_round_head(case["activity_uuid"]).round_uuid == case["round_uuid"]
    assert initial_round_uuid(case["activity_uuid"]) == case["round_uuid"]


@pytest.mark.parametrize(
    "kind,model",
    [
        ("heads", ChallengeRoundHead),
        ("intents", ChallengeRestartIntent),
        ("records", ChallengeRoundRecord),
    ],
)
def test_all_values_round_trip_without_claiming_storage_authority(kind, model):
    for raw in FIXTURE[kind]:
        value = model.model_validate(raw)
        assert value.model_dump(mode="json") == raw
        assert model.model_validate_json(value.model_dump_json()) == value


@pytest.mark.parametrize("case", FIXTURE["transitions"], ids=lambda case: case["name"])
def test_restart_preflight_preserves_inputs_and_requires_exact_head_and_plan(case):
    current, requested = head(case["head"]), intent(case["intent"])
    before = current.model_dump_json(), requested.model_dump_json()
    kwargs = dict(
        actual_plan_revision=case["plan_revision"],
        has_unfinished_timer=case.get("unfinished_timer", False),
        deleted=case.get("deleted", False),
    )
    if "error" in case:
        with pytest.raises(ChallengeTransitionError, match="^" + case["error"] + "$"):
            advance_challenge(current, requested, **kwargs)
    else:
        assert advance_challenge(current, requested, **kwargs) == head(case["result"])
    assert before == (current.model_dump_json(), requested.model_dump_json())


@pytest.mark.parametrize("case", FIXTURE["deliveries"], ids=lambda case: case["name"])
def test_delta_does_not_roll_back_or_skip_causal_steps(case):
    current, incoming = head(case["head"]), record(case["record"])
    before = current.model_dump_json(), incoming.model_dump_json()
    if "error" in case:
        with pytest.raises(ChallengeTransitionError, match="^" + case["error"] + "$"):
            apply_challenge_record(current, incoming)
    else:
        assert apply_challenge_record(current, incoming) == head(case["result"])
    assert before == (current.model_dump_json(), incoming.model_dump_json())


@pytest.mark.parametrize("case", FIXTURE["histories"], ids=lambda case: case["name"])
def test_complete_restore_chain_not_upload_or_timestamp_order(case):
    records = [record(index) for index in case["records"]]
    before = [item.model_dump_json() for item in records]
    activity = head(0).activity_uuid
    if "error" in case:
        with pytest.raises(ChallengeTransitionError, match="^" + case["error"] + "$"):
            rebuild_challenge_history(activity, records)
    else:
        assert rebuild_challenge_history(activity, records) == head(case["result"])
    assert before == [item.model_dump_json() for item in records]


@pytest.mark.parametrize("case", FIXTURE["invalid"])
def test_strict_primitive_identity_and_exact_record_binding(case):
    models: dict[str, type[BaseModel]] = {
        "head": ChallengeRoundHead,
        "intent": ChallengeRestartIntent,
        "record": ChallengeRoundRecord,
    }
    roots = {"head": "heads", "intent": "intents", "record": "records"}
    raw = deepcopy(FIXTURE[roots[case["kind"]]][case["base"]])
    cursor = raw
    for key in case["path"][:-1]:
        cursor = cursor[key]
    cursor[case["path"][-1]] = case["value"]
    with pytest.raises(ValidationError):
        models[case["kind"]].model_validate(raw)


def test_restore_cannot_cross_activity_or_recover_from_current_head_alone():
    with pytest.raises(ChallengeTransitionError, match="^CHALLENGE_ACTIVITY_MISMATCH$"):
        rebuild_challenge_history(head(5).activity_uuid, [record(0), record(1)])
    with pytest.raises(ChallengeTransitionError, match="^CHALLENGE_CHAIN_INCOMPLETE$"):
        rebuild_challenge_history(head(0).activity_uuid, [record(3)])


@pytest.mark.parametrize(
    "value",
    [
        "11111111111141118111111111111111",
        "{11111111-1111-4111-8111-111111111111}",
        "invalid",
        None,
    ],
)
def test_baseline_factory_never_normalizes_noncanonical_source(value):
    with pytest.raises(ValueError):
        initial_round_uuid(value)


def test_contract_values_do_not_mount_a_new_sync_operation_or_offer_write_authority():
    raw = {
        "operation_id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        "entity_type": "challenge_round",
        "entity_uuid": intent(0).round_uuid,
        "action": "upsert",
        "payload": intent(0).model_dump(mode="json"),
    }
    with pytest.raises(ValidationError):
        SyncOperationRequest.model_validate(raw)
    with pytest.raises(ValidationError):
        NextSyncPushRequest.model_validate(
            {"device_id": "dddddddd-dddd-4ddd-8ddd-dddddddddddd", "operations": [raw]}
        )


def test_python_bool_is_not_a_stored_integer_revision():
    raw = FIXTURE["intents"][0] | {"expected_plan_revision": 1}
    requested = ChallengeRestartIntent.model_validate(raw)
    with pytest.raises(ChallengeTransitionError, match="^CHALLENGE_PLAN_CHANGED$"):
        advance_challenge(head(0), requested, actual_plan_revision=True)
