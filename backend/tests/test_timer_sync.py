"""Acceptance coverage for endpoint identity and ordered active timer sync."""

from datetime import datetime, timedelta, timezone
from uuid import uuid4

import pytest
from sqlmodel import select

from src.auth.models import User
from src.auth.service import get_password_hash
from src.v2.models import DurationDayAllocation, TimerSession


PASSWORD = "TestPassword123!"


async def register_account(test_client, async_session, username: str) -> dict[str, str]:
    async_session.add(User(username=username, password_hash=get_password_hash(PASSWORD)))
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
            "app_version": "3.0.0",
            "display_name": installation_id,
        },
    )
    assert response.status_code == 200, response.text
    return response.json()["device_id"]


async def create_timer_activity(
    test_client,
    token: str,
    device_id: str,
    *,
    countdown: bool = False,
) -> str:
    activity_id = str(uuid4())
    response = await test_client.post(
        "/api/v2/sync/push",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "device_id": device_id,
            "operations": [
                {
                    "operation_id": str(uuid4()),
                    "entity_type": "plan_node",
                    "entity_uuid": activity_id,
                    "action": "upsert",
                    "base_revision": None,
                    "payload": {
                        "node_kind": "activity",
                        "title": f"Timer {activity_id[:8]}",
                        "activity": {
                            "tracking_mode": "duration",
                            "is_countdown": countdown,
                            "recurrence_rule": {
                                "schema_version": 1,
                                "type": "daily",
                                "interval": 1,
                            },
                            "completion_policy": "recurring",
                            "target_value": "60",
                            "target_unit": "second",
                            "failure_policy": {"schema_version": 1, "type": "strict"},
                            "timezone": "Asia/Shanghai",
                        },
                    },
                }
            ],
        },
    )
    assert response.status_code == 200, response.text
    assert response.json()["results"][0]["status"] == "applied"
    return activity_id


def timer_command(
    command_type: str,
    session_id: str,
    sequence: int,
    occurred_at: datetime,
    *,
    command_id: str | None = None,
    activity_id: str | None = None,
    generation: int = 1,
    revision: int | None = None,
    active_elapsed_ms: int | None = None,
) -> dict:
    body = {
        "command_id": command_id or str(uuid4()),
        "session_id": session_id,
        "sequence": sequence,
        "command_type": command_type,
        "occurred_at": occurred_at.isoformat().replace("+00:00", "Z"),
        "expected_control_generation": 0 if command_type == "start" else generation,
        "expected_revision": None if command_type == "start" else revision,
    }
    if command_type == "start":
        body["activity_uuid"] = activity_id
        body["timezone"] = "Asia/Shanghai"
    if active_elapsed_ms is not None:
        body["active_elapsed_ms"] = active_elapsed_ms
    return body


async def submit(test_client, token: str, device_id: str, commands: list[dict]):
    return await test_client.post(
        "/api/v2/timers/commands",
        headers={"Authorization": f"Bearer {token}"},
        json={"device_id": device_id, "commands": commands},
    )


@pytest.mark.asyncio
async def test_server_identity_is_stable_and_exposes_timer_capabilities(test_client):
    first = await test_client.get("/api/v2/system/identity")
    second = await test_client.get("/api/v2/system/identity")
    assert first.status_code == 200, first.text
    assert first.json()["server_instance_id"] == second.json()["server_instance_id"]
    assert first.json()["sync_epoch"] == second.json()["sync_epoch"]
    assert "timer_commands" in first.json()["capabilities"]


@pytest.mark.asyncio
async def test_invalid_iana_timezone_is_rejected_before_storage(test_client, async_session):
    account = await register_account(test_client, async_session, "invalidtimezone")
    token = account["access_token"]
    device = await register_device(test_client, token, "timer-invalid-timezone")
    activity = await create_timer_activity(test_client, token, device)
    session_id = str(uuid4())
    command = timer_command(
        "start",
        session_id,
        1,
        datetime(2026, 8, 3, 0, 0, tzinfo=timezone.utc),
        activity_id=activity,
    )
    command["timezone"] = "+08:00"
    response = await submit(test_client, token, device, [command])
    assert response.status_code == 422


