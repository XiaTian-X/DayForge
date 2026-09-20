"""Contract and invariant tests for the v2 incremental sync protocol."""

from copy import deepcopy
from typing import Any
from datetime import datetime
from uuid import uuid4

import pytest
from sqlalchemy import delete
from sqlmodel import col
from src.auth.models import User
from tests.account_fixtures import TEST_ACCOUNT_PASSWORD, account_password_hash
from src.v2.models import EntityRevisionSnapshot


async def register_account(test_client, async_session, username: str) -> dict[str, str]:
    async_session.add(User(username=username, password_hash=account_password_hash()))
    await async_session.commit()
    response = await test_client.post(
        "/api/v1/auth/login",
        json={"username": username, "password": TEST_ACCOUNT_PASSWORD},
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["user_id"]
    assert body["username"] == username
    return body


async def register_device(test_client, token: str, installation_id: str) -> str:
    response = await test_client.post(
        "/api/v2/devices/register",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "installation_id": installation_id,
            "protocol_version": 4,
            "platform": "android",
            "app_version": "2.0.0",
            "display_name": "Test phone",
        },
    )
    assert response.status_code == 200, response.text
    return response.json()["device_id"]


def goal_operation(*, entity_uuid=None, operation_id=None, title="Health") -> dict:
    return {
        "operation_id": str(operation_id or uuid4()),
        "entity_type": "plan_node",
        "entity_uuid": str(entity_uuid or uuid4()),
        "action": "upsert",
        "base_revision": None,
        "payload": {
            "node_kind": "goal",
            "title": title,
            "goal": {"evaluation_policy": {"schema_version": 1, "type": "manual"}},
        },
    }


def activity_operation(
    *,
    parent_uuid=None,
    entity_uuid=None,
    operation_id=None,
    tracking_mode="check",
    is_countdown=False,
    target_value=None,
) -> dict:
    effective_target = (
        target_value
        if target_value is not None
        else ("60" if tracking_mode == "duration" else "1")
    )
    return {
        "operation_id": str(operation_id or uuid4()),
        "entity_type": "plan_node",
        "entity_uuid": str(entity_uuid or uuid4()),
        "action": "upsert",
        "base_revision": None,
        "payload": {
            "node_kind": "activity",
            "title": "Drink water",
            "parent_uuid": str(parent_uuid) if parent_uuid else None,
            "activity": {
                "tracking_mode": tracking_mode,
                "is_countdown": is_countdown,
                "recurrence_rule": {
                    "schema_version": 1,
                    "type": "daily",
                    "interval": 1,
                },
                "completion_policy": "recurring",
                "target_value": effective_target,
                "target_unit": "second" if tracking_mode == "duration" else None,
                "failure_policy": {"schema_version": 1, "type": "strict"},
                "timezone": "Asia/Shanghai",
            },
        },
    }


async def push(test_client, token: str, device_id: str, operations: list[dict]):
    return await test_client.post(
        "/api/v2/sync/push",
        headers={"Authorization": f"Bearer {token}"},
        json={"device_id": device_id, "operations": operations},
    )


@pytest.mark.asyncio
async def test_push_pull_idempotency_and_revision_conflict(test_client, async_session):
    tokens = await register_account(test_client, async_session, "syncv2user")
    token = tokens["access_token"]
    device_id = await register_device(test_client, token, "android-install-0001")

    goal_uuid = uuid4()
    create = goal_operation(entity_uuid=goal_uuid)
    response = await push(test_client, token, device_id, [create])
    assert response.status_code == 200, response.text
    created = response.json()["results"][0]
    assert created["status"] == "applied"
    assert created["revision"] == 1

    retry = await push(test_client, token, device_id, [create])
    assert retry.status_code == 200
    assert retry.json()["results"][0]["status"] == "already_applied"

    update = goal_operation(entity_uuid=goal_uuid, title="Updated health")
    update["base_revision"] = 1
    updated = await push(test_client, token, device_id, [update])
    assert updated.json()["results"][0]["status"] == "applied"
    assert updated.json()["results"][0]["revision"] == 2

    stale = goal_operation(entity_uuid=goal_uuid, title="Stale title")
    stale["base_revision"] = 1
    conflict = await push(test_client, token, device_id, [stale])
    result = conflict.json()["results"][0]
    assert result["status"] == "conflict"
    assert result["error_code"] == "REVISION_CONFLICT"
    assert result["revision"] == 2
    assert result["entity"]["title"] == "Updated health"

    pull = await test_client.get(
        "/api/v2/sync/changes",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": device_id, "cursor": 0, "limit": 20},
    )
    assert pull.status_code == 200, pull.text
    changes = pull.json()["changes"]
    assert [change["revision"] for change in changes] == [1, 2]
    assert pull.json()["next_cursor"] >= 2


@pytest.mark.asyncio
async def test_stale_structural_edits_on_different_fields_are_three_way_merged(
    test_client,
    async_session,
):
    tokens = await register_account(test_client, async_session, "syncmergeuser")
    token = tokens["access_token"]
    device_id = await register_device(test_client, token, "android-merge")
    goal_uuid = uuid4()

    created = await push(
        test_client, token, device_id, [goal_operation(entity_uuid=goal_uuid)]
    )
    assert created.json()["results"][0]["revision"] == 1

    remote = goal_operation(entity_uuid=goal_uuid, title="Remote title")
    remote["base_revision"] = 1
    remote_result = await push(test_client, token, device_id, [remote])
    assert remote_result.json()["results"][0]["revision"] == 2

    local = goal_operation(entity_uuid=goal_uuid)
    local["base_revision"] = 1
    local["payload"]["description"] = "Local description"
    merged = await push(test_client, token, device_id, [local])
    result = merged.json()["results"][0]

    assert result["status"] == "applied"
    assert result["revision"] == 3
    assert result["entity"]["title"] == "Remote title"
    assert result["entity"]["description"] == "Local description"


