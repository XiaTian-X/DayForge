"""Household privacy and server-owned device capability contracts."""

from uuid import uuid4

import pytest

from src.auth.models import User
from src.auth.service import get_password_hash


PASSWORD = "TestPassword123!"


async def account(test_client, async_session, username: str, *, admin: bool = False) -> dict:
    user = User(
        username=username,
        password_hash=get_password_hash(PASSWORD),
        is_admin=admin,
    )
    async_session.add(user)
    await async_session.commit()
    await async_session.refresh(user)
    login = await test_client.post(
        "/api/v1/auth/login",
        json={"username": username, "password": PASSWORD},
    )
    assert login.status_code == 200, login.text
    return {**login.json(), "db_user": user}


async def register(test_client, token: str, installation: str, **extra) -> dict:
    payload = {
        "installation_id": installation,
        "protocol_version": 4,
        "platform": "android",
        "app_version": "2.0.0",
        "display_name": installation,
        **extra,
    }
    response = await test_client.post(
        "/api/v2/devices/register",
        headers={"Authorization": f"Bearer {token}"},
        json=payload,
    )
    assert response.status_code == 200, response.text
    return response.json()


def goal_operation(title: str = "Private goal") -> dict:
    return {
        "operation_id": str(uuid4()),
        "entity_type": "plan_node",
        "entity_uuid": str(uuid4()),
        "action": "upsert",
        "payload": {
            "node_kind": "goal",
            "title": title,
            "goal": {"evaluation_policy": {"schema_version": 1, "type": "manual"}},
        },
    }


async def push(test_client, token: str, device_id: str, operation: dict):
    return await test_client.post(
        "/api/v2/sync/push",
        headers={"Authorization": f"Bearer {token}"},
        json={"device_id": device_id, "operations": [operation]},
    )


@pytest.mark.asyncio
@pytest.mark.parametrize("protocol_version", [None, 3])
async def test_old_clients_receive_an_explicit_upgrade_response_instead_of_422(
    test_client,
    async_session,
    protocol_version,
):
    user = await account(test_client, async_session, f"oldclient{protocol_version}")
    payload = {
        "installation_id": f"old-client-{protocol_version}",
        "platform": "android",
    }
    if protocol_version is not None:
        payload["protocol_version"] = protocol_version
    response = await test_client.post(
        "/api/v2/devices/register",
        headers={"Authorization": f"Bearer {user['access_token']}"},
        json=payload,
    )

    assert response.status_code == 426
    assert response.json()["detail"]["code"] == "CLIENT_UPGRADE_REQUIRED"


