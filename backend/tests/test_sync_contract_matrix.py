"""Executable cross-stack fixtures and Sync V2 failure-recovery scenarios."""

import json
from copy import deepcopy
from pathlib import Path

import pytest
from pydantic import BaseModel, ValidationError
from src.auth.models import User
from tests.account_fixtures import TEST_ACCOUNT_PASSWORD as PASSWORD, account_password_hash
from src.v2.schemas import (
    ServerIdentityResponse,
    SyncBootstrapResponse,
    SyncPullResponse,
    SyncPushRequest,
    SyncPushResponse,
    TimerCommandBatchRequest,
    TimerCommandBatchResponse,
)


FIXTURES = Path(__file__).resolve().parents[2] / "contracts" / "sync-v2"
VALID_FIXTURES: list[tuple[str, type[BaseModel]]] = [
    ("client/push-all-entities.json", SyncPushRequest),
    ("client/delete-goal.json", SyncPushRequest),
    ("client/conflict-create.json", SyncPushRequest),
    ("client/conflict-remote.json", SyncPushRequest),
    ("client/conflict-stale.json", SyncPushRequest),
    ("client/account-a.json", SyncPushRequest),
    ("client/account-b.json", SyncPushRequest),
    ("client/timer-commands.json", TimerCommandBatchRequest),
    ("server/bootstrap-response.json", SyncBootstrapResponse),
    ("server/push-applied-response.json", SyncPushResponse),
    ("server/pull-with-tombstone-response.json", SyncPullResponse),
    ("server/conflict-response.json", SyncPushResponse),
    ("server/identity-before-response.json", ServerIdentityResponse),
    ("server/identity-after-epoch-reset-response.json", ServerIdentityResponse),
    ("server/timer-commands-response.json", TimerCommandBatchResponse),
]
INVALID_FIXTURES = {"invalid/push-missing-operation-id.json"}


def fixture(path: str) -> dict:
    with (FIXTURES / path).open(encoding="utf-8") as source:
        return json.load(source)


def with_device(request: dict, device_id: str) -> dict:
    result = deepcopy(request)
    result["device_id"] = device_id
    return result


async def register_account(test_client, async_session, username: str) -> dict[str, str]:
    async_session.add(User(username=username, password_hash=account_password_hash()))
    await async_session.commit()
    response = await test_client.post(
        "/api/v1/auth/login",
        json={"username": username, "password": PASSWORD},
    )
    assert response.status_code == 200, response.text
    return response.json()


async def register_device(test_client, token: str, installation_id: str) -> str:
    response = await test_client.post(
        "/api/v2/devices/register",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "installation_id": installation_id,
            "protocol_version": 4,
            "platform": "android",
            "device_class": "interactive",
            "app_version": "2.0",
            "display_name": "Contract fixture device",
        },
    )
    assert response.status_code == 200, response.text
    return response.json()["device_id"]


async def push(test_client, token: str, body: dict):
    return await test_client.post(
        "/api/v2/sync/push",
        headers={"Authorization": f"Bearer {token}"},
        json=body,
    )


@pytest.mark.parametrize(
    ("path", "model"),
    VALID_FIXTURES,
)
def test_contract_fixture_has_a_stable_typed_round_trip(path: str, model: type[BaseModel]):
    parsed = model.model_validate(fixture(path))
    first = parsed.model_dump_json(exclude_none=True)
    reparsed = model.model_validate_json(first)
    second = reparsed.model_dump_json(exclude_none=True)

    assert reparsed == parsed
    assert second == first


def test_every_json_fixture_is_registered_in_the_contract_suite():
    discovered = {
        str(path.relative_to(FIXTURES))
        for path in FIXTURES.rglob("*.json")
    }
    registered = {path for path, _ in VALID_FIXTURES} | INVALID_FIXTURES

    assert discovered == registered


def test_malformed_client_fixture_is_rejected_by_the_backend_schema():
    with pytest.raises(ValidationError):
        SyncPushRequest.model_validate(fixture("invalid/push-missing-operation-id.json"))


