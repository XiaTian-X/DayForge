"""Real runtime HTTP recovery reads; no device/cursor/API-token mutation."""

from copy import deepcopy
from typing import Any
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from sqlalchemy import text

from src.v2.recovery_snapshot_contract import ReplicaRecoveryPreviewResponse
from src.v2.replica_context import EPOCH_HEADER, INSTANCE_HEADER, PROTOCOL_HEADER
from tests.runtime_auth_fixtures import relogin_runtime_headers
from tests.test_http_commit_boundary import database_state, runtime_http as runtime_http
from tests.test_v5_production_sync import production_v5 as production_v5
from tests.test_challenge_profile import (
    COMMANDS,
    batch,
    call_push,
    fact,
    seed,
    snapshot,
    timer_batch,
)
from tests.test_next_structural_sync import goal, metric, operation
from tests.test_next_structural_sync import node
from tests.test_one_time_mutations import operation as once_fact
from tests.test_one_time_storage import ACTIVITY
from tests.test_timer_sync import timer_command

PATH = "/api/v2/sync/rounds/recovery-preview"


def parameters(device):
    return dict(
        device_id=device,
        installation_id="commit-primary-device",
        challenge_contract="1",
    )


async def preview(client, headers, device):
    response = await client.get(PATH, headers=headers, params=parameters(device))
    assert response.status_code == 200, response.text
    assert response.headers["Cache-Control"] == "private, no-store"
    ReplicaRecoveryPreviewResponse.model_validate(response.json())
    return response.json()


async def test_complete_snapshot_is_read_only_and_matches_normal_projection(
    production_v5,
):
    client, engine, headers, device = production_v5
    activity, _, head = await seed(production_v5)
    event = fact(activity)
    await call_push(
        client, headers, batch(device, [event], {event["operation_id"]: head})
    )
    await call_push(
        client,
        headers,
        batch(device, [operation(goal()), operation(metric(), kind="metric")]),
    )
    before = await database_state(engine)
    first = await preview(client, headers, device)
    second = await preview(client, headers, device)
    assert await database_state(engine) == before
    assert first["active_timer"] is None
    assert first["device"]["device_id"] == device
    assert first["server_instance_id"] == headers[INSTANCE_HEADER]
    assert first["sync_epoch"] == headers[EPOCH_HEADER]
    assert {item["entity_type"] for item in first["snapshot"]["changes"]} == {
        "plan_node",
        "metric",
        "activity_event",
    }
    for value in (first["snapshot"], second["snapshot"]):
        value.pop("server_time")
    assert first["snapshot"] == second["snapshot"]
    ordinary = await snapshot(client, headers, device)
    ordinary.pop("server_time")
    assert ordinary == first["snapshot"]


@pytest.mark.parametrize("paused", [False, True])
async def test_unfinished_timer_and_birth_share_snapshot_without_writes(
    production_v5, paused
):
    client, engine, headers, device = production_v5
    activity, _, head = await seed(production_v5, "duration")
    identity = str(uuid4())
    occurred = datetime.now(UTC) - timedelta(minutes=1)
    start = timer_command("start", identity, 1, occurred, activity_id=activity)
    start["start_policy"] = dict(
        target_seconds=60, is_countdown=False, max_duration_seconds=180
    )
    commands = [start]
    if paused:
        commands.append(
            timer_command(
                "pause",
                identity,
                2,
                occurred + timedelta(seconds=20),
                revision=1,
                active_elapsed_ms=20_000,
            )
        )
    sent = await client.post(
        COMMANDS, headers=headers, json=timer_batch(device, commands, head)
    )
    assert sent.status_code == 200 and all(
        item["status"] == "applied" for item in sent.json()["results"]
    ), sent.text
    before = await database_state(engine)
    result = await preview(client, headers, device)
    assert await database_state(engine) == before
    assert result["active_timer"]["session_id"] == identity
    assert result["active_timer"]["state"] == ("paused" if paused else "running")
    assert any(
        item["entity_uuid"] == identity and item["entity_type"] == "timer_session"
        for item in result["snapshot"]["births"]
    )
    broken = deepcopy(result)
    broken["snapshot"]["births"] = []
    with pytest.raises(ValueError):
        ReplicaRecoveryPreviewResponse.model_validate(broken)


