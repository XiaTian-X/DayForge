"""Shared protocol-v5 vectors; live routes remain v4 until the coordinated cutover."""

from copy import deepcopy
import json
from pathlib import Path

import pytest
from pydantic import BaseModel

from src.v2.asset_api_contract import (
    AssetDeclaration,
    AssetRecord,
    AssetTransferReceipt,
    PackDeclaration,
    AppearanceCatalogPage,
    AppearanceQuota,
    AssetSyncContext,
    validate_asset_record_binding,
    validate_transfer_binding,
    validate_catalog_binding,
    validate_pack_binding,
)
from src.v2.next_sync_contract import (
    NextPlanNodePayload,
    NextMetricPayload,
    NextSyncPushRequest,
    NextSyncBootstrapResponse,
    NextSyncOperationResult,
    NextSyncPullResponse,
    validate_task_result_binding,
    validate_next_sync_operation,
)
from src.v2.schemas import PlanNodePayload, MetricPayload, SyncBootstrapResponse

FIXTURE = json.loads(
    (Path(__file__).resolve().parents[2] / "contracts/next/api.json").read_text()
)
MODELS: dict[str, type[BaseModel]] = {
    "task": NextPlanNodePayload,
    "metric": NextMetricPayload,
    "push": NextSyncPushRequest,
    "bootstrap": NextSyncBootstrapResponse,
    "conflict": NextSyncOperationResult,
    "accepted": NextSyncOperationResult,
    "asset_declaration": AssetDeclaration,
    "asset_record": AssetRecord,
    "receipt": AssetTransferReceipt,
    "pack_declaration": PackDeclaration,
    "catalog": AppearanceCatalogPage,
    "quota": AppearanceQuota,
}


def mutate(source, case):
    raw = deepcopy(source)
    if not case["path"]:
        return raw
    cursor = raw
    for key in case["path"][:-1]:
        cursor = cursor[key]
    if case.get("operation") == "remove":
        del cursor[case["path"][-1]]
    else:
        cursor[case["path"][-1]] = deepcopy(case["value"])
    return raw


@pytest.mark.parametrize("name", list(MODELS))
def test_valid_next_api_vectors_round_trip(name):
    model = MODELS[name]
    parsed = model.model_validate(FIXTURE[name])
    assert model.model_validate_json(parsed.model_dump_json()) == parsed
    if isinstance(parsed, NextSyncPushRequest):
        for operation in parsed.operations:
            validate_next_sync_operation(operation)


@pytest.mark.parametrize("case", FIXTURE["invalid"], ids=lambda case: case["name"])
def test_invalid_next_api_vectors(case):
    with pytest.raises(ValueError):
        parsed = MODELS[case["model"]].model_validate(
            mutate(FIXTURE[case["model"]], case)
        )
        if isinstance(parsed, NextSyncPushRequest):
            for operation in parsed.operations:
                validate_next_sync_operation(operation)


def test_bad_domain_payload_does_not_reject_the_whole_envelope():
    raw = deepcopy(FIXTURE["push"])
    invalid = deepcopy(raw["operations"][0])
    invalid["operation_id"] = FIXTURE["context"]["device_id"]
    invalid["payload"]["one_time_state_after"] = None
    raw["operations"].append(invalid)
    envelope = NextSyncPushRequest.model_validate(raw)
    assert len(envelope.operations) == 2
    validate_next_sync_operation(envelope.operations[0])
    with pytest.raises(ValueError):
        validate_next_sync_operation(envelope.operations[1])


@pytest.mark.parametrize("case", FIXTURE["bindings"], ids=lambda case: case["name"])
def test_response_binding_rejects_old_scope_and_wrong_identity(case):
    def validate():
        request = AssetDeclaration.model_validate(FIXTURE["asset_declaration"])
        if case["type"] == "asset":
            validate_asset_record_binding(
                request,
                AssetRecord.model_validate(mutate(FIXTURE["asset_record"], case)),
            )
        elif case["type"] == "transfer":
            validate_transfer_binding(
                request,
                "light",
                AssetTransferReceipt.model_validate(mutate(FIXTURE["receipt"], case)),
            )
        elif case["type"] == "pack":
            validate_pack_binding(
                PackDeclaration.model_validate(FIXTURE["pack_declaration"]),
                PackDeclaration.model_validate(
                    mutate(FIXTURE["pack_declaration"], case)
                ),
            )
        elif case["type"] in {"task", "accepted_task"}:
            operation = NextSyncPushRequest.model_validate(FIXTURE["push"]).operations[
                0
            ]
            validate_task_result_binding(
                operation,
                NextSyncOperationResult.model_validate(
                    mutate(
                        FIXTURE[
                            "accepted"
                            if case["type"] == "accepted_task"
                            else "conflict"
                        ],
                        case,
                    )
                ),
            )
        else:
            validate_catalog_binding(
                request.context,
                0,
                None,
                AppearanceCatalogPage.model_validate(mutate(FIXTURE["catalog"], case)),
            )

    if case["valid"]:
        validate()
    else:
        with pytest.raises(ValueError):
            validate()


