"""Internal-reference failures must retain real HTTP rollback and retry behavior."""

from copy import deepcopy
from uuid import uuid4

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from src.v2.models import (
    ActivityDetail,
    GoalDetail,
    PlanNode,
    TimerSession,
    TrackedMetric,
)
from tests.test_http_commit_boundary import database_state
from tests.test_http_commit_boundary import runtime_http as runtime_http
from tests.test_sync_contract_matrix import fixture, with_device
from tests.test_sync_v2 import goal_operation


@pytest.mark.parametrize(
    "index,missing_model,action,expected_status",
    [
        (0, GoalDetail, "upsert", "applied"),
        (1, ActivityDetail, "upsert", "applied"),
        (4, PlanNode, "upsert", "conflict"),
        (5, TrackedMetric, "delete", "applied"),
        (6, PlanNode, "delete", "applied"),
        (6, TrackedMetric, "delete", "applied"),
    ],
)
async def test_missing_write_reference_rolls_back_entire_http_batch_and_retries(
    runtime_http, monkeypatch, index, missing_model, action, expected_status
):
    client, engine, bearer, api_headers, device = runtime_http
    batch = with_device(fixture("client/push-all-entities.json"), device)
    created = await client.post("/api/v2/sync/push", headers=bearer, json=batch)
    assert created.status_code == 200
    assert [item["status"] for item in created.json()["results"]] == ["applied"] * 7
    operation = deepcopy(batch["operations"][index])
    operation.update(operation_id=str(uuid4()), action=action, base_revision=1)
    if action == "delete":
        operation["payload"] = {}
    elif index in (0, 1):
        operation["payload"]["title"] += " changed"
    request = {
        "device_id": device,
        "operations": [goal_operation(title="Must roll back"), operation],
    }
    original_get = AsyncSession.get

    async def missing_reference(session, model, identity, **kwargs):
        if model is missing_model:
            return None
        return await original_get(session, model, identity, **kwargs)

    before = await database_state(engine)
    with monkeypatch.context() as patch:
        patch.setattr(AsyncSession, "get", missing_reference)
        failed = await client.post(
            "/api/v2/sync/push", headers=api_headers, json=request
        )
    assert failed.status_code == 500
    assert failed.text == "Internal Server Error"
    # Includes auth last-used, revisions, operation reservations, journal and cursors.
    assert await database_state(engine) == before
    retried = await client.post("/api/v2/sync/push", headers=api_headers, json=request)
    assert retried.status_code == 200
    assert [item["status"] for item in retried.json()["results"]] == [
        "applied",
        expected_status,
    ]
    replay = await client.post("/api/v2/sync/push", headers=api_headers, json=request)
    assert replay.status_code == 200
    expected_replay = (
        "already_applied" if expected_status == "applied" else expected_status
    )
    assert [item["status"] for item in replay.json()["results"]] == [
        "already_applied",
        expected_replay,
    ]


async def test_timer_completion_reference_failure_rolls_back_commands_and_allocations(
    runtime_http, monkeypatch
):
    client, engine, bearer, api_headers, device = runtime_http
    seeded = await client.post(
        "/api/v2/sync/push",
        headers=bearer,
        json=with_device(fixture("client/push-all-entities.json"), device),
    )
    assert seeded.status_code == 200
    commands = with_device(fixture("client/timer-commands.json"), device)
    original_get = AsyncSession.get
    fault_reached = False

    async def missing_completed_activity(session, model, identity, **kwargs):
        nonlocal fault_reached
        if model is PlanNode and any(
            isinstance(row, TimerSession) and row.state == "completed"
            for row in session.identity_map.values()
        ):
            fault_reached = True
            return None
        return await original_get(session, model, identity, **kwargs)

    before = await database_state(engine)
    with monkeypatch.context() as patch:
        patch.setattr(AsyncSession, "get", missing_completed_activity)
        failed = await client.post(
            "/api/v2/timers/commands", headers=api_headers, json=commands
        )
    assert fault_reached
    assert failed.status_code == 500 and failed.text == "Internal Server Error"
    assert await database_state(engine) == before
    retried = await client.post(
        "/api/v2/timers/commands", headers=api_headers, json=commands
    )
    assert retried.status_code == 200
    results = retried.json()["results"]
    assert [item["status"] for item in results] == ["applied"] * 4
    assert results[-1]["session"]["state"] == "completed"
    assert results[-1]["session"]["active_elapsed_ms"] == 60000
    replay = await client.post(
        "/api/v2/timers/commands", headers=api_headers, json=commands
    )
    assert replay.status_code == 200
    assert [item["status"] for item in replay.json()["results"]] == [
        "already_applied"
    ] * 4