@pytest.mark.asyncio
async def test_live_server_identity_matches_the_versioned_fixture(test_client):
    expected = fixture("server/identity-before-response.json")
    response = await test_client.get("/api/v2/system/identity")

    assert response.status_code == 200, response.text
    assert response.json()["protocol_version"] == expected["protocol_version"]
    assert response.json()["capabilities"] == expected["capabilities"]


@pytest.mark.asyncio
async def test_lost_response_replay_timezones_and_tombstones_share_one_fixture_matrix(
    test_client,
    async_session,
):
    account = await register_account(test_client, async_session, "contractrecovery")
    token = account["access_token"]
    writer = await register_device(test_client, token, "contract-recovery-writer")
    reader = await register_device(test_client, token, "contract-recovery-reader")
    queued = with_device(fixture("client/push-all-entities.json"), writer)

    first = await push(test_client, token, queued)
    replay_after_lost_response = await push(test_client, token, deepcopy(queued))

    assert first.status_code == 200, first.text
    assert [item["status"] for item in first.json()["results"]] == ["applied"] * 7
    assert [item["status"] for item in replay_after_lost_response.json()["results"]] == [
        "already_applied"
    ] * 7

    pulled = await test_client.get(
        "/api/v2/sync/changes",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": reader, "cursor": 0},
    )
    assert pulled.status_code == 200, pulled.text
    changes = pulled.json()["changes"]
    assert len(changes) == 7
    by_uuid = {change["entity_uuid"]: change for change in changes}
    event = by_uuid["40000000-0000-4000-8000-000000000001"]["payload"]
    assert event["local_date"] == "2026-11-01"
    assert event["timezone"] == "America/New_York"
    observation = by_uuid["50000000-0000-4000-8000-000000000001"]["payload"]
    assert observation["occurred_at"] == "2026-01-01T07:30:00Z"
    assert observation["local_date"] == "2025-12-31"
    assert observation["timezone"] == "America/Los_Angeles"

    deletion = with_device(fixture("client/delete-goal.json"), writer)
    deleted = await push(test_client, token, deletion)
    assert deleted.status_code == 200, deleted.text
    assert deleted.json()["results"][0]["status"] == "applied"

    after_delete = await test_client.get(
        "/api/v2/sync/changes",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": reader, "cursor": pulled.json()["next_cursor"]},
    )
    tombstones = {
        change["entity_uuid"]
        for change in after_delete.json()["changes"]
        if change["operation"] == "delete"
    }
    assert {
        "10000000-0000-4000-8000-000000000001",
        "20000000-0000-4000-8000-000000000001",
        "20000000-0000-4000-8000-000000000002",
    } <= tombstones


@pytest.mark.asyncio
async def test_shared_conflict_fixtures_produce_the_canonical_conflict_shape(
    test_client,
    async_session,
):
    account = await register_account(test_client, async_session, "contractconflict")
    token = account["access_token"]
    device = await register_device(test_client, token, "contract-conflict-device")
    for path in ("client/conflict-create.json", "client/conflict-remote.json"):
        response = await push(test_client, token, with_device(fixture(path), device))
        assert response.status_code == 200, response.text
        assert response.json()["results"][0]["status"] == "applied"

    response = await push(
        test_client,
        token,
        with_device(fixture("client/conflict-stale.json"), device),
    )
    actual = response.json()["results"][0]
    expected = fixture("server/conflict-response.json")["results"][0]

    assert actual["status"] == expected["status"]
    assert actual["error_code"] == expected["error_code"]
    assert actual["message"] == expected["message"]
    assert actual["conflicting_fields"] == expected["conflicting_fields"]
    assert actual["conflict_kind"] == expected["conflict_kind"]
    assert actual["base_entity"]["title"] == expected["base_entity"]["title"]
    assert actual["local_entity"]["title"] == expected["local_entity"]["title"]
    assert actual["entity"]["title"] == expected["entity"]["title"]


