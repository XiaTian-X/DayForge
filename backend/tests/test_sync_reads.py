"""Characterize incremental reads and full recovery before module extraction (#19)."""

from datetime import datetime, timezone
from uuid import uuid4

import pytest
from sqlmodel import col, select

from src.auth.models import User
from src.v2.models import ClientDevice, SyncChange, SyncCursor
from src.v2.read_service import bootstrap, pull_changes
from tests.test_sync_persistence import snapshot_setup as snapshot_setup
from tests.test_sync_v2 import goal_operation, push, register_account, register_device


async def read(test_client, token, device, *, cursor=None, limit=500):
    path = "bootstrap" if cursor is None else "changes"
    params = {"device_id": device}
    if cursor is not None:
        params.update(cursor=cursor, limit=limit)
    response = await test_client.get(
        f"/api/v2/sync/{path}",
        headers={"Authorization": f"Bearer {token}"},
        params=params,
    )
    assert response.status_code == 200, response.text
    return response.json()


@pytest.mark.parametrize("limit", [1, 3, 8, 500])
async def test_paging_across_other_account_sequences_keeps_cursor_and_history(
    test_client, async_session, snapshot_setup, limit
):
    owner, token, writer, batch, _ = snapshot_setup
    reader = await register_device(test_client, token, "read-pagination-device")
    other = await register_account(test_client, async_session, "read_foreign_owner")
    foreign_device = await register_device(
        test_client, other["access_token"], "read-foreign-writer"
    )
    for index in range(2):
        result = await push(
            test_client,
            other["access_token"],
            foreign_device,
            [goal_operation(title=f"Foreign {index}")],
        )
        assert result.json()["results"][0]["status"] == "applied"
        if index == 0:
            extra = goal_operation(title="Own final change")
            assert (await push(test_client, token, writer, [extra])).json()["results"][
                0
            ]["status"] == "applied"
    expected = list(
        (
            await async_session.execute(
                select(SyncChange)
                .where(SyncChange.recipient_user_id == owner)
                .order_by(col(SyncChange.sequence))
            )
        ).scalars()
    )
    cursor, collected = 0, []
    while True:
        page = await read(test_client, token, reader, cursor=cursor, limit=limit)
        collected.extend(page["changes"])
        assert page["has_more"] == (len(collected) < len(expected))
        assert page["next_cursor"] == collected[-1]["sequence"]
        cursor = page["next_cursor"]
        if not page["has_more"]:
            break
    assert [item["sequence"] for item in collected] == [
        row.sequence for row in expected
    ]
    assert [item["entity_uuid"] for item in collected] == [
        op["entity_uuid"] for op in batch["operations"]
    ] + [extra["entity_uuid"]]
    assert all(item["origin_device_id"] == writer for item in collected)
    assert expected[-1].sequence > expected[-2].sequence + 1
    caught_up = await read(test_client, token, reader, cursor=cursor, limit=limit)
    assert caught_up["changes"] == [] and caught_up["has_more"] is False
    assert caught_up["next_cursor"] == cursor
    old_page = await read(test_client, token, reader, cursor=0, limit=1)
    assert old_page["changes"] == collected[:1]
    stored = (
        await async_session.execute(
            select(SyncCursor)
            .join(ClientDevice)
            .where(ClientDevice.public_id == reader)
        )
    ).scalar_one()
    assert stored.last_pulled_sequence == cursor
    recovery = await read(test_client, token, reader)
    assert (
        recovery["next_cursor"] == cursor
    )  # Not the foreign account's later sequence.
    assert {item["entity_uuid"] for item in recovery["changes"]} == {
        item["entity_uuid"] for item in collected
    }


@pytest.mark.parametrize(
    "deleted_index,hidden",
    [
        (0, {0, 1, 2, 4, 6}),
        (1, {1, 4, 6}),
        (3, {3, 5, 6}),
        (5, {5}),
        (6, {6}),
    ],
)
async def test_bootstrap_filters_deleted_dependencies_but_pull_preserves_tombstones(
    test_client, async_session, snapshot_setup, deleted_index, hidden
):
    _, token, writer, batch, originals = snapshot_setup
    operation = batch["operations"][deleted_index]
    deletion = {
        "operation_id": str(uuid4()),
        "entity_type": operation["entity_type"],
        "entity_uuid": operation["entity_uuid"],
        "action": "delete",
        "base_revision": 1,
        "payload": {"child_policy": "cascade_children"} if deleted_index == 0 else {},
    }
    result = await push(test_client, token, writer, [deletion])
    assert result.json()["results"][0]["status"] == "applied"
    reader = await register_device(test_client, token, "read-filter-device")
    recovery = await read(test_client, token, reader)
    expected = {
        op["entity_uuid"]: originals[index]["entity"]
        for index, op in enumerate(batch["operations"])
        if index not in hidden
    }
    assert {
        item["entity_uuid"]: item["payload"] for item in recovery["changes"]
    } == expected
    assert all(
        item["sequence"] == 0
        and item["origin_device_id"] is None
        and item["operation"] == "upsert"
        for item in recovery["changes"]
    )
    history = await read(test_client, token, reader, cursor=0)
    tombstone = next(
        item
        for item in history["changes"]
        if item["entity_uuid"] == operation["entity_uuid"]
        and item["operation"] == "delete"
    )
    assert tombstone["revision"] == 2 and tombstone["payload"]["deleted_at"].endswith(
        "Z"
    )
    assert recovery["next_cursor"] == history["next_cursor"]
    assert (await read(test_client, token, reader, cursor=recovery["next_cursor"]))[
        "changes"
    ] == []