@pytest.mark.asyncio
async def test_stale_same_field_conflict_returns_base_local_and_server_versions(
    test_client,
    async_session,
):
    tokens = await register_account(test_client, async_session, "syncconflictversions")
    token = tokens["access_token"]
    device_id = await register_device(test_client, token, "android-conflict-versions")
    goal_uuid = uuid4()

    await push(test_client, token, device_id, [goal_operation(entity_uuid=goal_uuid)])
    remote = goal_operation(entity_uuid=goal_uuid, title="Remote title")
    remote["base_revision"] = 1
    await push(test_client, token, device_id, [remote])

    local = goal_operation(entity_uuid=goal_uuid, title="Local title")
    local["base_revision"] = 1
    response = await push(test_client, token, device_id, [local])
    result = response.json()["results"][0]

    assert result["status"] == "conflict"
    assert result["error_code"] == "REVISION_CONFLICT"
    assert result["conflict_kind"] == "overlapping_fields"
    assert result["conflicting_fields"] == ["title"]
    assert result["base_entity"]["title"] == "Health"
    assert result["local_entity"]["title"] == "Local title"
    assert result["entity"]["title"] == "Remote title"


@pytest.mark.asyncio
async def test_stale_same_value_is_applied_without_creating_an_extra_revision(
    test_client,
    async_session,
):
    tokens = await register_account(test_client, async_session, "syncsamenoversion")
    token = tokens["access_token"]
    device_id = await register_device(test_client, token, "android-same-value")
    goal_uuid = uuid4()

    await push(test_client, token, device_id, [goal_operation(entity_uuid=goal_uuid)])
    remote = goal_operation(entity_uuid=goal_uuid, title="Shared title")
    remote["base_revision"] = 1
    await push(test_client, token, device_id, [remote])

    same = goal_operation(entity_uuid=goal_uuid, title="Shared title")
    same["base_revision"] = 1
    response = await push(test_client, token, device_id, [same])
    result = response.json()["results"][0]

    assert result["status"] == "applied"
    assert result["revision"] == 2
    assert result["entity"]["title"] == "Shared title"

    pull = await test_client.get(
        "/api/v2/sync/changes",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": device_id, "cursor": 0, "limit": 20},
    )
    assert [change["revision"] for change in pull.json()["changes"]] == [1, 2]


@pytest.mark.asyncio
async def test_deleted_structural_entity_is_not_resurrected_by_a_stale_update(
    test_client,
    async_session,
):
    tokens = await register_account(test_client, async_session, "syncdeletenoresurrect")
    token = tokens["access_token"]
    device_id = await register_device(test_client, token, "android-delete-no-resurrect")
    goal_uuid = uuid4()

    await push(test_client, token, device_id, [goal_operation(entity_uuid=goal_uuid)])
    deletion = {
        "operation_id": str(uuid4()),
        "entity_type": "plan_node",
        "entity_uuid": str(goal_uuid),
        "action": "delete",
        "base_revision": 1,
        "payload": {},
    }
    deleted = await push(test_client, token, device_id, [deletion])
    assert deleted.json()["results"][0]["revision"] == 2

    stale = goal_operation(entity_uuid=goal_uuid, title="Must not return")
    stale["base_revision"] = 1
    response = await push(test_client, token, device_id, [stale])
    result = response.json()["results"][0]

    assert result["status"] == "conflict"
    assert result["error_code"] == "ENTITY_DELETED"
    assert result["entity"]["deleted_at"] is not None


@pytest.mark.asyncio
async def test_missing_merge_base_is_reported_instead_of_guessing(
    test_client,
    async_session,
):
    tokens = await register_account(test_client, async_session, "syncmissingbase")
    token = tokens["access_token"]
    device_id = await register_device(test_client, token, "android-missing-base")
    goal_uuid = uuid4()

    await push(test_client, token, device_id, [goal_operation(entity_uuid=goal_uuid)])
    remote = goal_operation(entity_uuid=goal_uuid, title="Remote title")
    remote["base_revision"] = 1
    await push(test_client, token, device_id, [remote])
    await async_session.execute(
        delete(EntityRevisionSnapshot).where(
            col(EntityRevisionSnapshot.entity_uuid) == str(goal_uuid),
            col(EntityRevisionSnapshot.revision) == 1,
        )
    )
    await async_session.commit()

    stale = goal_operation(entity_uuid=goal_uuid)
    stale["base_revision"] = 1
    stale["payload"]["description"] = "Cannot safely merge"
    response = await push(test_client, token, device_id, [stale])
    result = response.json()["results"][0]

    assert result["status"] == "conflict"
    assert result["error_code"] == "BASE_SNAPSHOT_UNAVAILABLE"
    assert result["conflict_kind"] == "base_snapshot_unavailable"
    assert result["local_entity"]["description"] == "Cannot safely merge"
    assert result["entity"]["title"] == "Remote title"