@pytest.mark.asyncio
async def test_cross_midnight_stop_is_idempotent_and_allocates_both_days(
    test_client,
    async_session,
):
    account = await register_account(test_client, async_session, "timercrossday")
    token = account["access_token"]
    device = await register_device(test_client, token, "timer-cross-day")
    activity = await create_timer_activity(test_client, token, device)
    session_id = str(uuid4())
    start_at = datetime(2026, 8, 3, 15, 59, 30, tzinfo=timezone.utc)
    commands = [
        timer_command("start", session_id, 1, start_at, activity_id=activity),
        timer_command("stop", session_id, 2, start_at + timedelta(seconds=60), revision=1),
    ]
    response = await submit(test_client, token, device, commands)
    assert response.status_code == 200, response.text
    assert [item["status"] for item in response.json()["results"]] == ["applied", "applied"]
    completed = response.json()["results"][1]["session"]
    assert completed["state"] == "completed"
    assert completed["active_elapsed_ms"] == 60_000

    replay = await submit(test_client, token, device, commands)
    assert [item["status"] for item in replay.json()["results"]] == [
        "already_applied",
        "already_applied",
    ]

    timer = (
        await async_session.execute(select(TimerSession).where(TimerSession.public_id == session_id))
    ).scalar_one()
    allocations = list(
        (
            await async_session.execute(
                select(DurationDayAllocation)
                .where(DurationDayAllocation.activity_event_id == timer.completed_event_id)
                .order_by(DurationDayAllocation.local_date)
            )
        ).scalars()
    )
    assert [(str(item.local_date), item.duration_ms) for item in allocations] == [
        ("2026-08-03", 30_000),
        ("2026-08-04", 30_000),
    ]
    pulled = await test_client.get(
        "/api/v2/sync/changes",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": device, "cursor": 0},
    )
    event_change = next(
        item for item in pulled.json()["changes"] if item["entity_uuid"] == session_id
    )
    assert event_change["payload"]["started_at"] == "2026-08-03T15:59:30Z"
    assert event_change["payload"]["ended_at"] == "2026-08-03T16:00:30Z"
    assert event_change["payload"]["day_allocations"] == [
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
async def test_pause_spanning_midnight_is_excluded_from_daily_allocations(
    test_client,
    async_session,
):
    account = await register_account(test_client, async_session, "timerpauseday")
    token = account["access_token"]
    device = await register_device(test_client, token, "timer-pause-day")
    activity = await create_timer_activity(test_client, token, device)
    session_id = str(uuid4())
    start_at = datetime(2026, 8, 3, 15, 59, 30, tzinfo=timezone.utc)
    response = await submit(
        test_client,
        token,
        device,
        [
            timer_command("start", session_id, 1, start_at, activity_id=activity),
            timer_command("pause", session_id, 2, start_at + timedelta(seconds=30), revision=1),
            timer_command("resume", session_id, 3, start_at + timedelta(seconds=90), revision=2),
            timer_command("stop", session_id, 4, start_at + timedelta(seconds=120), revision=3),
        ],
    )
    assert response.status_code == 200, response.text
    assert [item["status"] for item in response.json()["results"]] == ["applied"] * 4
    timer = (
        await async_session.execute(select(TimerSession).where(TimerSession.public_id == session_id))
    ).scalar_one()
    allocations = list(
        (
            await async_session.execute(
                select(DurationDayAllocation)
                .where(DurationDayAllocation.activity_event_id == timer.completed_event_id)
                .order_by(DurationDayAllocation.local_date)
            )
        ).scalars()
    )
    assert [(str(item.local_date), item.duration_ms) for item in allocations] == [
        ("2026-08-03", 30_000),
        ("2026-08-04", 30_000),
    ]


@pytest.mark.asyncio
async def test_monotonic_elapsed_corrects_wall_clock_and_clamps_at_timer_limit(
    test_client,
    async_session,
):
    account = await register_account(test_client, async_session, "timermonotonic")
    token = account["access_token"]
    device = await register_device(test_client, token, "timer-monotonic-device")
    activity = await create_timer_activity(test_client, token, device, countdown=True)
    session_id = str(uuid4())
    start_at = datetime(2026, 8, 3, 0, 0, tzinfo=timezone.utc)
    response = await submit(
        test_client,
        token,
        device,
        [
            timer_command("start", session_id, 1, start_at, activity_id=activity),
            # The wall clock jumped forward, while monotonic active time says
            # only 61 seconds passed. Countdown limit clamps this to 60s.
            timer_command(
                "pause",
                session_id,
                2,
                start_at + timedelta(hours=2),
                revision=1,
                active_elapsed_ms=61_000,
            ),
            timer_command(
                "stop",
                session_id,
                3,
                start_at + timedelta(hours=2, seconds=1),
                revision=2,
                active_elapsed_ms=60_000,
            ),
        ],
    )
    assert response.status_code == 200, response.text
    assert [item["status"] for item in response.json()["results"]] == ["applied"] * 3
    completed = response.json()["results"][-1]["session"]
    assert completed["state"] == "completed"
    assert completed["active_elapsed_ms"] == 60_000


@pytest.mark.asyncio
async def test_takeover_fences_the_old_controller(test_client, async_session):
    account = await register_account(test_client, async_session, "timertakeover")
    token = account["access_token"]
    first = await register_device(test_client, token, "timer-controller-first")
    second = await register_device(test_client, token, "timer-controller-second")
    activity = await create_timer_activity(test_client, token, first)
    session_id = str(uuid4())
    start_at = datetime(2026, 8, 3, 0, 0, tzinfo=timezone.utc)
    started = await submit(
        test_client,
        token,
        first,
        [timer_command("start", session_id, 1, start_at, activity_id=activity)],
    )
    assert started.json()["results"][0]["status"] == "applied"

    heartbeat = await test_client.post(
        f"/api/v2/timers/{session_id}/heartbeat",
        headers={"Authorization": f"Bearer {token}"},
        json={"device_id": first, "control_generation": 1},
    )
    assert heartbeat.status_code == 200
    assert heartbeat.json()["accepted"] is True

    visible = await test_client.get(
        "/api/v2/timers/active",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": second},
    )
    assert visible.json()["session"]["controller_device_id"] == first

    takeover = await submit(
        test_client,
        token,
        second,
        [
            timer_command(
                "takeover",
                session_id,
                2,
                start_at + timedelta(seconds=10),
                generation=1,
                revision=1,
            )
        ],
    )
    snapshot = takeover.json()["results"][0]["session"]
    assert snapshot["controller_device_id"] == second
    assert snapshot["control_generation"] == 2

    old_pause = await submit(
        test_client,
        token,
        first,
        [
            timer_command(
                "pause",
                session_id,
                3,
                start_at + timedelta(seconds=20),
                generation=1,
                revision=2,
            )
        ],
    )
    result = old_pause.json()["results"][0]
    assert result["status"] == "conflict"
    assert result["error_code"] == "CONTROL_LOST"
    assert result["session"]["controller_device_id"] == second

    fenced_heartbeat = await test_client.post(
        f"/api/v2/timers/{session_id}/heartbeat",
        headers={"Authorization": f"Bearer {token}"},
        json={"device_id": first, "control_generation": 1},
    )
    assert fenced_heartbeat.status_code == 409
    assert fenced_heartbeat.json()["detail"]["code"] == "CONTROL_LOST"


@pytest.mark.asyncio
async def test_late_takeover_clamps_elapsed_and_remains_completable(test_client, async_session):
    account = await register_account(test_client, async_session, "timerlatetakeover")
    token = account["access_token"]
    first = await register_device(test_client, token, "timer-late-controller-first")
    second = await register_device(test_client, token, "timer-late-controller-second")
    activity = await create_timer_activity(test_client, token, first, countdown=True)
    session_id = str(uuid4())
    start_at = datetime(2026, 8, 3, 0, 0, tzinfo=timezone.utc)

    started = await submit(
        test_client,
        token,
        first,
        [timer_command("start", session_id, 1, start_at, activity_id=activity)],
    )
    assert started.json()["results"][0]["status"] == "applied"

    takeover = await submit(
        test_client,
        token,
        second,
        [
            timer_command(
                "takeover",
                session_id,
                2,
                start_at + timedelta(hours=2),
                generation=1,
                revision=1,
            )
        ],
    )
    taken_over = takeover.json()["results"][0]
    assert taken_over["status"] == "applied"
    assert taken_over["session"]["active_elapsed_ms"] == 60_000

    stopped = await submit(
        test_client,
        token,
        second,
        [
            timer_command(
                "stop",
                session_id,
                3,
                start_at + timedelta(hours=2, seconds=1),
                generation=2,
                revision=2,
                active_elapsed_ms=60_000,
            )
        ],
    )
    completed = stopped.json()["results"][0]
    assert completed["status"] == "applied"
    assert completed["session"]["state"] == "completed"
    assert completed["session"]["active_elapsed_ms"] == 60_000


@pytest.mark.asyncio
async def test_timer_status_is_account_scoped_and_returns_missing_without_leaking(
    test_client,
    async_session,
):
    owner = await register_account(test_client, async_session, "timerstatusowner")
    owner_device = await register_device(
        test_client, owner["access_token"], "timer-status-owner-device"
    )
    activity = await create_timer_activity(
        test_client, owner["access_token"], owner_device
    )
    session_id = str(uuid4())
    start_at = datetime(2026, 8, 3, 0, 0, tzinfo=timezone.utc)
    await submit(
        test_client,
        owner["access_token"],
        owner_device,
        [timer_command("start", session_id, 1, start_at, activity_id=activity)],
    )

    owner_status = await test_client.get(
        f"/api/v2/timers/{session_id}",
        headers={"Authorization": f"Bearer {owner['access_token']}"},
        params={"device_id": owner_device},
    )
    assert owner_status.status_code == 200
    assert owner_status.json()["session"]["session_id"] == session_id

    other = await register_account(test_client, async_session, "timerstatusother")
    other_device = await register_device(
        test_client, other["access_token"], "timer-status-other-device"
    )
    hidden = await test_client.get(
        f"/api/v2/timers/{session_id}",
        headers={"Authorization": f"Bearer {other['access_token']}"},
        params={"device_id": other_device},
    )
    assert hidden.status_code == 200
    assert hidden.json()["session"] is None


@pytest.mark.asyncio
async def test_out_of_order_command_can_be_retried_after_its_predecessor(test_client, async_session):
    account = await register_account(test_client, async_session, "timerordering")
    token = account["access_token"]
    device = await register_device(test_client, token, "timer-ordering-device")
    activity = await create_timer_activity(test_client, token, device)
    session_id = str(uuid4())
    start_at = datetime(2026, 8, 3, 0, 0, tzinfo=timezone.utc)
    start = timer_command("start", session_id, 1, start_at, activity_id=activity)
    pause = timer_command("pause", session_id, 2, start_at + timedelta(seconds=30), revision=1)
    resume = timer_command("resume", session_id, 3, start_at + timedelta(seconds=40), revision=2)

    assert (await submit(test_client, token, device, [start])).json()["results"][0]["status"] == "applied"
    early = (await submit(test_client, token, device, [resume])).json()["results"][0]
    assert early["status"] == "conflict"
    assert early["error_code"] == "MISSING_PREDECESSOR"
    assert (await submit(test_client, token, device, [pause])).json()["results"][0]["status"] == "applied"
    retried = (await submit(test_client, token, device, [resume])).json()["results"][0]
    assert retried["status"] == "applied"


@pytest.mark.asyncio
async def test_revoked_device_is_not_silently_reactivated(test_client, async_session):
    account = await register_account(test_client, async_session, "timerrevoke")
    token = account["access_token"]
    device = await register_device(test_client, token, "timer-revoked-device")
    revoke = await test_client.delete(
        f"/api/v2/devices/{device}",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert revoke.status_code == 204
    retry = await test_client.post(
        "/api/v2/devices/register",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "installation_id": "timer-revoked-device",
            "protocol_version": 4,
            "platform": "android",
            "app_version": "3.0.0",
            "display_name": "Revoked",
        },
    )
    assert retry.status_code == 403
    assert retry.json()["detail"]["code"] == "DEVICE_REVOKED"
