"""Client-lifecycle acceptance tests for the Android Sync V2 contract."""

from copy import deepcopy
from uuid import uuid4

import pytest
from src.auth.models import User
from src.auth.service import get_password_hash


async def register_account(test_client, async_session, username: str) -> dict[str, str]:
    async_session.add(User(username=username, password_hash=get_password_hash("TestPassword123!")))
    await async_session.commit()
    response = await test_client.post(
        "/api/v1/auth/login",
        json={"username": username, "password": "TestPassword123!"},
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
            "app_version": "2.0.0",
            "display_name": "Acceptance phone",
        },
    )
    assert response.status_code == 200, response.text
    return response.json()["device_id"]


async def push(test_client, token: str, device_id: str, operations: list[dict]):
    return await test_client.post(
        "/api/v2/sync/push",
        headers={"Authorization": f"Bearer {token}"},
        json={"device_id": device_id, "operations": operations},
    )


def goal_operation(entity_uuid: str, title: str = "Health") -> dict:
    return {
        "operation_id": str(uuid4()),
        "entity_type": "plan_node",
        "entity_uuid": entity_uuid,
        "action": "upsert",
        "base_revision": None,
        "payload": {
            "node_kind": "goal",
            "title": title,
            "goal": {"evaluation_policy": {"schema_version": 1, "type": "manual"}},
        },
    }


def activity_operation(entity_uuid: str, parent_uuid: str | None = None) -> dict:
    return {
        "operation_id": str(uuid4()),
        "entity_type": "plan_node",
        "entity_uuid": entity_uuid,
        "action": "upsert",
        "base_revision": None,
        "payload": {
            "node_kind": "activity",
            "title": "Drink water",
            "parent_uuid": parent_uuid,
            "activity": {
                "tracking_mode": "check",
                "recurrence_rule": {"schema_version": 1, "type": "daily", "interval": 1},
                "completion_policy": "recurring",
                "target_value": "1",
                "failure_policy": {"schema_version": 1, "type": "strict"},
                "timezone": "Asia/Shanghai",
            },
        },
    }


@pytest.mark.asyncio
async def test_fresh_android_device_bootstraps_an_ordered_complete_snapshot(test_client, async_session):
    account = await register_account(test_client, async_session, "acceptbootstrap")
    token = account["access_token"]
    writer = await register_device(test_client, token, "accept-bootstrap-writer")
    reader = await register_device(test_client, token, "accept-bootstrap-reader")
    goal_uuid, activity_uuid, metric_uuid = str(uuid4()), str(uuid4()), str(uuid4())
    event_uuid, observation_uuid, link_uuid = str(uuid4()), str(uuid4()), str(uuid4())
    operations = [
        goal_operation(goal_uuid),
        activity_operation(activity_uuid, goal_uuid),
        {
            "operation_id": str(uuid4()),
            "entity_type": "metric",
            "entity_uuid": metric_uuid,
            "action": "upsert",
            "payload": {"name": "Weight", "unit": "kg", "decimal_places": 1},
        },
        {
            "operation_id": str(uuid4()),
            "entity_type": "activity_event",
            "entity_uuid": event_uuid,
            "action": "upsert",
            "payload": {
                "activity_uuid": activity_uuid,
                "event_type": "check_in",
                "occurred_at": "2026-08-10T08:00:00+08:00",
                "local_date": "2026-08-10",
                "timezone": "Asia/Shanghai",
                "source_type": "app",
            },
        },
        {
            "operation_id": str(uuid4()),
            "entity_type": "metric_observation",
            "entity_uuid": observation_uuid,
            "action": "upsert",
            "payload": {
                "metric_uuid": metric_uuid,
                "value": "60.5",
                "unit": "kg",
                "occurred_at": "2026-08-10T08:05:00+08:00",
                "local_date": "2026-08-10",
                "timezone": "Asia/Shanghai",
            },
        },
        {
            "operation_id": str(uuid4()),
            "entity_type": "activity_metric_link",
            "entity_uuid": link_uuid,
            "action": "upsert",
            "payload": {"activity_uuid": activity_uuid, "metric_uuid": metric_uuid},
        },
    ]
    response = await push(test_client, token, writer, operations)
    assert response.status_code == 200, response.text
    assert [result["status"] for result in response.json()["results"]] == ["applied"] * 6

    bootstrap = await test_client.get(
        "/api/v2/sync/bootstrap",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": reader},
    )
    assert bootstrap.status_code == 200, bootstrap.text
    body = bootstrap.json()
    uuids = [change["entity_uuid"] for change in body["changes"]]
    assert set(uuids) == {goal_uuid, activity_uuid, metric_uuid, event_uuid, observation_uuid, link_uuid}
    assert uuids.index(goal_uuid) < uuids.index(activity_uuid) < uuids.index(event_uuid)
    assert uuids.index(metric_uuid) < uuids.index(observation_uuid) < uuids.index(link_uuid)

    caught_up = await test_client.get(
        "/api/v2/sync/changes",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": reader, "cursor": body["next_cursor"]},
    )
    assert caught_up.status_code == 200, caught_up.text
    assert caught_up.json()["changes"] == []
    assert caught_up.json()["next_cursor"] == body["next_cursor"]