def metric_operation(*, entity_uuid=None, name="Weight", target_value="70") -> dict:
    """A metric carrying a Decimal target, unlike the Decimal-free goal payload."""
    payload = {
        "name": name,
        "unit": "kg",
        "decimal_places": 1,
        "target_direction": "increase",
    }
    if target_value is not None:
        payload["target_value"] = target_value
    return {
        "operation_id": str(uuid4()),
        "entity_type": "metric",
        "entity_uuid": str(entity_uuid or uuid4()),
        "action": "upsert",
        "base_revision": None,
        "payload": payload,
    }


@pytest.mark.asyncio
async def test_stale_activity_decimal_field_merges_instead_of_conflicting(
    test_client,
    async_session,
):
    """A Decimal field the local device never touched must not fabricate a conflict.

    plan_node.activity.target_value is a Decimal. Pydantic JSON mode renders it as
    a string while the stored snapshot renders it as a number, so an asymmetric
    encoder made every activity report target_value as locally modified.
    """
    tokens = await register_account(test_client, async_session, "syncmergeactivity")
    token = tokens["access_token"]
    device_id = await register_device(test_client, token, "android-merge-activity")
    activity_uuid = uuid4()

    base = activity_operation(
        entity_uuid=activity_uuid, tracking_mode="count", target_value="1"
    )
    created = await push(test_client, token, device_id, [base])
    assert created.json()["results"][0]["revision"] == 1

    remote = deepcopy(base)
    remote["operation_id"] = str(uuid4())
    remote["base_revision"] = 1
    remote["payload"]["activity"]["target_value"] = "5"
    assert (await push(test_client, token, device_id, [remote])).json()["results"][0][
        "revision"
    ] == 2

    # The stale device only renames the activity and resends the unchanged target.
    local = deepcopy(base)
    local["operation_id"] = str(uuid4())
    local["base_revision"] = 1
    local["payload"]["title"] = "Local title"
    result = (await push(test_client, token, device_id, [local])).json()["results"][0]

    assert result["status"] == "applied", result
    assert result["conflicting_fields"] == []
    assert result["revision"] == 3
    assert result["entity"]["title"] == "Local title"
    assert float(result["entity"]["activity"]["target_value"]) == 5.0


@pytest.mark.asyncio
async def test_stale_metric_decimal_field_merges_instead_of_conflicting(
    test_client,
    async_session,
):
    """metric.target_value and target_value_upper are Decimal and must merge."""
    tokens = await register_account(test_client, async_session, "syncmergemetric")
    token = tokens["access_token"]
    device_id = await register_device(test_client, token, "android-merge-metric")
    metric_uuid = uuid4()

    base = metric_operation(entity_uuid=metric_uuid)
    assert (await push(test_client, token, device_id, [base])).json()["results"][0][
        "revision"
    ] == 1

    remote = deepcopy(base)
    remote["operation_id"] = str(uuid4())
    remote["base_revision"] = 1
    remote["payload"]["target_value"] = "80"
    assert (await push(test_client, token, device_id, [remote])).json()["results"][0][
        "revision"
    ] == 2

    local = deepcopy(base)
    local["operation_id"] = str(uuid4())
    local["base_revision"] = 1
    local["payload"]["name"] = "Body weight"
    result = (await push(test_client, token, device_id, [local])).json()["results"][0]

    assert result["status"] == "applied", result
    assert result["conflicting_fields"] == []
    assert result["entity"]["name"] == "Body weight"
    assert float(result["entity"]["target_value"]) == 80.0


@pytest.mark.asyncio
async def test_stale_link_coefficient_merges_instead_of_conflicting(
    test_client,
    async_session,
):
    """activity_metric_link.coefficient is a Decimal and must merge."""
    tokens = await register_account(test_client, async_session, "syncmergelink")
    token = tokens["access_token"]
    device_id = await register_device(test_client, token, "android-merge-link")

    activity = activity_operation()
    metric = metric_operation(target_value=None)
    link: dict[str, Any] = {
        "operation_id": str(uuid4()),
        "entity_type": "activity_metric_link",
        "entity_uuid": str(uuid4()),
        "action": "upsert",
        "base_revision": None,
        "payload": {
            "activity_uuid": activity["entity_uuid"],
            "metric_uuid": metric["entity_uuid"],
            "coefficient": "1",
        },
    }
    created = await push(test_client, token, device_id, [activity, metric, link])
    assert [item["status"] for item in created.json()["results"]] == ["applied"] * 3

    remote = deepcopy(link)
    remote["operation_id"] = str(uuid4())
    remote["base_revision"] = 1
    remote["payload"]["coefficient"] = "2.5"
    assert (await push(test_client, token, device_id, [remote])).json()["results"][0][
        "revision"
    ] == 2

    # The stale device only flips a boolean and resends the unchanged coefficient.
    local = deepcopy(link)
    local["operation_id"] = str(uuid4())
    local["base_revision"] = 1
    local["payload"]["prompt_on_complete"] = True
    result = (await push(test_client, token, device_id, [local])).json()["results"][0]

    assert result["status"] == "applied", result
    assert result["conflicting_fields"] == []
    assert result["entity"]["prompt_on_complete"] is True
    assert float(result["entity"]["coefficient"]) == 2.5