@pytest.mark.parametrize("kind", ["pull", "bootstrap"])
@pytest.mark.parametrize("existing_cursor", [False, True])
async def test_read_cursor_and_device_activity_rollback_with_calling_transaction(
    async_session, test_client, snapshot_setup, kind, existing_cursor
):
    owner, token, _, _, _ = snapshot_setup
    reader = await register_device(test_client, token, "read-rollback-device")
    device = (
        await async_session.execute(
            select(ClientDevice).where(ClientDevice.public_id == reader)
        )
    ).scalar_one()
    device.last_seen_at = datetime(2026, 1, 1, tzinfo=timezone.utc)
    if existing_cursor:
        async_session.add(
            SyncCursor(
                user_id=owner,
                device_id=device.id,
                last_pulled_sequence=2,
                last_pull_at=device.last_seen_at,
            )
        )
    await async_session.commit()
    await async_session.refresh(device)
    before_seen = device.last_seen_at
    cursor = (
        await async_session.execute(
            select(SyncCursor).where(SyncCursor.device_id == device.id)
        )
    ).scalar_one_or_none()
    if cursor is not None:
        await async_session.refresh(cursor)
    before_cursor = cursor.model_dump() if cursor is not None else None
    user = await async_session.get(User, owner)
    response = (
        await bootstrap(user, reader, async_session)
        if kind == "bootstrap"
        else await pull_changes(user, reader, 0, 500, async_session)
    )
    assert len(response.changes) == 7 and response.next_cursor >= 7
    await async_session.flush()
    await async_session.rollback()
    device = (
        await async_session.execute(
            select(ClientDevice).where(ClientDevice.public_id == reader)
        )
    ).scalar_one()
    assert device.last_seen_at == before_seen
    cursor = (
        await async_session.execute(
            select(SyncCursor).where(SyncCursor.device_id == device.id)
        )
    ).scalar_one_or_none()
    assert (cursor.model_dump() if cursor is not None else None) == before_cursor


async def test_empty_account_reads_ignore_foreign_history(
    test_client, async_session, snapshot_setup
):
    other = await register_account(test_client, async_session, "empty_read_owner")
    token = other["access_token"]
    device = await register_device(test_client, token, "empty-read-device")
    for cursor in (None, 0):
        body = await read(test_client, token, device, cursor=cursor)
        assert body["changes"] == [] and body["next_cursor"] == 0
        assert body["server_time"].endswith("Z")
    denied = await test_client.get(
        "/api/v2/sync/changes",
        headers={"Authorization": f"Bearer {token}"},
        params={"device_id": device, "cursor": 1},
    )
    assert (
        denied.status_code == 400
        and denied.json()["detail"]["code"] == "INVALID_CURSOR"
    )


@pytest.mark.parametrize("source", ["server", "revoked-device"])
async def test_historical_changes_keep_nullable_or_revoked_origin_device(
    test_client, async_session, snapshot_setup, source
):
    owner, token, writer, _, _ = snapshot_setup
    reader = await register_device(test_client, token, "read-history-device")
    rows = (
        (
            await async_session.execute(
                select(SyncChange).where(SyncChange.recipient_user_id == owner)
            )
        )
        .scalars()
        .all()
    )
    if source == "server":
        for row in rows:
            row.origin_device_id = None
    else:
        device = (
            await async_session.execute(
                select(ClientDevice).where(ClientDevice.public_id == writer)
            )
        ).scalar_one()
        device.revoked_at = datetime(2026, 1, 1, tzinfo=timezone.utc)
    await async_session.commit()
    body = await read(test_client, token, reader, cursor=0)
    assert len(body["changes"]) == 7
    assert {item["origin_device_id"] for item in body["changes"]} == (
        {None} if source == "server" else {writer}
    )
