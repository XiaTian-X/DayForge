"""Device-boundary characterization before removing sync/timer coupling (#19)."""

from datetime import datetime, timezone
from uuid import uuid4

import pytest
from sqlmodel import select

from src.v2.models import (
    ClientDevice,
    SyncCursor,
    SyncOperation,
    TimerCommand,
    UserSyncPolicy,
)
from src.v2.schemas import DeviceRegisterRequest, utc_iso
from src.v2.device_service import register_device, require_device
from tests.test_household_device_capabilities import account
from tests.test_sync_contract_matrix import fixture


@pytest.fixture
async def device_context(test_client, async_session):
    owner = await account(test_client, async_session, "device_boundary_owner")
    headers = {"Authorization": f"Bearer {owner['access_token']}"}
    payload = {
        "installation_id": "device-boundary-install",
        "protocol_version": 4,
        "platform": "desktop",
        "device_class": "interactive",
        "app_version": "1.0",
        "display_name": "Original device",
    }
    response = await test_client.post(
        "/api/v2/devices/register", headers=headers, json=payload
    )
    assert response.status_code == 200, response.text
    device = (await async_session.execute(select(ClientDevice))).scalar_one()
    device.last_seen_at = datetime(2026, 1, 1, tzinfo=timezone.utc)
    await async_session.commit()
    await async_session.refresh(device)
    return owner, headers, payload, device, response.json()


async def device_snapshot(session):
    # Refresh ORM state so rollback assertions cannot pass against stale objects.
    result = {}
    for model in (ClientDevice, UserSyncPolicy):
        rows = (await session.execute(select(model))).scalars().all()
        for row in rows:
            await session.refresh(row)
        result[model] = [row.model_dump() for row in rows]
    return result


@pytest.mark.parametrize(
    "metadata", [{"app_version": "2.0", "display_name": "Renamed device"}, {}]
)
async def test_reregistration_only_refreshes_metadata_and_seen_time(
    test_client, async_session, device_context, metadata
):
    _, headers, payload, device, original = device_context
    request = {
        key: value
        for key, value in payload.items()
        if key not in {"app_version", "display_name"}
    }
    request.update(metadata)
    response = await test_client.post(
        "/api/v2/devices/register", headers=headers, json=request
    )
    assert response.status_code == 200, response.text
    current = response.json()
    for field in (
        "device_id",
        "installation_id",
        "platform",
        "device_class",
        "is_primary_editor",
        "structural_edit_enabled",
        "capability_revision",
        "capabilities",
    ):
        assert current[field] == original[field]
    assert current["app_version"] == metadata.get("app_version")
    assert current["display_name"] == metadata.get("display_name")
    assert current["last_seen_at"] > "2026-01-01T00:00:00Z"
    state = await device_snapshot(async_session)
    assert len(state[ClientDevice]) == len(state[UserSyncPolicy]) == 1
    assert state[UserSyncPolicy][0]["primary_editor_device_id"] == device.id
    assert state[UserSyncPolicy][0]["revision"] == 1


@pytest.mark.parametrize(
    "case,code,status",
    [
        ("platform", "DEVICE_IDENTITY_MISMATCH", 400),
        ("class", "DEVICE_IDENTITY_MISMATCH", 400),
        ("revoked", "DEVICE_REVOKED", 403),
        ("missing-protocol", "CLIENT_UPGRADE_REQUIRED", 426),
        ("old-protocol", "CLIENT_UPGRADE_REQUIRED", 426),
    ],
)
async def test_rejected_registration_preserves_existing_device_and_policy(
    test_client, async_session, device_context, case, code, status
):
    _, headers, payload, device, _ = device_context
    request = {
        **payload,
        "app_version": "Must not persist",
        "display_name": "Rejected rename",
    }
    if case == "platform":
        request["platform"] = "android"
    elif case == "class":
        request["device_class"] = "automation"
    elif case == "missing-protocol":
        del request["protocol_version"]
    elif case == "old-protocol":
        request["protocol_version"] = 3
    if case in {"revoked", "missing-protocol", "old-protocol"}:
        # Upgrade validation precedes revoked/identity validation.
        device.revoked_at = datetime(2026, 1, 2, tzinfo=timezone.utc)
        await async_session.commit()
    before = await device_snapshot(async_session)
    for _ in range(2):
        response = await test_client.post(
            "/api/v2/devices/register", headers=headers, json=request
        )
        assert response.status_code == status, response.text
        assert response.json()["detail"]["code"] == code
    assert await device_snapshot(async_session) == before