@pytest.mark.asyncio
async def test_decimal_entities_still_conflict_when_the_same_field_diverges(
    test_client,
    async_session,
):
    """Fixing the encoder must not stop genuine Decimal conflicts from surfacing."""
    tokens = await register_account(
        test_client, async_session, "syncmergedecimalconflict"
    )
    token = tokens["access_token"]
    device_id = await register_device(
        test_client, token, "android-merge-decimal-conflict"
    )
    activity_uuid = uuid4()

    base = activity_operation(
        entity_uuid=activity_uuid, tracking_mode="count", target_value="1"
    )
    await push(test_client, token, device_id, [base])

    remote = deepcopy(base)
    remote["operation_id"] = str(uuid4())
    remote["base_revision"] = 1
    remote["payload"]["activity"]["target_value"] = "5"
    await push(test_client, token, device_id, [remote])

    # This device really did change the same Decimal field, to a different value.
    local = deepcopy(base)
    local["operation_id"] = str(uuid4())
    local["base_revision"] = 1
    local["payload"]["activity"]["target_value"] = "9"
    result = (await push(test_client, token, device_id, [local])).json()["results"][0]

    assert result["status"] == "conflict"
    assert result["error_code"] == "REVISION_CONFLICT"
    assert result["conflicting_fields"] == ["activity.target_value"]
    assert float(result["base_entity"]["activity"]["target_value"]) == 1.0
    assert float(result["local_entity"]["activity"]["target_value"]) == 9.0
    assert float(result["entity"]["activity"]["target_value"]) == 5.0


@pytest.mark.asyncio
async def test_stale_same_decimal_value_is_applied_without_creating_an_extra_revision(
    test_client,
    async_session,
):
    """The no-op projection check must also hold for entities carrying Decimals."""
    tokens = await register_account(test_client, async_session, "syncmergedecimalnoop")
    token = tokens["access_token"]
    device_id = await register_device(test_client, token, "android-merge-decimal-noop")
    activity_uuid = uuid4()

    base = activity_operation(
        entity_uuid=activity_uuid, tracking_mode="count", target_value="1"
    )
    await push(test_client, token, device_id, [base])

    remote = deepcopy(base)
    remote["operation_id"] = str(uuid4())
    remote["base_revision"] = 1
    remote["payload"]["title"] = "Shared title"
    await push(test_client, token, device_id, [remote])

    same = deepcopy(base)
    same["operation_id"] = str(uuid4())
    same["base_revision"] = 1
    same["payload"]["title"] = "Shared title"
    result = (await push(test_client, token, device_id, [same])).json()["results"][0]

    assert result["status"] == "applied"
    assert result["revision"] == 2, "an identical stale edit must not bump the revision"

    pull = await test_client.get(
        "/api/v2/sync/changes",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": device_id, "cursor": 0, "limit": 20},
    )
    assert [change["revision"] for change in pull.json()["changes"]] == [1, 2]


@pytest.mark.asyncio
async def test_android_configuration_fields_round_trip_without_loss(
    test_client, async_session
):
    tokens = await register_account(test_client, async_session, "syncfidelityuser")
    token = tokens["access_token"]
    device_id = await register_device(test_client, token, "android-fidelity")
    goal_uuid = uuid4()
    created_at = "2026-08-01T00:00:00Z"
    goal = goal_operation(entity_uuid=goal_uuid, title="Thirty day goal")
    goal["payload"].update(
        {
            "created_at": created_at,
            "goal": {
                "target_cycles": 30,
                "failure_policy": {"schema_version": 1, "type": "loose"},
                "evaluation_policy": {"schema_version": 1, "type": "manual"},
            },
        }
    )
    activities = [
        activity_operation(
            parent_uuid=goal_uuid,
            tracking_mode="count",
            is_countdown=False,
            target_value="5",
        ),
        activity_operation(
            parent_uuid=goal_uuid,
            tracking_mode="count",
            is_countdown=True,
            target_value="5",
        ),
        activity_operation(
            parent_uuid=goal_uuid,
            tracking_mode="duration",
            is_countdown=False,
            target_value="60",
        ),
        activity_operation(
            parent_uuid=goal_uuid,
            tracking_mode="duration",
            is_countdown=True,
            target_value="60",
        ),
    ]
    for index, activity in enumerate(activities):
        activity["payload"]["title"] = f"Fidelity activity {index}"

    response = await push(test_client, token, device_id, [goal, *activities])
    assert response.status_code == 200, response.text
    assert [item["status"] for item in response.json()["results"]] == ["applied"] * 5

    snapshot = await test_client.get(
        "/api/v2/sync/bootstrap",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": device_id},
    )
    assert snapshot.status_code == 200, snapshot.text
    entities = {
        item["entity_uuid"]: item["payload"] for item in snapshot.json()["changes"]
    }
    goal_payload = entities[str(goal_uuid)]
    assert goal_payload["created_at"] == created_at
    assert goal_payload["goal"]["target_cycles"] == 30
    assert goal_payload["goal"]["failure_policy"]["type"] == "loose"
    modes = [entities[item["entity_uuid"]]["activity"] for item in activities]
    assert [(mode["tracking_mode"], mode["is_countdown"]) for mode in modes] == [
        ("count", False),
        ("count", True),
        ("duration", False),
        ("duration", True),
    ]
    assert [mode["target_value"] for mode in modes] == [5.0, 5.0, 60.0, 60.0]


