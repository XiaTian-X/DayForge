"""Hierarchy and multi-entity transaction characterization for #19."""

import json
from copy import deepcopy
from uuid import uuid4

import pytest
from sqlalchemy import text
from sqlmodel import select

from src.v2.entity_snapshots import serialize_plan_node
from src.v2.models import EntityRevisionSnapshot, PlanNode, SyncChange
from tests.test_sync_v2 import activity_operation, goal_operation, push, register_account, register_device


def delete_node(node, policy=None, revision=1):
    return {
        "operation_id": str(uuid4()), "entity_type": "plan_node", "entity_uuid": node["entity_uuid"],
        "action": "delete", "base_revision": revision,
        "payload": {} if policy is None else {"child_policy": policy},
    }


@pytest.fixture
async def hierarchy(test_client, async_session):
    account = await register_account(test_client, async_session, "hierarchy_owner")
    token = account["access_token"]
    device = await register_device(test_client, token, "hierarchy-phone")

    async def submit(*operations):
        response = await push(test_client, token, device, list(operations))
        assert response.status_code == 200, response.text
        return response.json()["results"]

    goal = goal_operation(title="Parent goal")
    other_goal = goal_operation(title="Unrelated goal")
    children = []
    for mode, countdown in (("check", False), ("count", False), ("count", True), ("duration", False), ("duration", True)):
        child = activity_operation(parent_uuid=goal["entity_uuid"], tracking_mode=mode, is_countdown=countdown)
        child["payload"]["title"] = f"{mode}-{countdown}"
        children.append(child)
    operations = [goal, other_goal, *children]
    results = await submit(*operations)
    assert [item["status"] for item in results] == ["applied"] * len(operations)
    await async_session.commit()
    originals = {item["entity_uuid"]: item["entity"] for item in results}
    return submit, goal, other_goal, children, originals, token, device


async def current_nodes(session):
    nodes = (await session.execute(select(PlanNode).execution_options(populate_existing=True))).scalars().all()
    return {node.public_id: await serialize_plan_node(session, node) for node in nodes}


@pytest.mark.parametrize("policy", ["cascade_children", "detach_children"])
async def test_goal_deletion_updates_each_tracking_variant_once_and_is_account_scoped(test_client, async_session, hierarchy, policy):
    submit, goal, other_goal, children, originals, token, device = hierarchy
    # Another account uses identical public IDs; its parent/child must remain untouched.
    other = await register_account(test_client, async_session, "foreign_hierarchy_owner")
    other_device = await register_device(test_client, other["access_token"], "foreign-hierarchy-phone")
    foreign = await push(test_client, other["access_token"], other_device, [goal, children[0]])
    assert [item["status"] for item in foreign.json()["results"]] == ["applied", "applied"]
    foreign_before = await test_client.get("/api/v2/sync/bootstrap", headers={"Authorization": f"Bearer {other['access_token']}"}, params={"device_id": other_device})
    assert foreign_before.status_code == 200

    request = delete_node(goal, policy)
    removed, = await submit(request)
    assert removed["status"] == "applied" and removed["revision"] == 2
    assert await submit(request) == [{**removed, "status": "already_applied"}]
    repeated, = await submit(delete_node(goal, revision=0))
    assert repeated["entity"] == removed["entity"] and repeated["revision"] == 2
    response = await test_client.get("/api/v2/sync/changes", headers={"Authorization": f"Bearer {token}"}, params={"device_id": device, "cursor": 0})
    assert response.status_code == 200, response.text
    changes = response.json()["changes"]
    changed = [item for item in changes if item["revision"] == 2]
    assert len(changed) == 6
    assert changed[-1]["entity_uuid"] == goal["entity_uuid"]
    assert {item["entity_uuid"] for item in changed[:-1]} == {child["entity_uuid"] for child in children}
    assert changed[-1]["payload"] == removed["entity"]
    for item in changed[:-1]:
        expected = deepcopy(originals[item["entity_uuid"]])
        expected.update(revision=2, updated_at=removed["entity"]["updated_at"])
        if policy == "cascade_children":
            expected["deleted_at"] = removed["entity"]["deleted_at"]
        else:
            expected["parent_uuid"] = None
        assert item["payload"] == expected
        assert item["operation"] == ("delete" if policy == "cascade_children" else "upsert")
    boot = await test_client.get("/api/v2/sync/bootstrap", headers={"Authorization": f"Bearer {token}"}, params={"device_id": device})
    assert boot.status_code == 200
    visible = {item["entity_uuid"] for item in boot.json()["changes"]}
    expected_visible = {other_goal["entity_uuid"]}
    if policy == "detach_children":
        expected_visible.update(child["entity_uuid"] for child in children)
    assert visible == expected_visible
    foreign_after = await test_client.get("/api/v2/sync/bootstrap", headers={"Authorization": f"Bearer {other['access_token']}"}, params={"device_id": other_device})
    assert foreign_after.status_code == 200
    assert foreign_after.json()["changes"] == foreign_before.json()["changes"]