@pytest.mark.asyncio
async def test_offline_batch_replay_is_idempotent_and_visible_to_another_device(test_client, async_session):
    account = await register_account(test_client, async_session, "acceptoffline")
    token = account["access_token"]
    phone = await register_device(test_client, token, "accept-offline-phone")
    tablet = await register_device(test_client, token, "accept-offline-tablet")
    goal_uuid, activity_uuid = str(uuid4()), str(uuid4())
    queued = [goal_operation(goal_uuid), activity_operation(activity_uuid, goal_uuid)]

    first = await push(test_client, token, phone, queued)
    replay = await push(test_client, token, phone, deepcopy(queued))
    assert [item["status"] for item in first.json()["results"]] == ["applied", "applied"]
    assert [item["status"] for item in replay.json()["results"]] == [
        "already_applied",
        "already_applied",
    ]

    pulled = await test_client.get(
        "/api/v2/sync/changes",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": tablet, "cursor": 0},
    )
    body = pulled.json()
    assert [change["entity_uuid"] for change in body["changes"]] == [goal_uuid, activity_uuid]
    assert len({change["sequence"] for change in body["changes"]}) == 2


@pytest.mark.asyncio
async def test_duplicate_operation_inside_one_batch_is_applied_only_once(test_client, async_session):
    account = await register_account(test_client, async_session, "acceptsamebatch")
    token = account["access_token"]
    phone = await register_device(test_client, token, "accept-same-batch-phone")
    reader = await register_device(test_client, token, "accept-same-batch-reader")
    operation = goal_operation(str(uuid4()), "One durable operation")

    response = await push(test_client, token, phone, [operation, deepcopy(operation)])

    assert response.status_code == 200, response.text
    assert [item["status"] for item in response.json()["results"]] == [
        "applied",
        "already_applied",
    ]
    pulled = await test_client.get(
        "/api/v2/sync/changes",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": reader, "cursor": 0},
    )
    matching = [
        change for change in pulled.json()["changes"]
        if change["entity_uuid"] == operation["entity_uuid"]
    ]
    assert len(matching) == 1


@pytest.mark.asyncio
async def test_rejected_middle_operation_does_not_block_later_batch_items(test_client, async_session):
    account = await register_account(test_client, async_session, "acceptpartialbatch")
    token = account["access_token"]
    phone = await register_device(test_client, token, "accept-partial-batch-phone")
    reader = await register_device(test_client, token, "accept-partial-batch-reader")
    first = goal_operation(str(uuid4()), "First valid goal")
    rejected = activity_operation(str(uuid4()), str(uuid4()))
    last = goal_operation(str(uuid4()), "Last valid goal")

    response = await push(test_client, token, phone, [first, rejected, last])

    assert response.status_code == 200, response.text
    results = response.json()["results"]
    assert [item["status"] for item in results] == ["applied", "rejected", "applied"]
    assert results[1]["error_code"] == "PARENT_NOT_FOUND"
    pulled = await test_client.get(
        "/api/v2/sync/changes",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": reader, "cursor": 0},
    )
    assert [change["entity_uuid"] for change in pulled.json()["changes"]] == [
        first["entity_uuid"],
        last["entity_uuid"],
    ]


@pytest.mark.asyncio
async def test_pull_pagination_has_strict_monotonic_resumable_cursors(test_client, async_session):
    account = await register_account(test_client, async_session, "acceptpagination")
    token = account["access_token"]
    writer = await register_device(test_client, token, "accept-pagination-writer")
    reader = await register_device(test_client, token, "accept-pagination-reader")
    operations = [
        goal_operation(str(uuid4()), f"Paginated goal {index}")
        for index in range(3)
    ]
    created = await push(test_client, token, writer, operations)
    assert [item["status"] for item in created.json()["results"]] == ["applied"] * 3

    cursor = 0
    sequences: list[int] = []
    entity_uuids: list[str] = []
    has_more_values: list[bool] = []
    for _ in range(3):
        page = await test_client.get(
            "/api/v2/sync/changes",
            headers={"Authorization": f"Bearer {token}"},
            params={"device_id": reader, "cursor": cursor, "limit": 1},
        )
        assert page.status_code == 200, page.text
        body = page.json()
        assert len(body["changes"]) == 1
        change = body["changes"][0]
        assert change["sequence"] > cursor
        assert body["next_cursor"] == change["sequence"]
        cursor = body["next_cursor"]
        sequences.append(change["sequence"])
        entity_uuids.append(change["entity_uuid"])
        has_more_values.append(body["has_more"])

    assert sequences == sorted(set(sequences))
    assert entity_uuids == [operation["entity_uuid"] for operation in operations]
    assert has_more_values == [True, True, False]

    caught_up = await test_client.get(
        "/api/v2/sync/changes",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": reader, "cursor": cursor, "limit": 1},
    )
    assert caught_up.status_code == 200, caught_up.text
    assert caught_up.json()["changes"] == []
    assert caught_up.json()["next_cursor"] == cursor
    assert caught_up.json()["has_more"] is False