@pytest.mark.asyncio
async def test_first_interactive_device_is_primary_and_secondary_requires_explicit_editing(
    test_client,
    async_session,
):
    user = await account(test_client, async_session, "devicepolicy")
    token = user["access_token"]
    first = await register(test_client, token, "device-policy-first")
    second = await register(test_client, token, "device-policy-second")

    assert first["is_primary_editor"] is True
    assert "structure.write" in first["capabilities"]
    assert second["is_primary_editor"] is False
    assert "facts.append" in second["capabilities"]
    assert "structure.write" not in second["capabilities"]

    denied = await push(test_client, token, second["device_id"], goal_operation())
    result = denied.json()["results"][0]
    assert result["status"] == "rejected"
    assert result["error_code"] == "DEVICE_CAPABILITY_DENIED"

    enabled = await test_client.patch(
        f"/api/v2/devices/{second['device_id']}/editing",
        headers={"Authorization": f"Bearer {token}"},
        json={"structural_edit_enabled": True},
    )
    assert enabled.status_code == 200, enabled.text
    assert "structure.write" in enabled.json()["capabilities"]
    applied = await push(test_client, token, second["device_id"], goal_operation())
    assert applied.json()["results"][0]["status"] == "applied"

    switched = await test_client.post(
        f"/api/v2/devices/{second['device_id']}/make-primary",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert switched.status_code == 200
    assert switched.json()["is_primary_editor"] is True
    devices = await test_client.get(
        "/api/v2/devices",
        headers={"Authorization": f"Bearer {token}"},
    )
    by_id = {item["device_id"]: item for item in devices.json()}
    assert by_id[first["device_id"]]["is_primary_editor"] is False
    assert by_id[second["device_id"]]["is_primary_editor"] is True


@pytest.mark.asyncio
async def test_revoked_primary_is_not_reassigned_until_explicit_takeover(
    test_client,
    async_session,
):
    user = await account(test_client, async_session, "explicitdevicehandoff")
    token = user["access_token"]
    first = await register(test_client, token, "explicit-handoff-first")
    second = await register(test_client, token, "explicit-handoff-second")

    revoked = await test_client.delete(
        f"/api/v2/devices/{first['device_id']}",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert revoked.status_code == 204

    registered_again = await register(test_client, token, "explicit-handoff-second")
    assert registered_again["is_primary_editor"] is False
    assert "structure.write" not in registered_again["capabilities"]

    taken_over = await test_client.post(
        f"/api/v2/devices/{second['device_id']}/make-primary",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert taken_over.status_code == 200
    assert taken_over.json()["is_primary_editor"] is True
    assert "structure.write" in taken_over.json()["capabilities"]


@pytest.mark.asyncio
async def test_noninteractive_device_requires_admin_provisioning_and_cannot_edit_structure(
    test_client,
    async_session,
):
    admin = await account(test_client, async_session, "deviceadmin", admin=True)
    member = await account(test_client, async_session, "hardwareowner")
    member_token = member["access_token"]

    rejected = await test_client.post(
        "/api/v2/devices/register",
        headers={"Authorization": f"Bearer {member_token}"},
        json={
            "installation_id": "unprovisioned-hardware",
            "protocol_version": 4,
            "platform": "hardware",
            "device_class": "hardware",
        },
    )
    assert rejected.status_code == 400
    assert rejected.json()["detail"]["code"] == "DEVICE_PROVISIONING_REQUIRED"

    provisioned = await test_client.post(
        f"/api/v1/admin/users/{member['user_id']}/devices",
        headers={"Authorization": f"Bearer {admin['access_token']}"},
        json={
            "installation_id": "provisioned-hardware",
            "platform": "hardware",
            "device_class": "hardware",
            "display_name": "Kitchen button",
        },
    )
    assert provisioned.status_code == 201, provisioned.text
    device = provisioned.json()
    assert "facts.append" in device["capabilities"]
    assert "timer.control" in device["capabilities"]
    assert "structure.write" not in device["capabilities"]

    registered = await register(
        test_client,
        member_token,
        "provisioned-hardware",
        platform="hardware",
        device_class="hardware",
    )
    denied = await push(test_client, member_token, registered["device_id"], goal_operation())
    assert denied.json()["results"][0]["error_code"] == "DEVICE_CAPABILITY_DENIED"


@pytest.mark.asyncio
async def test_household_membership_is_admin_managed_metadata_not_private_data_access(
    test_client,
    async_session,
):
    admin = await account(test_client, async_session, "familyadmin", admin=True)
    member = await account(test_client, async_session, "familymember")
    admin_headers = {"Authorization": f"Bearer {admin['access_token']}"}

    created = await test_client.post(
        "/api/v1/admin/households",
        headers=admin_headers,
        json={"name": "Home"},
    )
    assert created.status_code == 201, created.text
    household_id = created.json()["household_id"]
    assert created.json()["members"][0]["role"] == "owner"

    added = await test_client.put(
        f"/api/v1/admin/households/{household_id}/members",
        headers=admin_headers,
        json={"user_id": member["user_id"], "role": "member"},
    )
    assert added.status_code == 200, added.text
    body = added.json()
    assert {item["username"] for item in body["members"]} == {"familyadmin", "familymember"}
    assert not ({"plan_nodes", "events", "metrics", "observations", "timers"} & set(body))

    forbidden = await test_client.get(
        "/api/v1/admin/households",
        headers={"Authorization": f"Bearer {member['access_token']}"},
    )
    assert forbidden.status_code == 403

    last_owner = await test_client.delete(
        f"/api/v1/admin/households/{household_id}/members/{admin['user_id']}",
        headers=admin_headers,
    )
    assert last_owner.status_code == 409

    member_device = await register(test_client, member["access_token"], "family-member-phone")
    private_goal = await push(
        test_client,
        member["access_token"],
        member_device["device_id"],
        goal_operation("Member only"),
    )
    assert private_goal.json()["results"][0]["status"] == "applied"
    admin_device = await register(test_client, admin["access_token"], "family-admin-phone")
    admin_bootstrap = await test_client.get(
        "/api/v2/sync/bootstrap",
        headers=admin_headers,
        params={"device_id": admin_device["device_id"]},
    )
    assert admin_bootstrap.status_code == 200
    assert all(change["payload"].get("title") != "Member only" for change in admin_bootstrap.json()["changes"])
