"""Observable merge behavior to preserve while splitting the sync service."""

from copy import deepcopy
import json
from uuid import uuid4

import pytest
from sqlalchemy import delete
from sqlmodel import select

from src.auth.models import User
from src.v2.models import EntityRevisionSnapshot, SyncChange, SyncOperation
from tests.test_sync_v2 import (
    activity_operation,
    goal_operation,
    metric_operation,
    push,
    register_account,
    register_device,
)


def edit(operation, revision=1):
    result = deepcopy(operation)
    result["operation_id"] = str(uuid4())
    result["base_revision"] = revision
    return result


@pytest.fixture
async def merge_client(test_client, async_session):
    tokens = await register_account(
        test_client, async_session, "merge-characterization"
    )
    token = tokens["access_token"]
    device = await register_device(test_client, token, "merge-characterization-phone")

    async def submit(operation):
        response = await push(test_client, token, device, [operation])
        assert response.status_code == 200, response.text
        # Exercise persisted results, not just the ORM identity map.
        await async_session.commit()
        return response.json()["results"][0]

    return submit


async def assert_history(session, operation, revisions):
    entity_uuid = operation["entity_uuid"]
    snapshots = (
        (
            await session.execute(
                select(EntityRevisionSnapshot)
                .where(
                    EntityRevisionSnapshot.entity_uuid == entity_uuid,
                )
                .order_by(EntityRevisionSnapshot.revision)
            )
        )
        .scalars()
        .all()
    )
    changes = (
        (
            await session.execute(
                select(SyncChange)
                .where(SyncChange.entity_uuid == entity_uuid)
                .order_by(SyncChange.sequence)
            )
        )
        .scalars()
        .all()
    )
    assert [row.revision for row in snapshots] == revisions
    assert [row.revision for row in changes] == revisions
    assert [row.payload_json for row in snapshots] == [
        row.payload_json for row in changes
    ]


@pytest.mark.parametrize(
    "explicit_clear", [False, True], ids=["omitted", "explicit-null"]
)
async def test_stale_nested_optional_field_distinguishes_omission_from_clear(
    merge_client,
    async_session,
    explicit_clear,
):
    base = goal_operation()
    base["payload"]["goal"]["due_date"] = "2026-12-31"
    assert (await merge_client(base))["revision"] == 1
    remote = edit(base)
    remote["payload"]["title"] = "Server title"
    assert (await merge_client(remote))["revision"] == 2

    local = edit(base)
    if explicit_clear:
        local["payload"]["goal"]["due_date"] = None
    else:
        del local["payload"]["goal"]["due_date"]
    result = await merge_client(local)
    assert result["status"] == "applied", result
    assert result["entity"]["title"] == "Server title"
    assert result["entity"]["goal"]["due_date"] == (
        None if explicit_clear else "2026-12-31"
    )
    assert result["revision"] == (3 if explicit_clear else 2)
    replay = await merge_client(local)
    assert replay == {**result, "status": "already_applied"}
    await assert_history(async_session, base, [1, 2, 3] if explicit_clear else [1, 2])


@pytest.mark.parametrize("entity_type", ["goal", "metric", "activity_metric_link"])
async def test_omitted_default_fields_preserve_remote_changes_across_structural_types(
    merge_client,
    entity_type,
):
    if entity_type == "goal":
        base = goal_operation()
        remote_field, remote_value = "description", "Remote description"
        local_field, local_value = "title", "Local title"
    elif entity_type == "metric":
        base = metric_operation()
        remote_field, remote_value = "description", "Remote description"
        local_field, local_value = "name", "Local metric"
    else:
        activity = activity_operation()
        metric = metric_operation()
        assert (await merge_client(activity))["status"] == "applied"
        assert (await merge_client(metric))["status"] == "applied"
        base = {
            "operation_id": str(uuid4()),
            "entity_uuid": str(uuid4()),
            "entity_type": "activity_metric_link",
            "action": "upsert",
            "payload": {
                "activity_uuid": activity["entity_uuid"],
                "metric_uuid": metric["entity_uuid"],
            },
        }
        remote_field, remote_value = "prompt_on_complete", True
        local_field, local_value = "is_active", False

    assert (await merge_client(base))["revision"] == 1
    remote = edit(base)
    remote["payload"][remote_field] = remote_value
    assert (await merge_client(remote))["revision"] == 2
    local = edit(base)
    assert remote_field not in local["payload"]
    local["payload"][local_field] = local_value
    result = await merge_client(local)
    assert result["status"] == "applied", result
    assert result["revision"] == 3
    assert result["entity"][remote_field] == remote_value
    assert result["entity"][local_field] == local_value


