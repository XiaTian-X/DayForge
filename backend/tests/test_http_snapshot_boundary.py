"""Concurrent commits cannot produce successful responses from stale write snapshots."""

import sqlite3

import pytest
from sqlalchemy import event, text
from sqlalchemy.exc import OperationalError

from src.v2 import read_service, service, timer_service
from tests.test_http_commit_boundary import database_state, runtime_http as runtime_http
from tests.test_sync_contract_matrix import fixture, with_device
from tests.test_sync_v2 import goal_operation


@pytest.mark.parametrize("kind", ["push", "timer", "bootstrap", "pull"])
async def test_stale_snapshot_rolls_back_request_and_original_identity_can_retry(
    runtime_http, monkeypatch, kind
):
    client, engine, headers, _, device = runtime_http
    if kind == "timer":
        setup = await client.post(
            "/api/v2/sync/push",
            headers=headers,
            json=with_device(fixture("client/push-all-entities.json"), device),
        )
        assert all(item["status"] == "applied" for item in setup.json()["results"])
    module = (
        service
        if kind == "push"
        else timer_service
        if kind == "timer"
        else read_service
    )
    original_require = module.require_device
    expected_after_winner = None

    async def concurrent_commit(user_id, public_id, session):
        nonlocal expected_after_winner
        result = await original_require(user_id, public_id, session)
        if expected_after_winner is None:
            # Authentication + require_device have genuinely read this transaction.
            # An independent writer wins before the losing reader upgrades to DML.
            async with engine.begin() as writer:
                await writer.execute(
                    text("UPDATE users SET is_verified = 1 WHERE id = :id"),
                    {"id": user_id},
                )
            expected_after_winner = await database_state(engine)
        return result

    monkeypatch.setattr(module, "require_device", concurrent_commit)
    body = (
        with_device(fixture("client/timer-commands.json"), device)
        if kind == "timer"
        else {"device_id": device, "operations": [goal_operation()]}
    )

    async def request():
        if kind in {"bootstrap", "pull"}:
            path = "bootstrap" if kind == "bootstrap" else "changes"
            return await client.get(
                f"/api/v2/sync/{path}",
                headers=headers,
                params={
                    "device_id": device,
                    **({"cursor": 0} if kind == "pull" else {}),
                },
            )
        path = "/api/v2/timers/commands" if kind == "timer" else "/api/v2/sync/push"
        return await client.post(path, headers=headers, json=body)

    failed = await request()
    assert failed.status_code == 503, failed.text
    assert failed.json() == {
        "detail": {
            "code": "DATABASE_BUSY",
            "message": "Database is temporarily busy; retry the original request",
        }
    }
    assert failed.headers["Retry-After"] == "1"
    assert await database_state(engine) == expected_after_winner
    recovered = await request()
    assert recovered.status_code == 200, recovered.text
    if kind in {"push", "timer"}:
        assert all(item["status"] == "applied" for item in recovered.json()["results"])
        replay = await request()
        assert replay.status_code == 200, replay.text
        assert replay.json()["results"] == [
            {**item, "status": "already_applied"}
            for item in recovered.json()["results"]
        ]
    else:
        assert recovered.json()["changes"] == []
        assert recovered.json()["next_cursor"] == 0


async def test_existing_writer_lock_rolls_back_without_partial_operations(runtime_http):
    client, engine, headers, _, device = runtime_http
    before = await database_state(engine)
    body = {"device_id": device, "operations": [goal_operation()]}
    async with engine.connect() as writer:
        transaction = await writer.begin()
        await writer.execute(text("UPDATE users SET is_verified = 1"))
        response = await client.post("/api/v2/sync/push", headers=headers, json=body)
        assert response.status_code == 503, response.text
        assert response.json()["detail"]["code"] == "DATABASE_BUSY"
        await transaction.rollback()
    assert await database_state(engine) == before
    retry = await client.post("/api/v2/sync/push", headers=headers, json=body)
    assert retry.status_code == 200, retry.text
    assert retry.json()["results"][0]["status"] == "applied"


async def test_busy_at_commit_never_acknowledges_saved_operations(runtime_http):
    client, engine, headers, _, device = runtime_http
    before = await database_state(engine)
    body = {"device_id": device, "operations": [goal_operation()]}

    def fail_commit(connection):
        original = sqlite3.OperationalError("injected busy COMMIT with private details")
        original.sqlite_errorcode = sqlite3.SQLITE_BUSY
        raise OperationalError("COMMIT", {}, original)

    event.listen(engine.sync_engine, "commit", fail_commit)
    try:
        failed = await client.post("/api/v2/sync/push", headers=headers, json=body)
        assert failed.status_code == 503, failed.text
        assert failed.json()["detail"]["code"] == "DATABASE_BUSY"
        assert "private" not in failed.text and "applied" not in failed.text
    finally:
        event.remove(engine.sync_engine, "commit", fail_commit)
    assert await database_state(engine) == before
    retry = await client.post("/api/v2/sync/push", headers=headers, json=body)
    assert retry.status_code == 200, retry.text
    assert retry.json()["results"][0]["status"] == "applied"