async def test_same_installation_id_belongs_to_separate_accounts(
    test_client, async_session, device_context
):
    _, _, payload, _, original = device_context
    other = await account(test_client, async_session, "other_device_boundary_owner")
    response = await test_client.post(
        "/api/v2/devices/register",
        headers={"Authorization": f"Bearer {other['access_token']}"},
        json=payload,
    )
    assert response.status_code == 200, response.text
    assert response.json()["device_id"] != original["device_id"]
    assert response.json()["is_primary_editor"] is True
    state = await device_snapshot(async_session)
    assert len(state[ClientDevice]) == len(state[UserSyncPolicy]) == 2
    assert {row["user_id"] for row in state[ClientDevice]} == {
        row["user_id"] for row in state[UserSyncPolicy]
    }
    assert {row["primary_editor_device_id"] for row in state[UserSyncPolicy]} == {
        row["id"] for row in state[ClientDevice]
    }


@pytest.mark.parametrize("kind", ["missing", "foreign", "revoked"])
async def test_all_sync_and_timer_entries_reject_unavailable_devices_without_writes(
    test_client, async_session, device_context, kind
):
    _, headers, _, device, _ = device_context
    device_id = device.public_id
    if kind == "missing":
        device_id = str(uuid4())
    elif kind == "foreign":
        other = await account(test_client, async_session, "foreign_device_requester")
        headers = {"Authorization": f"Bearer {other['access_token']}"}
    else:
        device.revoked_at = datetime(2026, 1, 2, tzinfo=timezone.utc)
        await async_session.commit()
    before = await device_snapshot(async_session)
    sync = fixture("client/push-all-entities.json")
    sync["device_id"] = device_id
    timer = fixture("client/timer-commands.json")
    timer["device_id"] = device_id
    timer_id = timer["commands"][0]["session_id"]
    cases = [
        ("POST", "/api/v2/sync/push", {"json": sync}),
        (
            "GET",
            "/api/v2/sync/changes",
            {"params": {"device_id": device_id, "cursor": 0}},
        ),
        ("GET", "/api/v2/sync/bootstrap", {"params": {"device_id": device_id}}),
        ("POST", "/api/v2/timers/commands", {"json": timer}),
        ("GET", "/api/v2/timers/active", {"params": {"device_id": device_id}}),
        ("GET", f"/api/v2/timers/{timer_id}", {"params": {"device_id": device_id}}),
        (
            "POST",
            f"/api/v2/timers/{timer_id}/heartbeat",
            {"json": {"device_id": device_id, "control_generation": 1}},
        ),
    ]
    for method, path, options in cases:
        response = await test_client.request(method, path, headers=headers, **options)
        assert response.status_code == 404, (path, response.text)
        assert response.json()["detail"]["code"] == "DEVICE_NOT_FOUND"
    assert await device_snapshot(async_session) == before
    for model in (SyncOperation, SyncCursor, TimerCommand):
        assert (await async_session.execute(select(model))).scalars().all() == []


async def test_device_registration_and_primary_assignment_rollback_with_caller(
    async_session, test_client
):
    owner = await account(test_client, async_session, "device_rollback_owner")
    request = DeviceRegisterRequest(
        installation_id="rollback-registration", platform="android", protocol_version=4
    )
    created = await register_device(owner["db_user"], request, async_session)
    assert created.is_primary_editor is True
    assert len((await device_snapshot(async_session))[ClientDevice]) == 1
    await async_session.rollback()
    assert await device_snapshot(async_session) == {
        ClientDevice: [],
        UserSyncPolicy: [],
    }
    await async_session.refresh(owner["db_user"])
    recreated = await register_device(owner["db_user"], request, async_session)
    assert recreated.is_primary_editor is True
    assert recreated.capability_revision == created.capability_revision


async def test_active_device_lookup_seen_time_remains_caller_transactional(
    async_session, device_context
):
    owner, _, _, device, _ = device_context
    before = await device_snapshot(async_session)
    found = await require_device(owner["db_user"].id, device.public_id, async_session)
    assert found.id == device.id
    assert utc_iso(found.last_seen_at) > "2026-01-01T00:00:00Z"
    await async_session.flush()
    await async_session.rollback()
    assert await device_snapshot(async_session) == before