async def test_explicit_default_and_nested_changes_survive_disjoint_remote_edit(
    merge_client,
):
    base = goal_operation()
    base["payload"]["description"] = "Original description"
    base["payload"]["goal"]["target_cycles"] = 3
    assert (await merge_client(base))["revision"] == 1
    remote = edit(base)
    remote["payload"]["title"] = "Remote title"
    assert (await merge_client(remote))["revision"] == 2
    local = edit(base)
    local["payload"]["description"] = ""
    local["payload"]["goal"]["target_cycles"] = 5
    result = await merge_client(local)
    assert result["status"] == "applied", result
    assert result["entity"]["title"] == "Remote title"
    assert result["entity"]["description"] == ""
    assert result["entity"]["goal"]["target_cycles"] == 5


async def test_merged_replay_keeps_original_request_identity_and_does_not_write_again(
    merge_client,
    async_session,
):
    base = goal_operation()
    assert (await merge_client(base))["revision"] == 1
    remote = edit(base)
    remote["payload"]["title"] = "Remote title"
    assert (await merge_client(remote))["revision"] == 2
    local = edit(base)
    local["payload"]["description"] = "Local description"
    original = deepcopy(local)
    result = await merge_client(local)
    assert result["status"] == "applied", result
    assert result["revision"] == 3
    assert local == original

    record = (
        await async_session.execute(
            select(SyncOperation).where(
                SyncOperation.operation_id == local["operation_id"],
            )
        )
    ).scalar_one()
    assert record.base_revision == 1
    assert json.loads(record.result_json) == result
    assert (await merge_client(local)) == {**result, "status": "already_applied"}

    # Same operation ID with the rebased request is still a different request.
    rebased = deepcopy(local)
    rebased["base_revision"] = 2
    rebased["payload"]["title"] = "Remote title"
    reused = await merge_client(rebased)
    assert reused["status"] == "rejected"
    assert reused["error_code"] == "OPERATION_ID_REUSED"
    await assert_history(async_session, base, [1, 2, 3])


async def test_multi_field_conflict_is_sorted_cached_and_does_not_write_history(
    merge_client,
    async_session,
):
    base = goal_operation()
    assert (await merge_client(base))["revision"] == 1
    remote = edit(base)
    remote["payload"].update(title="Remote title", description="Remote description")
    remote["payload"]["goal"]["target_cycles"] = 2
    assert (await merge_client(remote))["revision"] == 2
    local = edit(base)
    local["payload"].update(title="Local title", description="Local description")
    local["payload"]["goal"]["target_cycles"] = 3
    result = await merge_client(local)
    assert result["status"] == "conflict"
    assert result["error_code"] == "REVISION_CONFLICT"
    assert result["conflict_kind"] == "overlapping_fields"
    assert result["conflicting_fields"] == [
        "description",
        "goal.target_cycles",
        "title",
    ]
    assert result["base_entity"]["title"] == "Health"
    assert result["local_entity"]["goal"]["target_cycles"] == 3
    assert result["entity"]["goal"]["target_cycles"] == 2
    await assert_history(async_session, base, [1, 2])

    later = edit(remote, revision=2)
    later["payload"]["title"] = "Later server title"
    assert (await merge_client(later))["revision"] == 3
    assert await merge_client(local) == result
    await assert_history(async_session, base, [1, 2, 3])


async def test_another_accounts_same_uuid_snapshot_cannot_replace_missing_merge_base(
    test_client,
    async_session,
    merge_client,
):
    base = goal_operation()
    assert (await merge_client(base))["revision"] == 1
    remote = edit(base)
    remote["payload"]["title"] = "Owner server title"
    assert (await merge_client(remote))["revision"] == 2
    owner = (
        await async_session.execute(
            select(User).where(
                User.username == "merge-characterization",
            )
        )
    ).scalar_one()
    owner_id = owner.id

    tokens = await register_account(test_client, async_session, "other-merge-owner")
    device = await register_device(
        test_client, tokens["access_token"], "other-merge-owner-phone"
    )
    other = goal_operation(entity_uuid=base["entity_uuid"], title="Private other title")
    response = await push(test_client, tokens["access_token"], device, [other])
    assert response.json()["results"][0]["status"] == "applied"
    await async_session.execute(
        delete(EntityRevisionSnapshot).where(
            EntityRevisionSnapshot.owner_user_id == owner_id,
            EntityRevisionSnapshot.entity_uuid == base["entity_uuid"],
            EntityRevisionSnapshot.revision == 1,
        )
    )
    await async_session.commit()

    local = edit(base)
    local["payload"]["description"] = "Owner local description"
    result = await merge_client(local)
    assert result["status"] == "conflict"
    assert result["error_code"] == "BASE_SNAPSHOT_UNAVAILABLE"
    assert result["conflict_kind"] == "base_snapshot_unavailable"
    assert result["base_entity"] is None
    assert result["entity"]["title"] == "Owner server title"
    assert result["local_entity"]["description"] == "Owner local description"
    assert "Private other title" not in json.dumps(result)
    assert await merge_client(local) == result