@pytest.mark.asyncio
async def test_same_public_entity_id_is_isolated_by_authenticated_account(
    test_client,
    async_session,
):
    first = await register_account(test_client, async_session, "contractaccounta")
    second = await register_account(test_client, async_session, "contractaccountb")
    first_device = await register_device(
        test_client, first["access_token"], "contract-account-a"
    )
    second_device = await register_device(
        test_client, second["access_token"], "contract-account-b"
    )

    first_push = await push(
        test_client,
        first["access_token"],
        with_device(fixture("client/account-a.json"), first_device),
    )
    second_push = await push(
        test_client,
        second["access_token"],
        with_device(fixture("client/account-b.json"), second_device),
    )
    assert first_push.json()["results"][0]["status"] == "applied"
    assert second_push.json()["results"][0]["status"] == "applied"

    for account, device, expected in (
        (first, first_device, "First account"),
        (second, second_device, "Second account"),
    ):
        response = await test_client.get(
            "/api/v2/sync/bootstrap",
            headers={"Authorization": f"Bearer {account['access_token']}"},
            params={"device_id": device},
        )
        assert response.status_code == 200, response.text
        assert [item["payload"]["title"] for item in response.json()["changes"]] == [expected]

    stolen = await test_client.get(
        "/api/v2/sync/bootstrap",
        headers={"Authorization": f"Bearer {second['access_token']}"},
        params={"device_id": first_device},
    )
    assert stolen.status_code == 404
    assert stolen.json()["detail"]["code"] == "DEVICE_NOT_FOUND"


@pytest.mark.asyncio
async def test_ordered_cross_midnight_timer_fixture_is_idempotent(
    test_client,
    async_session,
):
    account = await register_account(test_client, async_session, "contracttimer")
    token = account["access_token"]
    device = await register_device(test_client, token, "contract-timer-device")
    setup = with_device(fixture("client/push-all-entities.json"), device)
    created = await push(test_client, token, setup)
    assert [item["status"] for item in created.json()["results"]] == ["applied"] * 7

    commands = with_device(fixture("client/timer-commands.json"), device)
    first = await test_client.post(
        "/api/v2/timers/commands",
        headers={"Authorization": f"Bearer {token}"},
        json=commands,
    )
    replay = await test_client.post(
        "/api/v2/timers/commands",
        headers={"Authorization": f"Bearer {token}"},
        json=deepcopy(commands),
    )

    assert first.status_code == 200, first.text
    assert [item["status"] for item in first.json()["results"]] == ["applied"] * 4
    assert first.json()["results"][-1]["session"]["state"] == "completed"
    assert first.json()["results"][-1]["session"]["active_elapsed_ms"] == 60_000
    assert [item["status"] for item in replay.json()["results"]] == [
        "already_applied"
    ] * 4

    pulled = await test_client.get(
        "/api/v2/sync/changes",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": device, "cursor": 0},
    )
    timer_event = next(
        item
        for item in pulled.json()["changes"]
        if item["entity_uuid"] == "80000000-0000-4000-8000-000000000001"
    )
    assert timer_event["payload"]["day_allocations"] == [
        {
            "local_date": "2026-08-03",
            "timezone": "Asia/Shanghai",
            "duration_milliseconds": 30_000,
        },
        {
            "local_date": "2026-08-04",
            "timezone": "Asia/Shanghai",
            "duration_milliseconds": 30_000,
        },
    ]


@pytest.mark.asyncio
async def test_malformed_fixture_returns_422_without_creating_a_change(
    test_client,
    async_session,
):
    account = await register_account(test_client, async_session, "contractmalformed")
    token = account["access_token"]
    device = await register_device(test_client, token, "contract-malformed-device")
    malformed = with_device(fixture("invalid/push-missing-operation-id.json"), device)

    response = await push(test_client, token, malformed)
    assert response.status_code == 422

    snapshot = await test_client.get(
        "/api/v2/sync/bootstrap",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": device},
    )
    assert snapshot.status_code == 200, snapshot.text
    assert snapshot.json()["changes"] == []