def test_catalog_watermark_cursor_long_range_and_lowered_quota():
    raw = deepcopy(FIXTURE["catalog"])
    raw.update(entries=raw["entries"][:1], next_cursor=1, has_more=True)
    page = AppearanceCatalogPage.model_validate(raw)
    context = AssetSyncContext.model_validate(FIXTURE["context"])
    validate_catalog_binding(context, 0, 3, page)
    for after, through in ((1, 3), (0, 4), (-1, 3), (4, 3)):
        with pytest.raises(ValueError):
            validate_catalog_binding(context, after, through, page)
    raw.update(
        entries=[],
        next_cursor=9_223_372_036_854_775_807,
        through_sequence=9_223_372_036_854_775_807,
        has_more=False,
    )
    assert (
        AppearanceCatalogPage.model_validate(raw).next_cursor
        == 9_223_372_036_854_775_807
    )
    assert (
        AppearanceQuota.model_validate(
            {**FIXTURE["quota"], "byte_limit": 1}
        ).reserved_bytes
        == 640
    )


def test_same_blob_variant_availability_and_missing_dark_transfer():
    raw = deepcopy(FIXTURE["asset_record"])
    raw["asset"]["dark"] = raw["asset"]["light"]
    for variants in ([], ["light", "dark"]):
        AssetRecord.model_validate({**raw, "ready_variants": variants})
    with pytest.raises(ValueError):
        AssetRecord.model_validate({**raw, "ready_variants": ["light"]})
    request = AssetDeclaration.model_validate(FIXTURE["asset_declaration"])
    receipt = AssetTransferReceipt.model_validate(
        {**FIXTURE["receipt"], "variant": "dark"}
    )
    with pytest.raises(ValueError):
        validate_transfer_binding(request, "dark", receipt)


def test_zero_history_item_needs_zero_checkpoint_and_recurring_cannot_have_proof():
    raw = deepcopy(FIXTURE["bootstrap"])
    raw["changes"] = raw["changes"][:1]
    raw["one_time_checkpoints"][0]["state"] = {
        "version": 0,
        "head_event_uuid": None,
        "completion_event_uuid": None,
    }
    assert (
        NextSyncBootstrapResponse.model_validate(raw)
        .one_time_checkpoints[0]
        .state.version
        == 0
    )
    raw = deepcopy(FIXTURE["bootstrap"])
    node = raw["changes"][0]["payload"]
    node["activity"].update(
        completion_policy="recurring",
        recurrence_rule={"type": "daily", "schema_version": 1},
    )
    node["appearance"]["icon"]["role"] = "habit.water"
    raw["one_time_checkpoints"] = []
    with pytest.raises(ValueError):
        NextSyncBootstrapResponse.model_validate(raw)


def test_live_v4_models_still_reject_new_fields_and_pull_validates_immutable_proof():
    for model, name in (
        (PlanNodePayload, "task"),
        (MetricPayload, "metric"),
        (SyncBootstrapResponse, "bootstrap"),
    ):
        with pytest.raises(ValueError):
            model.model_validate(FIXTURE[name])
    change = deepcopy(FIXTURE["bootstrap"]["changes"][1])
    raw = {
        "changes": [change],
        "next_cursor": 7,
        "has_more": False,
        "server_time": FIXTURE["bootstrap"]["server_time"],
    }
    NextSyncPullResponse.model_validate(raw)
    change["payload"]["one_time_state_after"]["head_event_uuid"] = FIXTURE["context"][
        "device_id"
    ]
    with pytest.raises(ValueError):
        NextSyncPullResponse.model_validate(raw)


def test_planned_openapi_is_reproducible_has_no_dangling_refs_and_is_not_live():
    import subprocess
    import sys
    from src.main import app

    root = Path(__file__).resolve().parents[2]
    result = subprocess.run(
        [
            sys.executable,
            str(root / "backend/scripts/export_next_openapi.py"),
            "--check",
        ],
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    document = json.loads((root / "contracts/next/openapi.json").read_text())
    assert (
        document["openapi"] == "3.1.0" and document["x-activation-state"] == "planned"
    )
    assert not any("/appearance/" in path for path in app.openapi()["paths"])
    for path in document["paths"].values():
        for operation in path.values():
            protocol = [
                item
                for item in operation["parameters"]
                if item["name"] == "X-DayForge-Protocol"
            ]
            assert len(protocol) == 1
            assert protocol[0]["in"] == "header" and protocol[0]["required"]
            assert protocol[0]["schema"]["enum"] == [5]

    def visit(value):
        if isinstance(value, dict):
            if "$ref" in value:
                assert value["$ref"].startswith("#/components/schemas/")
                assert value["$ref"].split("/")[-1] in document["components"]["schemas"]
            for item in value.values():
                visit(item)
        elif isinstance(value, list):
            for item in value:
                visit(item)

    visit(document)
    assert {
        "/api/v2/sync/push",
        "/api/v2/sync/bootstrap",
        "/api/v2/sync/changes",
        "/api/v2/appearance/catalog",
        "/api/v2/appearance/quota",
    }.issubset(document["paths"])
