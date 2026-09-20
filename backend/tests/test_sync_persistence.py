"""Database characterization before extracting shared sync persistence (#19)."""

import hashlib
import json
from copy import deepcopy
from uuid import uuid4

import pytest
from sqlalchemy import func, text
from sqlalchemy.ext.asyncio import async_sessionmaker
from sqlmodel import select

from src.auth.models import User
from src.v2.entity_snapshots import current_entity_snapshot
from src.v2.models import (
    EntityRevisionSnapshot,
    GoalDetail,
    PlanNode,
    SyncChange,
    SyncCursor,
    SyncOperation,
)
from src.v2.schemas import SyncOperationRequest, SyncPushRequest
from src.v2.service import process_push
from tests.test_sync_contract_matrix import fixture, with_device
from tests.test_sync_v2 import goal_operation, push, register_account, register_device


@pytest.fixture
async def snapshot_setup(test_client, async_session):
    account = await register_account(test_client, async_session, "snapshot_owner")
    token = account["access_token"]
    device = await register_device(test_client, token, "snapshot-owner-phone")
    batch = with_device(fixture("client/push-all-entities.json"), device)
    response = await push(test_client, token, device, batch["operations"])
    assert response.status_code == 200, response.text
    results = response.json()["results"]
    assert [result["status"] for result in results] == ["applied"] * 7
    user = (
        await async_session.execute(
            select(User).where(User.username == "snapshot_owner")
        )
    ).scalar_one()
    await async_session.commit()
    return user.id, token, device, batch, results


@pytest.mark.parametrize(
    "index",
    range(7),
    ids=["goal", "check", "duration", "metric", "event", "observation", "link"],
)
async def test_authoritative_snapshot_is_scoped_by_owner_even_with_identical_public_ids(
    test_client,
    async_session,
    snapshot_setup,
    index,
):
    owner, _, _, batch, results = snapshot_setup
    operation = SyncOperationRequest.model_validate(batch["operations"][index])
    other_account = await register_account(
        test_client, async_session, "other_snapshot_owner"
    )
    other = (
        await async_session.execute(
            select(User).where(User.username == "other_snapshot_owner")
        )
    ).scalar_one()
    assert await current_entity_snapshot(async_session, other.id, operation) == (
        None,
        None,
    )

    other_device = await register_device(
        test_client, other_account["access_token"], "other-snapshot-phone"
    )
    other_batch = deepcopy(batch)
    for item in other_batch["operations"]:
        item["operation_id"] = str(uuid4())
        payload = item["payload"]
        if item["entity_type"] == "plan_node":
            payload["title"] += " other"
        elif item["entity_type"] == "metric":
            payload["name"] = "Other weight"
        elif item["entity_type"] == "activity_metric_link":
            payload["coefficient"] = "3"
        else:
            payload["note"] = "Other private fact"
    response = await push(
        test_client,
        other_account["access_token"],
        other_device,
        other_batch["operations"],
    )
    assert response.status_code == 200, response.text
    other_results = response.json()["results"]
    assert [result["status"] for result in other_results] == ["applied"] * 7
    await async_session.commit()

    for user_id, expected in (
        (owner, results[index]),
        (other.id, other_results[index]),
    ):
        revision, payload = await current_entity_snapshot(
            async_session, user_id, operation
        )
        assert revision == expected["revision"] == 1
        assert payload == expected["entity"]
        assert "owner_user_id" not in payload
        assert "created_by_user_id" not in payload
    assert results[index]["entity"] != other_results[index]["entity"]


@pytest.mark.parametrize(
    "index", [0, 1, 3, 5, 6], ids=["goal", "activity", "metric", "observation", "link"]
)
async def test_authoritative_snapshot_keeps_tombstones_for_conflict_resolution(
    test_client,
    async_session,
    snapshot_setup,
    index,
):
    owner, token, device, batch, _ = snapshot_setup
    original = batch["operations"][index]
    deletion = {
        "operation_id": str(uuid4()),
        "entity_type": original["entity_type"],
        "entity_uuid": original["entity_uuid"],
        "action": "delete",
        "base_revision": 1,
        "payload": {"child_policy": "cascade_children"} if index == 0 else {},
    }
    response = await push(test_client, token, device, [deletion])
    assert response.status_code == 200, response.text
    result = response.json()["results"][0]
    assert result["status"] == "applied", result
    await async_session.commit()
    revision, payload = await current_entity_snapshot(
        async_session,
        owner,
        SyncOperationRequest.model_validate(deletion),
    )
    assert revision == result["revision"] == 2
    assert payload == result["entity"]
    assert payload["deleted_at"].endswith("Z")