@pytest.mark.parametrize("policy", [None, "unsupported"])
async def test_goal_with_children_requires_an_explicit_valid_deletion_policy(async_session, hierarchy, policy):
    submit, goal, _, _, originals, _, _ = hierarchy
    request = delete_node(goal, policy)
    rejected, = await submit(request)
    assert rejected["status"] == "rejected" and rejected["error_code"] == "CHILD_POLICY_REQUIRED"
    assert await submit(request) == [rejected]
    assert await current_nodes(async_session) == originals
    assert len((await async_session.execute(select(SyncChange))).scalars().all()) == len(originals)


@pytest.mark.parametrize("index", range(5), ids=["check", "count-up", "count-down", "timer-up", "timer-down"])
async def test_reparent_and_detach_preserve_tracking_details_and_survive_old_parent_deletion(async_session, hierarchy, index):
    submit, goal, other_goal, children, originals, _, _ = hierarchy
    child = deepcopy(children[index])
    child.update(operation_id=str(uuid4()), base_revision=1)
    child["payload"]["parent_uuid"] = other_goal["entity_uuid"]
    moved, = await submit(child)
    assert moved["status"] == "applied" and moved["revision"] == 2
    assert moved["entity"]["parent_uuid"] == other_goal["entity_uuid"]
    assert moved["entity"]["activity"] == originals[child["entity_uuid"]]["activity"]
    assert (await submit(delete_node(goal, "cascade_children")))[0]["status"] == "applied"
    assert (await current_nodes(async_session))[child["entity_uuid"]] == moved["entity"]
    child.update(operation_id=str(uuid4()), base_revision=2)
    child["payload"]["parent_uuid"] = None
    detached, = await submit(child)
    assert detached["status"] == "applied" and detached["revision"] == 3
    assert detached["entity"]["parent_uuid"] is None
    assert detached["entity"]["activity"] == moved["entity"]["activity"]
    assert (await submit(delete_node(other_goal)))[0]["status"] == "applied"
    assert (await current_nodes(async_session))[child["entity_uuid"]] == detached["entity"]


@pytest.mark.parametrize("case", ["missing", "deleted", "activity", "self", "goal-parent", "kind-change", "duplicate-title"])
async def test_invalid_hierarchy_updates_leave_nodes_and_details_unchanged(async_session, hierarchy, case):
    submit, goal, other_goal, children, originals, _, _ = hierarchy
    request = deepcopy(children[0])
    request.update(operation_id=str(uuid4()), base_revision=1)
    if case == "missing":
        request["payload"]["parent_uuid"] = str(uuid4())
        code = "PARENT_NOT_FOUND"
    elif case == "deleted":
        assert (await submit(delete_node(other_goal)))[0]["status"] == "applied"
        request["payload"]["parent_uuid"] = other_goal["entity_uuid"]
        code = "PARENT_NOT_FOUND"
    elif case in {"activity", "self"}:
        request["payload"]["parent_uuid"] = children[1 if case == "activity" else 0]["entity_uuid"]
        code = "INVALID_PARENT"
    elif case == "goal-parent":
        request = deepcopy(goal)
        request.update(operation_id=str(uuid4()), base_revision=1)
        request["payload"]["parent_uuid"] = children[0]["entity_uuid"]
        code = "INVALID_PAYLOAD"
    elif case == "kind-change":
        request["payload"] = deepcopy(goal["payload"])
        request["payload"]["title"] = children[0]["payload"]["title"]
        code = "IMMUTABLE_NODE_KIND"
    else:
        request["payload"]["title"] = goal["payload"]["title"]
        code = "DUPLICATE_TITLE"
    before = await current_nodes(async_session)
    rejected, = await submit(request)
    assert rejected["status"] == "rejected" and rejected["error_code"] == code
    assert await submit(request) == [rejected]
    assert await current_nodes(async_session) == before


@pytest.mark.parametrize("table", ["entity_revision_snapshots", "sync_changes"])
@pytest.mark.parametrize("policy", ["cascade_children", "detach_children"])
@pytest.mark.parametrize("failure_at", ["first-child", "last-child", "parent"])
async def test_goal_delete_journal_failure_rolls_back_every_child_and_parent(async_session, hierarchy, table, policy, failure_at):
    submit, goal, _, children, originals, _, _ = hierarchy
    target = goal if failure_at == "parent" else children[0 if failure_at == "first-child" else -1]
    await async_session.execute(text(
        f"CREATE TRIGGER reject_plan_journal BEFORE INSERT ON {table} "
        f"WHEN NEW.entity_uuid = '{target['entity_uuid']}' "
        "BEGIN SELECT RAISE(ABORT, 'injected plan journal failure'); END"
    ))
    await async_session.commit()
    request = delete_node(goal, policy)
    following = goal_operation(title="Next operation survives")
    rejected, applied = await submit(request, following)
    assert rejected["status"] == "rejected" and rejected["error_code"] == "CONSTRAINT_VIOLATION"
    assert applied["status"] == "applied"
    expected = {**originals, following["entity_uuid"]: applied["entity"]}
    assert await current_nodes(async_session) == expected
    for model in (SyncChange, EntityRevisionSnapshot):
        rows = (await async_session.execute(select(model))).scalars().all()
        assert len(rows) == len(expected)
        assert {row.entity_uuid: json.loads(row.payload_json) for row in rows} == expected
        assert all(row.revision == 1 for row in rows)
    assert await submit(request, following) == [rejected, {**applied, "status": "already_applied"}]
    assert await current_nodes(async_session) == expected
