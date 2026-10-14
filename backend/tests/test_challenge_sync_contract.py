"""Shared wire vectors, not owner/admission/CAS or actual HTTP proof."""

from copy import deepcopy
import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from src.v2.challenge_sync_contract import (
    ChallengeMetadata,
    ChallengeRestartOperation,
    RoundSyncPushRequest,
    RoundTimerCommandBatchRequest,
)
from src.v2.challenge_receipts import receipt_context, validate_receipt_context
from src.v2.encoding import operation_hash, timer_command_hash
from src.v2.errors import DomainError
from src.v2.next_sync_contract import NextSyncPushRequest
from src.v2.replica_context import ReplicaIdentity
from src.v2.schemas import SyncOperationRequest, TimerCommandRequest

FIXTURES = Path(__file__).resolve().parents[2] / "contracts" / "next"
WIRE = json.loads((FIXTURES / "challenge-sync.json").read_text())
ROUNDS = json.loads((FIXTURES / "challenge-rounds.json").read_text())


@pytest.mark.parametrize("key", ["restart_request", "fact_request", "timer_request"])
def test_shared_requests_roundtrip_preserve_original_objects_and_captured_hash(key):
    body = deepcopy(WIRE[key])
    request = (
        RoundTimerCommandBatchRequest.model_validate(body)
        if key == "timer_request"
        else RoundSyncPushRequest.model_validate(body)
    )
    assert request.model_dump(mode="json") == body
    replica = ReplicaIdentity(**WIRE["replica"])
    source = request.contexts[0]
    original = (
        request.commands[0]
        if isinstance(request, RoundTimerCommandBatchRequest)
        else request.operations[0]
    )
    timer = key == "timer_request"
    digest = (
        timer_command_hash(original, replica=replica)
        if isinstance(original, TimerCommandRequest)
        else operation_hash(original, replica=replica)
    )
    encoded = receipt_context(original, source, replica, timer=timer)
    value, recovered = validate_receipt_context(encoded, digest)
    assert recovered == original and value.source == source
    assert body == WIRE[key]
    wrong_replica = ReplicaIdentity(
        replica.server_instance_id, "ffffffff-ffff-4fff-8fff-ffffffffffff"
    )
    wrong_hash = (
        timer_command_hash(original, replica=wrong_replica)
        if isinstance(original, TimerCommandRequest)
        else operation_hash(original, replica=wrong_replica)
    )
    with pytest.raises(DomainError, match="recovered safely"):
        validate_receipt_context(encoded, wrong_hash)


def test_original_fact_envelope_and_hash_are_not_replaced_by_context():
    wire = WIRE["fact_request"]
    original = SyncOperationRequest.model_validate(wire["operations"][0])
    plain = NextSyncPushRequest.model_validate(
        {"device_id": wire["device_id"], "operations": wire["operations"]}
    )
    profile = RoundSyncPushRequest.model_validate(wire)
    replica = ReplicaIdentity(**WIRE["replica"])
    assert plain.operations[0] == original == profile.operations[0]
    assert operation_hash(plain.operations[0], replica=replica) == operation_hash(
        profile.operations[0], replica=replica
    )
    changed = deepcopy(wire)
    changed["contexts"][0]["head"] = ROUNDS["heads"][0]
    second = RoundSyncPushRequest.model_validate(changed)
    assert operation_hash(second.operations[0], replica=replica) == operation_hash(
        original, replica=replica
    )
    assert receipt_context(
        second.operations[0], second.contexts[0], replica
    ) != receipt_context(original, profile.contexts[0], replica)


@pytest.mark.parametrize(
    "field,value",
    [
        ("entity_uuid", "44444444-4444-4444-8444-444444444444"),
        ("action", "delete"),
        ("base_revision", 1),
    ],
)
def test_restart_requires_exact_new_identity_and_no_structural_merge(field, value):
    operation = deepcopy(WIRE["restart_request"]["operations"][0])
    operation[field] = value
    with pytest.raises(ValidationError):
        ChallengeRestartOperation.model_validate(operation)
    with pytest.raises(ValidationError):
        SyncOperationRequest.model_validate(WIRE["restart_request"]["operations"][0])


def metadata():
    return dict(
        challenge_contract=1,
        checkpoints=[dict(head=ROUNDS["heads"][1], records=ROUNDS["records"][:2])],
        births=[
            dict(
                entity_type="activity_event",
                entity_uuid=WIRE["fact_request"]["operations"][0]["entity_uuid"],
                head=ROUNDS["heads"][1],
            )
        ],
    )


@pytest.mark.parametrize(
    "damage",
    [
        "missing-baseline",
        "duplicate-checkpoint",
        "duplicate-birth",
        "unproven-birth",
        "foreign-birth",
    ],
)
def test_metadata_requires_unique_complete_original_lineage(damage):
    body = metadata()
    assert ChallengeMetadata.model_validate(body).model_dump(mode="json") == body
    if damage == "missing-baseline":
        body["checkpoints"][0]["records"].pop(0)
    elif damage == "duplicate-checkpoint":
        body["checkpoints"].append(deepcopy(body["checkpoints"][0]))
    elif damage == "duplicate-birth":
        body["births"].append(deepcopy(body["births"][0]))
    elif damage == "unproven-birth":
        body["births"][0]["head"] = ROUNDS["heads"][2]
    else:
        body["births"][0]["head"] = ROUNDS["heads"][5]
    with pytest.raises(ValidationError):
        ChallengeMetadata.model_validate(body)