@pytest.mark.parametrize(
    "case",
    [
        "wrong-epoch",
        "wrong-server",
        "duplicate-header",
        "wrong-install",
        "duplicate-device",
        "uppercase-device",
        "duplicate-contract",
        "unknown-device",
        "legacy-device",
        "revoked",
        "inactive-account",
        "no-auth",
        "unknown-version",
    ],
)
async def test_preview_rejects_unproven_scope_without_any_writes(production_v5, case):
    client, engine, original, device = production_v5
    headers: Any = dict(original)
    params: Any = parameters(device)
    if case == "wrong-epoch":
        headers[EPOCH_HEADER] = str(uuid4())
    elif case == "wrong-server":
        headers[INSTANCE_HEADER] = str(uuid4())
    elif case == "duplicate-header":
        headers = [*headers.items(), (PROTOCOL_HEADER, "5")]
    elif case == "wrong-install":
        params["installation_id"] = "different-installation"
    elif case == "duplicate-device":
        params = [*params.items(), ("device_id", device)]
    elif case == "uppercase-device":
        params["device_id"] = device.upper()
    elif case == "duplicate-contract":
        params = [*params.items(), ("challenge_contract", "1")]
    elif case == "unknown-device":
        params["device_id"] = str(uuid4())
    elif case == "no-auth":
        headers.pop("Authorization")
    else:
        statement = {
            "legacy-device": "UPDATE client_devices SET registered_protocol_version=4",
            "revoked": "UPDATE client_devices SET revoked_at=CURRENT_TIMESTAMP",
            "inactive-account": "UPDATE users SET is_active=0",
            "unknown-version": "UPDATE server_instances SET protocol_version=6",
        }[case]
        async with engine.begin() as connection:
            await connection.execute(text(statement))
    before = await database_state(engine)
    result = await client.get(PATH, headers=headers, params=params)
    expected = {
        "wrong-epoch": 409,
        "wrong-server": 409,
        "duplicate-header": 426,
        "wrong-install": 400,
        "duplicate-device": 400,
        "uppercase-device": 400,
        "duplicate-contract": 400,
        "unknown-device": 404,
        "legacy-device": 426,
        "revoked": 404,
        "inactive-account": 401,
        "no-auth": 401,
        "unknown-version": 426,
    }[case]
    assert result.status_code == expected, result.text
    assert await database_state(engine) == before
    assert "snapshot" not in result.json()


async def test_new_epoch_requires_fresh_authentication_and_does_not_register(
    production_v5,
):
    client, engine, original, device = production_v5
    new_epoch = str(uuid4())
    # Scope fault injection only, not a claim of a physical backup/restore test.
    async with engine.begin() as connection:
        await connection.execute(
            text("UPDATE server_instances SET sync_epoch=:epoch"), dict(epoch=new_epoch)
        )
    headers = original | {EPOCH_HEADER: new_epoch}
    before = await database_state(engine)
    if headers["Authorization"].startswith("Bearer "):
        rejected = await client.get(PATH, headers=headers, params=parameters(device))
        assert rejected.status_code == 401
        assert await database_state(engine) == before
    headers = await relogin_runtime_headers(client, headers)
    before = await database_state(engine)
    result = await preview(client, headers, device)
    assert result["sync_epoch"] == new_epoch
    assert await database_state(engine) == before


@pytest.mark.parametrize(
    "field,value",
    [
        ("recovery_contract", True),
        ("protocol_version", True),
        ("recovery_contract", "1"),
        ("protocol_version", 6),
    ],
)
async def test_response_contract_cannot_coerce_version(production_v5, field, value):
    client, _, headers, device = production_v5
    result = await preview(client, headers, device)
    result[field] = value
    with pytest.raises(ValueError):
        ReplicaRecoveryPreviewResponse.model_validate(result)


async def test_all_fact_kinds_and_once_proof_are_preserved(production_v5):
    client, engine, headers, device = production_v5
    once = operation(node(once=True), identity=ACTIVITY)
    metric_id = str(uuid4())
    metric_op = operation(metric(), identity=metric_id, kind="metric")
    created = await call_push(client, headers, batch(device, [once, metric_op]))
    for item in created["results"]:
        assert item["status"] == "applied", item
    fact_op = once_fact().model_dump(mode="json")
    observation = operation(
        dict(
            metric_uuid=metric_id,
            value=64.25,
            unit="kg",
            occurred_at="2026-10-08T03:04:05.123456Z",
            local_date="2026-10-08",
            timezone="UTC",
        ),
        identity=str(uuid4()),
        kind="metric_observation",
    )
    link = operation(
        dict(activity_uuid=ACTIVITY, metric_uuid=metric_id, prompt_on_complete=True),
        identity=str(uuid4()),
        kind="activity_metric_link",
    )
    response = await call_push(
        client, headers, batch(device, [fact_op, observation, link])
    )
    for item in response["results"]:
        assert item["status"] == "applied", item
    before = await database_state(engine)
    result = await preview(client, headers, device)
    assert await database_state(engine) == before
    assert {item["entity_type"] for item in result["snapshot"]["changes"]} == {
        "plan_node",
        "activity_event",
        "metric",
        "metric_observation",
        "activity_metric_link",
    }
    assert result["snapshot"]["one_time_checkpoints"][0]["state"]["version"] == 1
    expected = await snapshot(client, headers, device)
    expected.pop("server_time")
    result["snapshot"].pop("server_time")
    assert expected == result["snapshot"]


async def test_concurrent_epoch_change_cannot_mix_snapshot_scopes(
    production_v5, monkeypatch
):
    from src.v2 import recovery_snapshot_service

    client, engine, headers, device = production_v5
    original = recovery_snapshot_service.read_bootstrap_snapshot
    new_epoch = str(uuid4())

    async def interleaved(user, session, **kwargs):
        async with engine.begin() as writer:
            await writer.execute(
                text("UPDATE server_instances SET sync_epoch=:epoch"),
                dict(epoch=new_epoch),
            )
        return await original(user, session, **kwargs)

    monkeypatch.setattr(
        recovery_snapshot_service, "read_bootstrap_snapshot", interleaved
    )
    result = await preview(client, headers, device)
    # An immutable read snapshot may be old, but must not mix the concurrent epoch.
    assert result["sync_epoch"] == headers[EPOCH_HEADER]
    monkeypatch.setattr(recovery_snapshot_service, "read_bootstrap_snapshot", original)
    stale = await client.get(PATH, headers=headers, params=parameters(device))
    assert stale.status_code == (
        401 if headers["Authorization"].startswith("Bearer ") else 409
    )