async def test_timer_snapshot_preserves_sorted_day_allocations_and_journal_payload(
    test_client,
    async_session,
    snapshot_setup,
):
    owner, token, device, _, _ = snapshot_setup
    commands = with_device(fixture("client/timer-commands.json"), device)
    response = await test_client.post(
        "/api/v2/timers/commands",
        headers={"Authorization": f"Bearer {token}"},
        json=commands,
    )
    assert response.status_code == 200, response.text
    assert [result["status"] for result in response.json()["results"]] == [
        "applied"
    ] * 4
    await async_session.commit()
    operation = SyncOperationRequest(
        operation_id=uuid4(),
        entity_type="activity_event",
        action="upsert",
        entity_uuid=commands["commands"][0]["session_id"],
    )
    revision, payload = await current_entity_snapshot(async_session, owner, operation)
    assert revision == 1
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
    change = (
        await async_session.execute(
            select(SyncChange).where(
                SyncChange.recipient_user_id == owner,
                SyncChange.entity_uuid == str(operation.entity_uuid),
            )
        )
    ).scalar_one()
    snapshot = (
        await async_session.execute(
            select(EntityRevisionSnapshot).where(
                EntityRevisionSnapshot.owner_user_id == owner,
                EntityRevisionSnapshot.entity_uuid == str(operation.entity_uuid),
            )
        )
    ).scalar_one()
    assert json.loads(change.payload_json) == payload
    assert snapshot.payload_json == change.payload_json
    assert (
        snapshot.payload_hash
        == hashlib.sha256(change.payload_json.encode("utf-8")).hexdigest()
    )


async def test_revert_snapshot_keeps_its_original_event_reference(
    test_client, async_session, snapshot_setup
):
    owner, token, device, batch, _ = snapshot_setup
    event = batch["operations"][4]
    revert = {
        "operation_id": str(uuid4()),
        "entity_uuid": str(uuid4()),
        "entity_type": "activity_event",
        "action": "upsert",
        "payload": {
            "activity_uuid": event["payload"]["activity_uuid"],
            "event_type": "revert",
            "reverts_event_uuid": event["entity_uuid"],
            "occurred_at": "2026-11-01T06:30:00Z",
            "local_date": "2026-11-01",
            "timezone": "America/New_York",
            "note": "Reverted fact",
        },
    }
    response = await push(test_client, token, device, [revert])
    assert response.status_code == 200, response.text
    result = response.json()["results"][0]
    assert result["status"] == "applied", result
    await async_session.commit()
    revision, payload = await current_entity_snapshot(
        async_session,
        owner,
        SyncOperationRequest.model_validate(revert),
    )
    assert revision == 1
    assert payload == result["entity"]
    assert payload["reverts_event_uuid"] == event["entity_uuid"]
    assert "day_allocations" not in payload