@pytest.mark.asyncio
async def test_android_incompatible_activity_shapes_are_rejected(
    test_client, async_session
):
    tokens = await register_account(test_client, async_session, "syncshapeuser")
    token = tokens["access_token"]
    device_id = await register_device(test_client, token, "android-shapes")
    operations = [
        activity_operation(tracking_mode="check", is_countdown=True),
        activity_operation(tracking_mode="count", target_value="1.5"),
        activity_operation(tracking_mode="duration", target_value="90"),
    ]
    for index, operation in enumerate(operations):
        operation["payload"]["title"] = f"Invalid shape {index}"

    response = await push(test_client, token, device_id, operations)
    assert response.status_code == 200, response.text
    assert [item["status"] for item in response.json()["results"]] == ["rejected"] * 3
    assert {item["error_code"] for item in response.json()["results"]} == {
        "INVALID_PAYLOAD"
    }


@pytest.mark.asyncio
async def test_android_incompatible_icons_and_node_statuses_are_rejected(
    test_client, async_session
):
    tokens = await register_account(test_client, async_session, "syncpresentationuser")
    token = tokens["access_token"]
    device_id = await register_device(test_client, token, "android-presentation")

    paused_activity = activity_operation()
    paused_activity["payload"].update(title="Paused activity", status="paused")
    unsupported_icon_activity = activity_operation()
    unsupported_icon_activity["payload"].update(
        title="Unknown icon", icon="future_platform_icon"
    )
    completed_without_result = goal_operation(title="Incomplete completed goal")
    completed_without_result["payload"]["status"] = "completed"
    unsupported_icon_metric = {
        "operation_id": str(uuid4()),
        "entity_type": "metric",
        "entity_uuid": str(uuid4()),
        "action": "upsert",
        "base_revision": None,
        "payload": {
            "name": "Unsupported icon metric",
            "unit": "point",
            "icon": "favorite",
        },
    }

    response = await push(
        test_client,
        token,
        device_id,
        [
            paused_activity,
            unsupported_icon_activity,
            completed_without_result,
            unsupported_icon_metric,
        ],
    )

    assert response.status_code == 200, response.text
    assert [item["status"] for item in response.json()["results"]] == ["rejected"] * 4
    assert {item["error_code"] for item in response.json()["results"]} == {
        "INVALID_PAYLOAD"
    }


@pytest.mark.asyncio
async def test_duplicate_active_habit_titles_are_rejected(test_client, async_session):
    tokens = await register_account(test_client, async_session, "syncduplicatetitle")
    token = tokens["access_token"]
    device_id = await register_device(test_client, token, "android-duplicate-title")
    first = activity_operation()
    second = activity_operation()

    response = await push(test_client, token, device_id, [first, second])

    assert response.status_code == 200, response.text
    assert [item["status"] for item in response.json()["results"]] == [
        "applied",
        "rejected",
    ]
    assert response.json()["results"][1]["error_code"] == "DUPLICATE_TITLE"


@pytest.mark.asyncio
async def test_android_update_preserves_server_only_plan_fields(
    test_client, async_session
):
    tokens = await register_account(test_client, async_session, "syncserverfields")
    token = tokens["access_token"]
    device_id = await register_device(test_client, token, "android-server-fields")
    goal_uuid = uuid4()
    activity_uuid = uuid4()
    assignment_uuid = uuid4()
    goal = goal_operation(entity_uuid=goal_uuid, title="Goal with dates")
    goal["payload"].update({"sort_order": 4})
    goal["payload"]["goal"].update(
        {"start_date": "2026-08-01", "due_date": "2026-08-31"}
    )
    activity = activity_operation(parent_uuid=goal_uuid, entity_uuid=activity_uuid)
    activity["payload"]["title"] = "Assigned activity"
    activity["payload"]["activity"]["origin_assignment_id"] = str(assignment_uuid)
    activity["payload"]["activity"]["recurrence_rule"]["start_date"] = "2026-08-01"
    created = await push(test_client, token, device_id, [goal, activity])
    assert [item["status"] for item in created.json()["results"]] == [
        "applied",
        "applied",
    ]

    android_goal_update = goal_operation(
        entity_uuid=goal_uuid, title="Goal edited on Android"
    )
    android_goal_update["base_revision"] = 1
    android_activity_update = activity_operation(
        parent_uuid=goal_uuid, entity_uuid=activity_uuid
    )
    android_activity_update["payload"]["title"] = "Activity edited on Android"
    android_activity_update["base_revision"] = 1
    updated = await push(
        test_client,
        token,
        device_id,
        [android_goal_update, android_activity_update],
    )

    assert [item["status"] for item in updated.json()["results"]] == [
        "applied",
        "applied",
    ]
    goal_entity, activity_entity = [
        item["entity"] for item in updated.json()["results"]
    ]
    assert goal_entity["goal"]["start_date"] == "2026-08-01"
    assert goal_entity["goal"]["due_date"] == "2026-08-31"
    assert goal_entity["sort_order"] == 4
    assert activity_entity["activity"]["origin_assignment_id"] == str(assignment_uuid)
    assert activity_entity["activity"]["recurrence_rule"]["start_date"] == "2026-08-01"


