"""Wire baselines captured before the model/validation dependency upgrade.

The fixtures remain shared with Android. These digests anchor canonical parsed
JSON and persisted operation identities; update only for an intentional reviewed
contract change, not to silence a dependency regression.
"""

import hashlib
from copy import deepcopy

import pytest
from pydantic import BaseModel, ValidationError

from src.v2.encoding import canonical_json, operation_hash
from src.v2.schemas import (
    ActivityPayload,
    MetricObservationPayload,
    SyncOperationRequest,
    SyncPushRequest,
)
from tests.test_sync_contract_matrix import VALID_FIXTURES, fixture


WIRE_DIGESTS = {
    "client/push-all-entities.json": "4beaa522b6b3307d7ba39c6e33a3dc80c0898c2d53d87735d878fab65f14c2c9",
    "client/delete-goal.json": "fe5882ce639afecb9b7d439e3291e287fa6022fb6c42839021c9979006370953",
    "client/conflict-create.json": "8b6cf1be68d6967ebf08d946d5782bcba2e3cf9b4fc69c1b5c8a26da39b56d10",
    "client/conflict-remote.json": "806ce6009b4a222fad8a9ae310661fc3d17bb9d32ca5282ddb629371a328d9ec",
    "client/conflict-stale.json": "45a9559b99a225516705fc1433d45de1760e7f348e3ebdbf4c730307c2159152",
    "client/account-a.json": "cb800726465816a6bc5e6aaa92358235d4366b44c857f606c6ea9780c0120d72",
    "client/account-b.json": "f484f4c7c0f73c5776bedeced5d2ab0af0aeb2cd48380ffe70f12713c64b1376",
    "client/timer-commands.json": "7eeab16572911318a9c85c0876d5a7a57a520e8b815e5bd39a33fdcdd1a44243",
    "server/bootstrap-response.json": "62758d8dc2ea57cf800361f078269030bb21220a5a67ede9b0f068a66b4d26b9",
    "server/push-applied-response.json": "36caafe37ee27c64ae26c29eedab77e0fec178d59e0e9e588c881c2e052cd65e",
    "server/pull-with-tombstone-response.json": "f61ce3ab900bf4ebb0c5555493dca881079a1c795734deca2b999b17bdd4bf98",
    "server/conflict-response.json": "6c982ca26bc21088059c1fa6b13800744ba4d041f02f28bfda56689ca427fc16",
    "server/identity-before-response.json": "e565eca3a22b26be3fcb7fb2b3fd5c5b9594472099e81914dab6e04597f8cf5e",
    "server/identity-after-epoch-reset-response.json": "65739e5c68b6a8fa049ebae3e30cb169d5ae3a7ff895279077a446ac1a8e4f94",
    "server/timer-commands-response.json": "713c5cc33535c1ed08f21abe3da15767b180e5b4bed3292ef2307367f34a1815",
}

OPERATION_DIGESTS = [
    "ce5c881586697f91dae37fcb14066db1ed43fb90b06241ba87a0d7aa52c81777",
    "78a86ae408e5247b6dc8fd5afe74f7600881cd1213e9e9cc2f5cfe46fdf93628",
    "b4d45313387044c1a9e10daeb85678aab72ed6994260633a71280e885c893077",
    "490b531678faa6518ddeb1061908b7ed62d9955a75c95a7e7351a56fa17715ec",
    "b9ea024631b2775ae56b6ea7ab4d9be5728b4651992ba199adff40ff7b0022f6",
    "cdc29a0b45bce4da6a4a8ef9de5a4e048aa8731785402538aab96ddedd38f596",
    "ac667b48234a9e829cc2e7d5eca3e608d6b9619e9e4684533e02a4384fa99308",
]


@pytest.mark.parametrize("path,model", VALID_FIXTURES)
def test_parsed_wire_json_matches_pre_upgrade_baseline(path, model):
    encoded = canonical_json(
        model.model_validate(fixture(path)).model_dump(mode="json")
    )
    assert hashlib.sha256(encoded.encode()).hexdigest() == WIRE_DIGESTS[path], encoded


def test_persisted_operation_hashes_match_pre_upgrade_baseline():
    request = SyncPushRequest.model_validate(fixture("client/push-all-entities.json"))
    assert [operation_hash(op) for op in request.operations] == OPERATION_DIGESTS


@pytest.mark.parametrize("location", ["batch", "operation", "recurrence"])
def test_unknown_typed_fields_stay_forbidden(location):
    raw = deepcopy(fixture("client/push-all-entities.json"))
    model: type[BaseModel]
    if location == "batch":
        raw["unexpected"] = True
        model = SyncPushRequest
    elif location == "operation":
        raw = raw["operations"][0]
        raw["unexpected"] = True
        model = SyncOperationRequest
    else:
        raw = raw["operations"][1]["payload"]["activity"]
        raw["recurrence_rule"]["unexpected"] = True
        model = ActivityPayload
    with pytest.raises(ValidationError) as error:
        model.model_validate(raw)
    assert any(item["type"] == "extra_forbidden" for item in error.value.errors())


@pytest.mark.parametrize("invalid", [None, "NaN", "Infinity", "-Infinity"])
def test_observation_invalid_decimal_stays_rejected(invalid):
    raw = fixture("client/push-all-entities.json")["operations"][5]["payload"]
    with pytest.raises(ValidationError) as error:
        MetricObservationPayload.model_validate({**raw, "value": invalid})
    assert error.value.errors()[0]["loc"] == ("value",)


def test_sync_envelope_keeps_open_payload_but_closed_typed_fields():
    schema = SyncOperationRequest.model_json_schema()
    payload_schema = schema["properties"]["payload"]
    assert payload_schema["type"] == "object"
    assert payload_schema.get("additionalProperties", True) is True
    assert schema["additionalProperties"] is False
    raw = fixture("client/push-all-entities.json")["operations"][0]
    arbitrary = {"unknown_key": ["中文", 1, None, {"nested": True}]}
    model = SyncOperationRequest.model_validate({**raw, "payload": arbitrary})
    assert model.model_dump(mode="json")["payload"] == arbitrary