@pytest.mark.parametrize("table", ["entity_revision_snapshots", "sync_changes"])
@pytest.mark.parametrize("mode", ["create", "update", "delete"])
async def test_journal_constraint_failure_rolls_back_entity_and_allows_next_batch_item(
    test_client,
    async_session,
    table,
    mode,
):
    account = await register_account(
        test_client, async_session, "journal_failure_owner"
    )
    token = account["access_token"]
    device = await register_device(test_client, token, "journal-failure-phone")
    failing = goal_operation(title="Rejected journal write")
    succeeding = goal_operation(title="Successful journal write")
    prior = None
    if mode != "create":
        prior = goal_operation(
            entity_uuid=failing["entity_uuid"], title="Preserved original"
        )
        response = await push(test_client, token, device, [prior])
        assert response.json()["results"][0]["status"] == "applied"
        failing["base_revision"] = 1
        if mode == "delete":
            failing.update(action="delete", payload={})
    # Real SQLite constraint failure after domain mutation, not a mocked handler.
    await async_session.execute(
        text(
            f"CREATE TRIGGER reject_test_journal BEFORE INSERT ON {table} "
            f"WHEN NEW.entity_uuid = '{failing['entity_uuid']}' "
            "BEGIN SELECT RAISE(ABORT, 'injected journal constraint'); END"
        )
    )
    await async_session.commit()
    response = await push(test_client, token, device, [failing, succeeding])
    assert response.status_code == 200, response.text
    rejected, applied = response.json()["results"]
    assert rejected["status"] == "rejected"
    assert rejected["error_code"] == "CONSTRAINT_VIOLATION"
    assert applied["status"] == "applied"
    await async_session.commit()

    nodes = (await async_session.execute(select(PlanNode))).scalars().all()
    expected_nodes = {succeeding["entity_uuid"]: ("Successful journal write", 1, None)}
    expected_operations = {
        failing["operation_id"]: "rejected",
        succeeding["operation_id"]: "applied",
    }
    if prior:
        expected_nodes[prior["entity_uuid"]] = ("Preserved original", 1, None)
        expected_operations[prior["operation_id"]] = "applied"
    assert {
        node.public_id: (node.title, node.revision, node.deleted_at) for node in nodes
    } == expected_nodes
    assert (
        await async_session.execute(select(func.count()).select_from(GoalDetail))
    ).scalar_one() == len(expected_nodes)
    for model in (EntityRevisionSnapshot, SyncChange):
        rows = (await async_session.execute(select(model))).scalars().all()
        assert {row.entity_uuid: row.revision for row in rows} == {
            key: 1 for key in expected_nodes
        }
        assert len(rows) == len(expected_nodes)
    records = (await async_session.execute(select(SyncOperation))).scalars().all()
    assert {row.operation_id: row.status for row in records} == expected_operations
    replay = await push(test_client, token, device, [failing, succeeding])
    assert replay.status_code == 200, replay.text
    assert replay.json()["results"] == [
        rejected,
        {**applied, "status": "already_applied"},
    ]
    for model in (EntityRevisionSnapshot, SyncChange):
        assert (
            await async_session.execute(select(func.count()).select_from(model))
        ).scalar_one() == len(expected_nodes)


async def test_outer_rollback_removes_entity_history_cursor_and_idempotency_result(
    test_client,
    async_session,
    async_engine,
):
    account = await register_account(test_client, async_session, "outer_rollback_owner")
    device = await register_device(
        test_client, account["access_token"], "outer-rollback-phone"
    )
    user = (
        await async_session.execute(
            select(User).where(User.username == "outer_rollback_owner")
        )
    ).scalar_one()
    user_id = user.id
    await async_session.commit()
    operation = goal_operation()
    request = SyncPushRequest.model_validate(
        {"device_id": device, "operations": [operation]}
    )

    class AbortRequest(Exception):
        pass

    sessions = async_sessionmaker(async_engine, expire_on_commit=False)
    with pytest.raises(AbortRequest):
        async with sessions.begin() as transaction:
            owner = await transaction.get(User, user_id)
            result = await process_push(owner, request, transaction)
            assert result.results[0].status == "applied"
            for model in (
                PlanNode,
                GoalDetail,
                EntityRevisionSnapshot,
                SyncChange,
                SyncOperation,
                SyncCursor,
            ):
                assert (
                    await transaction.execute(select(func.count()).select_from(model))
                ).scalar_one() == 1
            raise AbortRequest()

    async with sessions() as reader:
        for model in (
            PlanNode,
            GoalDetail,
            EntityRevisionSnapshot,
            SyncChange,
            SyncOperation,
            SyncCursor,
        ):
            assert (
                await reader.execute(select(func.count()).select_from(model))
            ).scalar_one() == 0
    # The rollback must not consume the operation ID.
    response = await push(test_client, account["access_token"], device, [operation])
    assert response.status_code == 200, response.text
    assert response.json()["results"][0]["status"] == "applied"