@pytest.mark.asyncio
async def test_single_parent_goal_rules_and_account_isolation(
    test_client, async_session
):
    first = await register_account(test_client, async_session, "firstv2user")
    first_device = await register_device(
        test_client, first["access_token"], "android-first-001"
    )
    second = await register_account(test_client, async_session, "secondv2user")
    second_device = await register_device(
        test_client, second["access_token"], "android-second-01"
    )

    first_goal = goal_operation()
    assert (
        await push(test_client, first["access_token"], first_device, [first_goal])
    ).json()["results"][0]["status"] == "applied"

    cross_account_child = activity_operation(parent_uuid=first_goal["entity_uuid"])
    result = (
        await push(
            test_client,
            second["access_token"],
            second_device,
            [cross_account_child],
        )
    ).json()["results"][0]
    assert result["status"] == "rejected"
    assert result["error_code"] == "PARENT_NOT_FOUND"

    own_activity = activity_operation()
    assert (
        await push(test_client, first["access_token"], first_device, [own_activity])
    ).json()["results"][0]["status"] == "applied"
    illegal_child = activity_operation(parent_uuid=own_activity["entity_uuid"])
    result = (
        await push(
            test_client,
            first["access_token"],
            first_device,
            [illegal_child],
        )
    ).json()["results"][0]
    assert result["status"] == "rejected"
    assert result["error_code"] == "INVALID_PARENT"


@pytest.mark.asyncio
async def test_goal_delete_detaches_children_and_propagates_tombstone(
    test_client, async_session
):
    tokens = await register_account(test_client, async_session, "deletev2user")
    token = tokens["access_token"]
    device_id = await register_device(test_client, token, "android-delete-01")
    goal = goal_operation()
    activity = activity_operation(parent_uuid=goal["entity_uuid"])
    response = await push(test_client, token, device_id, [goal, activity])
    assert [item["status"] for item in response.json()["results"]] == [
        "applied",
        "applied",
    ]

    delete = {
        "operation_id": str(uuid4()),
        "entity_type": "plan_node",
        "entity_uuid": goal["entity_uuid"],
        "action": "delete",
        "base_revision": 1,
        "payload": {"child_policy": "detach_children"},
    }
    deleted = await push(test_client, token, device_id, [delete])
    assert deleted.json()["results"][0]["status"] == "applied"
    assert deleted.json()["results"][0]["revision"] == 2

    bootstrap = await test_client.get(
        "/api/v2/sync/bootstrap",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": device_id},
    )
    assert bootstrap.status_code == 200, bootstrap.text
    nodes = [c for c in bootstrap.json()["changes"] if c["entity_type"] == "plan_node"]
    assert len(nodes) == 1
    assert nodes[0]["entity_uuid"] == activity["entity_uuid"]
    assert nodes[0]["revision"] == 2
    assert nodes[0]["payload"]["parent_uuid"] is None

    pull = await test_client.get(
        "/api/v2/sync/changes",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": device_id, "cursor": 0},
    )
    operations = [
        (c["entity_uuid"], c["operation"], c["revision"])
        for c in pull.json()["changes"]
    ]
    assert (activity["entity_uuid"], "upsert", 2) in operations
    assert (goal["entity_uuid"], "delete", 2) in operations


@pytest.mark.asyncio
async def test_activity_event_validation_revert_and_bootstrap(
    test_client, async_session
):
    tokens = await register_account(test_client, async_session, "eventv2user")
    token = tokens["access_token"]
    device_id = await register_device(test_client, token, "android-event-001")
    activity = activity_operation()
    await push(test_client, token, device_id, [activity])

    event_uuid = uuid4()
    event: dict[str, Any] = {
        "operation_id": str(uuid4()),
        "entity_type": "activity_event",
        "entity_uuid": str(event_uuid),
        "action": "upsert",
        "payload": {
            "activity_uuid": activity["entity_uuid"],
            "event_type": "check_in",
            "occurred_at": "2026-08-03T08:30:00+08:00",
            "local_date": "2026-08-03",
            "timezone": "Asia/Shanghai",
            "source_type": "app",
        },
    }
    created = await push(test_client, token, device_id, [event])
    assert created.json()["results"][0]["status"] == "applied"

    wrong = deepcopy(event)
    wrong["operation_id"] = str(uuid4())
    wrong["entity_uuid"] = str(uuid4())
    wrong["payload"]["event_type"] = "duration_session"
    wrong["payload"].update(
        duration_seconds=60,
        started_at="2026-08-03T00:00:00Z",
        ended_at="2026-08-03T00:01:00Z",
    )
    rejected = await push(test_client, token, device_id, [wrong])
    assert rejected.json()["results"][0]["error_code"] == "EVENT_TYPE_MISMATCH"

    revert = {
        "operation_id": str(uuid4()),
        "entity_type": "activity_event",
        "entity_uuid": str(uuid4()),
        "action": "upsert",
        "payload": {
            "activity_uuid": activity["entity_uuid"],
            "event_type": "revert",
            "occurred_at": "2026-08-03T08:31:00+08:00",
            "local_date": "2026-08-03",
            "timezone": "Asia/Shanghai",
            "reverts_event_uuid": str(event_uuid),
        },
    }
    reverted = await push(test_client, token, device_id, [revert])
    assert reverted.json()["results"][0]["status"] == "applied"

    duplicate_revert = deepcopy(revert)
    duplicate_revert["operation_id"] = str(uuid4())
    duplicate_revert["entity_uuid"] = str(uuid4())
    rejected = await push(test_client, token, device_id, [duplicate_revert])
    assert rejected.json()["results"][0]["error_code"] == "EVENT_ALREADY_REVERTED"

    bootstrap = await test_client.get(
        "/api/v2/sync/bootstrap",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": device_id},
    )
    event_changes = [
        change
        for change in bootstrap.json()["changes"]
        if change["entity_type"] == "activity_event"
    ]
    assert len(event_changes) == 2

    original = next(
        change for change in event_changes if change["entity_uuid"] == str(event_uuid)
    )
    occurred_at = original["payload"]["occurred_at"]
    assert occurred_at == "2026-08-03T00:30:00Z"
    assert (
        datetime.fromisoformat(occurred_at.replace("Z", "+00:00")).utcoffset()
        is not None
    )


