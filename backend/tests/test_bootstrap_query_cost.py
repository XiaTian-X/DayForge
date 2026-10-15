"""Small real timer histories: measure bootstrap SQL without changing authority."""

from time import perf_counter
from uuid import uuid4

import pytest
from sqlalchemy import event
from sqlmodel import select

from src.auth.models import User
from src.v2.models import ActivityEvent, PlanNode
from src.time_utils import utc_now
from src.v2.read_service import _bootstrap_day_allocations
from src.v2.entity_snapshots import current_entity_snapshot
from src.v2.schemas import SyncOperationRequest

from tests.test_sync_persistence import snapshot_setup as snapshot_setup
from tests.test_sync_contract_matrix import fixture, with_device
from tests.test_sync_v2 import register_account, register_device, push


async def complete_timer(test_client, token, device):
    commands = with_device(fixture("client/timer-commands.json"), device)
    identity = str(uuid4())
    for command in commands["commands"]:
        command["command_id"] = str(uuid4())
        command["session_id"] = identity
    response = await test_client.post(
        "/api/v2/timers/commands",
        headers={"Authorization": f"Bearer {token}"},
        json=commands,
    )
    assert response.status_code == 200, response.text
    assert [row["status"] for row in response.json()["results"]] == ["applied"] * 4
    return identity


@pytest.mark.parametrize("sessions", [0, 1, 8])
async def test_bootstrap_timer_allocations_keep_payload_and_bounded_queries(
    test_client, async_session, async_engine, snapshot_setup, sessions
):
    owner, token, device, _, _ = snapshot_setup
    identities = set()
    for _ in range(sessions):
        identities.add(await complete_timer(test_client, token, device))
    await async_session.commit()
    statements = []

    def observe(_connection, _cursor, statement, _parameters, _context, _many):
        statements.append(statement)

    event.listen(async_engine.sync_engine, "before_cursor_execute", observe)
    started = perf_counter()
    try:
        response = await test_client.get(
            "/api/v2/sync/bootstrap",
            headers={"Authorization": f"Bearer {token}"},
            params={"device_id": device},
        )
    finally:
        elapsed_ms = (perf_counter() - started) * 1000
        event.remove(async_engine.sync_engine, "before_cursor_execute", observe)
    assert response.status_code == 200, response.text
    durations = {
        row["entity_uuid"]: row["payload"]
        for row in response.json()["changes"]
        if row["entity_type"] == "activity_event"
        and row["payload"]["event_type"] == "duration_session"
    }
    assert durations.keys() == identities
    for payload in durations.values():
        assert payload["day_allocations"] == [
            {
                "local_date": "2026-08-03",
                "timezone": "Asia/Shanghai",
                "duration_milliseconds": 30000,
            },
            {
                "local_date": "2026-08-04",
                "timezone": "Asia/Shanghai",
                "duration_milliseconds": 30000,
            },
        ]
        assert payload["duration_milliseconds"] == 60000
    allocation_queries = sum(
        "FROM duration_day_allocations" in statement for statement in statements
    )
    print(
        f"bootstrap sessions={sessions} sql={len(statements)} allocation_sql={allocation_queries} elapsed_ms={elapsed_ms:.2f}"
    )
    assert allocation_queries == (1 if sessions else 0)
    for identity, payload in durations.items():
        revision, independent = await current_entity_snapshot(
            async_session,
            owner,
            SyncOperationRequest(
                operation_id=uuid4(),
                entity_type="activity_event",
                action="upsert",
                entity_uuid=identity,
            ),
        )
        assert revision == 1
        assert payload == independent


@pytest.mark.parametrize("deleted", ["none", "event", "parent"])
async def test_batched_allocations_exclude_other_accounts_and_deleted_dependencies(
    test_client, async_session, snapshot_setup, deleted
):
    owner, token, device, _, _ = snapshot_setup
    own_uuid = await complete_timer(test_client, token, device)
    other = await register_account(test_client, async_session, "allocation-other")
    other_device = await register_device(
        test_client, other["access_token"], "allocation-other-device"
    )
    batch = with_device(fixture("client/push-all-entities.json"), other_device)
    setup = await push(
        test_client, other["access_token"], other_device, batch["operations"]
    )
    assert [row["status"] for row in setup.json()["results"]] == ["applied"] * 7
    await complete_timer(test_client, other["access_token"], other_device)
    own = (
        await async_session.execute(
            select(ActivityEvent).where(
                ActivityEvent.owner_user_id == owner,
                ActivityEvent.public_id == own_uuid,
            )
        )
    ).scalar_one()
    if deleted == "event":
        own.deleted_at = utc_now()
    elif deleted == "parent":
        parent = await async_session.get(PlanNode, own.activity_node_id)
        assert parent is not None
        parent.deleted_at = utc_now()
    await async_session.commit()
    rows = await _bootstrap_day_allocations(async_session, owner)
    assert rows.keys() == ({own.id} if deleted == "none" else set())
    other_user = (
        await async_session.execute(
            select(User).where(User.username == "allocation-other")
        )
    ).scalar_one()
    other_rows = await _bootstrap_day_allocations(async_session, other_user.id)
    assert len(other_rows) == 1
    assert own.id not in other_rows