@pytest.mark.asyncio
async def test_cursor_ahead_of_server_is_rejected_without_breaking_valid_resume(test_client, async_session):
    account = await register_account(test_client, async_session, "acceptcursorerror")
    token = account["access_token"]
    writer = await register_device(test_client, token, "accept-cursor-writer")
    reader = await register_device(test_client, token, "accept-cursor-reader")
    operation = goal_operation(str(uuid4()), "Cursor recovery goal")
    assert (await push(test_client, token, writer, [operation])).json()["results"][0]["status"] == "applied"

    invalid = await test_client.get(
        "/api/v2/sync/changes",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": reader, "cursor": 999_999},
    )
    assert invalid.status_code == 400
    assert invalid.json()["detail"]["code"] == "INVALID_CURSOR"

    resumed = await test_client.get(
        "/api/v2/sync/changes",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": reader, "cursor": 0},
    )
    assert resumed.status_code == 200, resumed.text
    assert [change["entity_uuid"] for change in resumed.json()["changes"]] == [
        operation["entity_uuid"]
    ]


@pytest.mark.asyncio
async def test_accounts_with_the_same_entity_uuid_remain_fully_isolated(test_client, async_session):
    shared_uuid = str(uuid4())
    first = await register_account(test_client, async_session, "acceptisolationa")
    second = await register_account(test_client, async_session, "acceptisolationb")
    first_device = await register_device(test_client, first["access_token"], "accept-isolation-a")
    second_device = await register_device(test_client, second["access_token"], "accept-isolation-b")
    assert (await push(
        test_client,
        first["access_token"],
        first_device,
        [goal_operation(shared_uuid, "First account")],
    )).json()["results"][0]["status"] == "applied"
    assert (await push(
        test_client,
        second["access_token"],
        second_device,
        [goal_operation(shared_uuid, "Second account")],
    )).json()["results"][0]["status"] == "applied"

    for account, device, expected_title in (
        (first, first_device, "First account"),
        (second, second_device, "Second account"),
    ):
        snapshot = await test_client.get(
            "/api/v2/sync/bootstrap",
            headers={"Authorization": f"Bearer {account['access_token']}"},
            params={"device_id": device},
        )
        changes = snapshot.json()["changes"]
        assert len(changes) == 1
        assert changes[0]["entity_uuid"] == shared_uuid
        assert changes[0]["payload"]["title"] == expected_title

    stolen_device = await test_client.get(
        "/api/v2/sync/bootstrap",
        headers={"Authorization": f"Bearer {second['access_token']}"},
        params={"device_id": first_device},
    )
    assert stolen_device.status_code == 404
    assert stolen_device.json()["detail"]["code"] == "DEVICE_NOT_FOUND"


@pytest.mark.asyncio
async def test_rejected_offline_change_does_not_poison_canonical_bootstrap(test_client, async_session):
    account = await register_account(test_client, async_session, "acceptrecovery")
    token = account["access_token"]
    device = await register_device(test_client, token, "accept-recovery-phone")
    missing_parent = str(uuid4())
    activity_uuid = str(uuid4())
    rejected = activity_operation(activity_uuid, missing_parent)

    result = (await push(test_client, token, device, [rejected])).json()["results"][0]
    assert result["status"] == "rejected"
    assert result["error_code"] == "PARENT_NOT_FOUND"
    empty_snapshot = await test_client.get(
        "/api/v2/sync/bootstrap",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": device},
    )
    assert empty_snapshot.json()["changes"] == []

    goal = goal_operation(missing_parent, "Recovered parent")
    corrected = deepcopy(rejected)
    corrected["operation_id"] = str(uuid4())
    fixed = await push(test_client, token, device, [goal, corrected])
    assert [item["status"] for item in fixed.json()["results"]] == ["applied", "applied"]
    snapshot = await test_client.get(
        "/api/v2/sync/bootstrap",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": device},
    )
    assert [change["entity_uuid"] for change in snapshot.json()["changes"]] == [
        missing_parent,
        activity_uuid,
    ]


@pytest.mark.parametrize(
    "path",
    [
        "/api/v1/sync",
        "/api/v1/habits",
        "/api/v1/metrics",
        "/api/v1/habit-metric-links",
    ],
)
async def test_legacy_data_endpoints_are_not_exposed(test_client, path):
    response = await test_client.get(path)

    assert response.status_code == 404


async def test_openapi_contains_no_legacy_data_route_descendants(test_client):
    response = await test_client.get("/openapi.json")

    assert response.status_code == 200
    paths = response.json()["paths"]
    retired_prefixes = (
        "/api/v1/sync",
        "/api/v1/habits",
        "/api/v1/metrics",
        "/api/v1/habit-metric-links",
    )
    assert not {
        path
        for path in paths
        if any(path == prefix or path.startswith(f"{prefix}/") for prefix in retired_prefixes)
    }
