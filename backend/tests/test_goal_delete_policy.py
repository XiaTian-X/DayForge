"""Malformed deletion policies must not abort an otherwise valid sync batch (#103)."""

import json
from copy import deepcopy
from uuid import uuid4

import pytest
from sqlmodel import select

from src.v2.models import EntityRevisionSnapshot, SyncChange
from tests.test_plan_node_mutations import current_nodes, delete_node

# Explicit re-export: pytest discovers this fixture by its module attribute.
from tests.test_plan_node_mutations import hierarchy as hierarchy
from tests.test_sync_v2 import goal_operation


INVALID_PAYLOADS = [
    pytest.param({}, id="missing"),
    pytest.param({"child_policy": None}, id="null"),
    pytest.param({"child_policy": ""}, id="empty-string"),
    pytest.param({"child_policy": "unsupported"}, id="unknown-string"),
    pytest.param({"child_policy": True}, id="true"),
    pytest.param({"child_policy": False}, id="false"),
    pytest.param({"child_policy": 0}, id="integer"),
    pytest.param({"child_policy": 1.5}, id="fraction"),
    pytest.param({"child_policy": []}, id="empty-array"),
    pytest.param({"child_policy": ["cascade_children"]}, id="array"),
    pytest.param({"child_policy": {}}, id="empty-object"),
    pytest.param({"child_policy": {"policy": "detach_children"}}, id="object"),
]


async def journal(session):
    result = {}
    for model in (EntityRevisionSnapshot, SyncChange):
        rows = (
            (
                await session.execute(
                    select(model).order_by(model.entity_uuid, model.revision)
                )
            )
            .scalars()
            .all()
        )
        result[model.__name__] = [
            (row.entity_uuid, row.revision, row.operation, row.payload_json)
            for row in rows
        ]
    return result


@pytest.mark.parametrize("payload", INVALID_PAYLOADS)
async def test_invalid_policy_is_cached_without_mutation_and_other_batch_items_succeed(
    async_session, hierarchy, payload
):
    submit, goal, _, _, originals, _, _ = hierarchy
    request = delete_node(goal)
    request["payload"] = deepcopy(payload)
    earlier = goal_operation(title="Earlier valid operation")
    later = goal_operation(title="Later valid operation")
    first, rejected, last = await submit(earlier, request, later)
    assert first["status"] == last["status"] == "applied"
    assert rejected["status"] == "rejected"
    assert rejected["error_code"] == "CHILD_POLICY_REQUIRED"
    expected = {
        **originals,
        earlier["entity_uuid"]: first["entity"],
        later["entity_uuid"]: last["entity"],
    }
    assert await current_nodes(async_session) == expected
    history = await journal(async_session)
    for rows in history.values():
        assert len(rows) == len(expected)
        assert {uuid: json.loads(body) for uuid, _, _, body in rows} == expected
        assert all(revision == 1 for _, revision, _, _ in rows)
    assert await submit(earlier, request, later) == [
        {**first, "status": "already_applied"},
        rejected,
        {**last, "status": "already_applied"},
    ]
    assert await journal(async_session) == history

    # Correcting a rejected request still requires a fresh operation ID.
    corrected = deepcopy(request)
    corrected["payload"] = {"child_policy": "detach_children"}
    (reused,) = await submit(corrected)
    assert (
        reused["status"] == "rejected" and reused["error_code"] == "OPERATION_ID_REUSED"
    )
    assert await current_nodes(async_session) == expected
    corrected["operation_id"] = str(uuid4())
    (applied,) = await submit(corrected)
    assert applied["status"] == "applied" and applied["revision"] == 2
    corrected_history = await journal(async_session)
    assert await submit(request, corrected) == [
        rejected,
        {**applied, "status": "already_applied"},
    ]
    assert await journal(async_session) == corrected_history


@pytest.mark.parametrize("policy", [[], {}], ids=["array", "object"])
@pytest.mark.parametrize(
    "target_kind", ["empty-goal", "only-deleted-children", "activity"]
)
async def test_unused_policy_does_not_change_deletion_without_active_children(
    async_session, hierarchy, policy, target_kind
):
    submit, goal, empty_goal, children, _, _, _ = hierarchy
    if target_kind == "only-deleted-children":
        assert all(
            item["status"] == "applied"
            for item in await submit(*(delete_node(child) for child in children))
        )
        target = goal
    else:
        target = empty_goal if target_kind == "empty-goal" else children[0]
    request = delete_node(target, policy)
    (result,) = await submit(request)
    assert result["status"] == "applied" and result["revision"] == 2
    assert result["entity"]["deleted_at"].endswith("Z")
    after = await current_nodes(async_session)
    history = await journal(async_session)
    assert await submit(request) == [{**result, "status": "already_applied"}]
    assert await current_nodes(async_session) == after
    assert await journal(async_session) == history


@pytest.mark.parametrize("policy", [[], {}], ids=["array", "object"])
@pytest.mark.parametrize("state", ["missing", "stale", "deleted"])
async def test_existing_entity_and_revision_checks_precede_policy_validation(
    async_session, hierarchy, policy, state
):
    submit, goal, _, _, _, _, _ = hierarchy
    request = delete_node(goal, policy, revision=0)
    if state == "missing":
        request["entity_uuid"] = str(uuid4())
    elif state == "deleted":
        assert (await submit(delete_node(goal, "cascade_children")))[0][
            "status"
        ] == "applied"
    before = await current_nodes(async_session)
    history = await journal(async_session)
    (result,) = await submit(request)
    if state == "missing":
        assert (
            result["status"] == "rejected"
            and result["error_code"] == "ENTITY_NOT_FOUND"
        )
    elif state == "stale":
        assert (
            result["status"] == "conflict"
            and result["error_code"] == "REVISION_CONFLICT"
        )
        assert (
            result["revision"] == 1 and result["entity"] == before[goal["entity_uuid"]]
        )
    else:
        assert result["status"] == "applied" and result["revision"] == 2
        assert result["entity"] == before[goal["entity_uuid"]]
    expected = {**result, "status": "already_applied"} if state == "deleted" else result
    assert await submit(request) == [expected]
    assert await current_nodes(async_session) == before
    assert await journal(async_session) == history