@pytest.mark.asyncio
async def test_sync_rejects_ambiguous_naive_event_timestamp(test_client, async_session):
    tokens = await register_account(test_client, async_session, "naivetimev2user")
    token = tokens["access_token"]
    device_id = await register_device(test_client, token, "android-naive-time")
    activity = activity_operation()
    await push(test_client, token, device_id, [activity])

    event: dict[str, Any] = {
        "operation_id": str(uuid4()),
        "entity_type": "activity_event",
        "entity_uuid": str(uuid4()),
        "action": "upsert",
        "payload": {
            "activity_uuid": activity["entity_uuid"],
            "event_type": "check_in",
            "occurred_at": "2026-08-03T08:30:00",
            "local_date": "2026-08-03",
            "timezone": "Asia/Shanghai",
        },
    }
    response = await push(test_client, token, device_id, [event])
    result = response.json()["results"][0]
    assert result["status"] == "rejected"
    assert result["error_code"] == "INVALID_PAYLOAD"


@pytest.mark.asyncio
async def test_direct_duration_event_requires_timer_commands_for_every_source(
    test_client,
    async_session,
):
    tokens = await register_account(test_client, async_session, "durationtimev2user")
    token = tokens["access_token"]
    device_id = await register_device(test_client, token, "android-duration-time")
    activity = activity_operation(tracking_mode="duration")
    await push(test_client, token, device_id, [activity])

    event: dict[str, Any] = {
        "operation_id": str(uuid4()),
        "entity_type": "activity_event",
        "entity_uuid": str(uuid4()),
        "action": "upsert",
        "payload": {
            "activity_uuid": activity["entity_uuid"],
            "event_type": "duration_session",
            "duration_seconds": 600,
            "started_at": "2026-08-03T08:30:00+08:00",
            "ended_at": "2026-08-03T08:40:00+08:00",
            "occurred_at": "2026-08-03T08:40:00+08:00",
            "local_date": "2026-08-03",
            "timezone": "Asia/Shanghai",
            "source_type": "smart_device",
            "external_event_id": "watch-session-001",
        },
    }
    for source_type in ("app", "smart_device", "automation", "import"):
        candidate = deepcopy(event)
        candidate["operation_id"] = str(uuid4())
        candidate["entity_uuid"] = str(uuid4())
        candidate["payload"]["source_type"] = source_type
        candidate["payload"]["external_event_id"] = f"{source_type}-session-001"
        rejected = await push(test_client, token, device_id, [candidate])
        result = rejected.json()["results"][0]
        assert result["status"] == "rejected"
        assert result["error_code"] == "TIMER_COMMAND_REQUIRED"


@pytest.mark.asyncio
async def test_external_count_event_round_trip_is_utc_and_unique(
    test_client, async_session
):
    tokens = await register_account(test_client, async_session, "externalcountv2user")
    token = tokens["access_token"]
    device_id = await register_device(test_client, token, "external-count-device")
    activity = activity_operation(tracking_mode="count")
    await push(test_client, token, device_id, [activity])

    event: dict[str, Any] = {
        "operation_id": str(uuid4()),
        "entity_type": "activity_event",
        "entity_uuid": str(uuid4()),
        "action": "upsert",
        "payload": {
            "activity_uuid": activity["entity_uuid"],
            "event_type": "count_delta",
            "value": 1,
            "occurred_at": "2026-08-03T08:40:00+08:00",
            "local_date": "2026-08-03",
            "timezone": "Asia/Shanghai",
            "source_type": "smart_device",
            "external_event_id": "counter-event-001",
        },
    }
    created = await push(test_client, token, device_id, [event])
    assert created.json()["results"][0]["status"] == "applied"

    duplicate = deepcopy(event)
    duplicate["operation_id"] = str(uuid4())
    duplicate["entity_uuid"] = str(uuid4())
    rejected = await push(test_client, token, device_id, [duplicate])
    assert rejected.json()["results"][0]["error_code"] == "DUPLICATE_EXTERNAL_EVENT"

    bootstrap = await test_client.get(
        "/api/v2/sync/bootstrap",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": device_id},
    )
    count_event = next(
        change
        for change in bootstrap.json()["changes"]
        if change["entity_uuid"] == event["entity_uuid"]
    )
    assert count_event["payload"]["occurred_at"] == "2026-08-03T00:40:00Z"


@pytest.mark.asyncio
async def test_operation_id_cannot_be_reused_with_different_body(
    test_client, async_session
):
    tokens = await register_account(test_client, async_session, "operationv2user")
    token = tokens["access_token"]
    device_id = await register_device(test_client, token, "android-operation-1")
    operation_id = uuid4()
    first = goal_operation(operation_id=operation_id)
    assert (await push(test_client, token, device_id, [first])).json()["results"][0][
        "status"
    ] == "applied"

    changed = deepcopy(first)
    changed["payload"]["title"] = "Different body"
    result = (await push(test_client, token, device_id, [changed])).json()["results"][0]
    assert result["status"] == "rejected"
    assert result["error_code"] == "OPERATION_ID_REUSED"


@pytest.mark.asyncio
async def test_bootstrap_excludes_facts_and_links_whose_parent_is_deleted(
    test_client, async_session
):
    tokens = await register_account(test_client, async_session, "bootstrapfilterv2")
    token = tokens["access_token"]
    device_id = await register_device(test_client, token, "android-filter-001")

    activity = activity_operation()
    metric_uuid = uuid4()
    metric = {
        "operation_id": str(uuid4()),
        "entity_type": "metric",
        "entity_uuid": str(metric_uuid),
        "action": "upsert",
        "payload": {"name": "Weight", "unit": "kg", "decimal_places": 1},
    }
    observation = {
        "operation_id": str(uuid4()),
        "entity_type": "metric_observation",
        "entity_uuid": str(uuid4()),
        "action": "upsert",
        "payload": {
            "metric_uuid": str(metric_uuid),
            "value": "60.5",
            "unit": "kg",
            "occurred_at": "2026-08-03T08:30:00+08:00",
            "local_date": "2026-08-03",
            "timezone": "Asia/Shanghai",
        },
    }
    link: dict[str, Any] = {
        "operation_id": str(uuid4()),
        "entity_type": "activity_metric_link",
        "entity_uuid": str(uuid4()),
        "action": "upsert",
        "payload": {
            "activity_uuid": activity["entity_uuid"],
            "metric_uuid": str(metric_uuid),
        },
    }
    created = await push(
        test_client, token, device_id, [activity, metric, observation, link]
    )
    assert [item["status"] for item in created.json()["results"]] == ["applied"] * 4

    delete_metric = {
        "operation_id": str(uuid4()),
        "entity_type": "metric",
        "entity_uuid": str(metric_uuid),
        "action": "delete",
        "base_revision": 1,
        "payload": {},
    }
    deleted = await push(test_client, token, device_id, [delete_metric])
    assert deleted.json()["results"][0]["status"] == "applied"

    bootstrap = await test_client.get(
        "/api/v2/sync/bootstrap",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": device_id},
    )
    assert bootstrap.status_code == 200, bootstrap.text
    entity_types = [change["entity_type"] for change in bootstrap.json()["changes"]]
    assert "metric" not in entity_types
    assert "metric_observation" not in entity_types
    assert "activity_metric_link" not in entity_types


@pytest.mark.asyncio
async def test_deleted_activity_metric_link_can_be_created_again(
    test_client, async_session
):
    tokens = await register_account(test_client, async_session, "relinkv2user")
    token = tokens["access_token"]
    device_id = await register_device(test_client, token, "android-relink-01")
    activity = activity_operation()
    metric_uuid = uuid4()
    metric = {
        "operation_id": str(uuid4()),
        "entity_type": "metric",
        "entity_uuid": str(metric_uuid),
        "action": "upsert",
        "payload": {"name": "Mood", "unit": "point"},
    }
    first_link_uuid = uuid4()
    first_link = {
        "operation_id": str(uuid4()),
        "entity_type": "activity_metric_link",
        "entity_uuid": str(first_link_uuid),
        "action": "upsert",
        "payload": {
            "activity_uuid": activity["entity_uuid"],
            "metric_uuid": str(metric_uuid),
        },
    }
    created = await push(test_client, token, device_id, [activity, metric, first_link])
    assert [item["status"] for item in created.json()["results"]] == ["applied"] * 3

    unlink = {
        "operation_id": str(uuid4()),
        "entity_type": "activity_metric_link",
        "entity_uuid": str(first_link_uuid),
        "action": "delete",
        "base_revision": 1,
        "payload": {},
    }
    assert (await push(test_client, token, device_id, [unlink])).json()["results"][0][
        "status"
    ] == "applied"

    second_link = deepcopy(first_link)
    second_link["operation_id"] = str(uuid4())
    second_link["entity_uuid"] = str(uuid4())
    relinked = await push(test_client, token, device_id, [second_link])
    assert relinked.json()["results"][0]["status"] == "applied"


@pytest.mark.asyncio
async def test_conflict_always_contains_authoritative_revision_and_entity(
    test_client, async_session
):
    tokens = await register_account(test_client, async_session, "completeconflictv2")
    token = tokens["access_token"]
    device_id = await register_device(test_client, token, "android-conflict-complete")
    activity = activity_operation()
    metric_uuid = uuid4()
    metric = {
        "operation_id": str(uuid4()),
        "entity_type": "metric",
        "entity_uuid": str(metric_uuid),
        "action": "upsert",
        "payload": {"name": "Mood", "unit": "point"},
    }
    link: dict[str, Any] = {
        "operation_id": str(uuid4()),
        "entity_type": "activity_metric_link",
        "entity_uuid": str(uuid4()),
        "action": "upsert",
        "payload": {
            "activity_uuid": activity["entity_uuid"],
            "metric_uuid": str(metric_uuid),
        },
    }
    created = await push(test_client, token, device_id, [activity, metric, link])
    assert [item["status"] for item in created.json()["results"]] == ["applied"] * 3

    stale = deepcopy(link)
    stale["operation_id"] = str(uuid4())
    stale["base_revision"] = 0
    stale["payload"]["coefficient"] = "2"
    response = await push(test_client, token, device_id, [stale])
    result = response.json()["results"][0]
    assert result["status"] == "conflict"
    assert result["revision"] == 1
    assert result["entity"]["activity_uuid"] == activity["entity_uuid"]
    assert result["entity"]["metric_uuid"] == str(metric_uuid)

    replay = await push(test_client, token, device_id, [stale])
    assert replay.json()["results"][0] == result
