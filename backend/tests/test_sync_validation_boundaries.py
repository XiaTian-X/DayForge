"""Invalid updates reject one operation without poisoning valid sync siblings."""

import pytest
from sqlalchemy import delete
from sqlmodel import select

from src.v2.models import EntityRevisionSnapshot, GoalDetail, PlanNode, SyncChange
from tests.test_sync_merge_characterization import edit, assert_history
from tests.test_sync_merge_characterization import merge_client as merge_client
from tests.test_sync_persistence import snapshot_setup as snapshot_setup
from tests.test_sync_v2 import goal_operation, push


@pytest.mark.parametrize("index", [0, 1, 3, 6], ids=["goal", "activity", "metric", "link"])
@pytest.mark.parametrize("base_state", ["current", "stale", "unavailable"])
@pytest.mark.parametrize("shape", ["empty", "invalid-field"])
async def test_invalid_structural_payload_is_cached_and_does_not_abort_batch(test_client, async_session, snapshot_setup, index, base_state, shape):
    owner, token, device, batch, _ = snapshot_setup
    original = batch["operations"][index]
    remote = edit(original)
    remote["payload"].update({"coefficient": "2"} if index == 6 else {"description": "Remote edit"})
    assert (await push(test_client, token, device, [remote])).json()["results"][0]["revision"] == 2
    if base_state == "unavailable":
        await async_session.execute(delete(EntityRevisionSnapshot).where(
            EntityRevisionSnapshot.owner_user_id == owner,
            EntityRevisionSnapshot.entity_type == original["entity_type"],
            EntityRevisionSnapshot.entity_uuid == original["entity_uuid"],
            EntityRevisionSnapshot.revision == 1,
        ))
    await async_session.commit()
    invalid = edit(original, revision=2 if base_state == "current" else 1)
    if shape == "empty":
        invalid["payload"] = {}
    elif index == 0:
        invalid["payload"]["goal"].update(start_date="2026-09-20", due_date="2026-09-01")
    elif index == 1:
        invalid["payload"]["activity"]["timezone"] = "Invalid/Timezone"
    elif index == 3:
        invalid["payload"]["decimal_places"] = 7
    else:
        invalid["payload"]["activity_uuid"] = "invalid-uuid"
    before, after = goal_operation(title="Valid before"), goal_operation(title="Valid after")
    response = await push(test_client, token, device, [before, invalid, after])
    assert response.status_code == 200, response.text
    results = response.json()["results"]
    assert [r["status"] for r in results] == ["applied", "rejected", "applied"]
    assert results[1]["error_code"] == "INVALID_PAYLOAD"
    await async_session.commit()
    changes = (await async_session.execute(select(SyncChange).where(
        SyncChange.entity_uuid == original["entity_uuid"],
    ).order_by(SyncChange.revision))).scalars().all()
    assert [row.revision for row in changes] == [1, 2]
    replay = await push(test_client, token, device, [before, invalid, after])
    assert replay.json()["results"] == [{**r, "status": "already_applied"} if r["status"] == "applied" else r for r in results]
    corrected = edit(remote, revision=2)
    fixed = (await push(test_client, token, device, [corrected])).json()["results"][0]
    assert fixed["status"] == "applied" and fixed["revision"] == 3
    assert (await push(test_client, token, device, [invalid])).json()["results"][0] == results[1]


@pytest.mark.parametrize("field,value", [("start_date", "2026-09-20"), ("due_date", "2026-08-20")])
@pytest.mark.parametrize("stale", [False, True])
async def test_partial_goal_dates_validate_final_retained_range(merge_client, async_session, field, value, stale):
    original = goal_operation()
    original["payload"]["goal"].update(start_date="2026-09-01", due_date="2026-09-10")
    baseline = await merge_client(original)
    if stale:
        remote = edit(original)
        remote["payload"]["description"] = "Remote edit"
        baseline = await merge_client(remote)
    invalid = edit(original)
    invalid["payload"]["goal"] = {field: value}
    rejected = await merge_client(invalid)
    assert rejected["status"] == "rejected" and rejected["error_code"] == "INVALID_PAYLOAD"
    assert await merge_client(invalid) == rejected
    node = (await async_session.execute(select(PlanNode))).scalar_one()
    detail = await async_session.get(GoalDetail, node.id)
    assert node.revision == baseline["revision"]
    assert str(detail.start_date) == "2026-09-01" and str(detail.due_date) == "2026-09-10"
    await assert_history(async_session, original, [1, 2] if stale else [1])
    corrected = edit(original, revision=baseline["revision"])
    corrected["payload"]["goal"] = {field: "2026-09-05"}
    applied = await merge_client(corrected)
    assert applied["status"] == "applied" and applied["revision"] == baseline["revision"] + 1
    assert await merge_client(invalid) == rejected


@pytest.mark.parametrize("field", ["start_date", "due_date"])
@pytest.mark.parametrize("value", [None, "2026-09-05"])
@pytest.mark.parametrize("stale", [False, True])
async def test_valid_partial_goal_dates_preserve_omission_and_explicit_null(merge_client, async_session, field, value, stale):
    original = goal_operation()
    original["payload"]["goal"].update(start_date="2026-09-01", due_date="2026-09-10")
    await merge_client(original)
    if stale:
        remote = edit(original)
        remote["payload"]["description"] = "Remote edit"
        await merge_client(remote)
    local = edit(original)
    local["payload"]["goal"] = {field: value}
    result = await merge_client(local)
    assert result["status"] == "applied", result
    expected = {"start_date": "2026-09-01", "due_date": "2026-09-10", field: value}
    assert {key: result["entity"]["goal"][key] for key in expected} == expected
    assert await merge_client(local) == {**result, "status": "already_applied"}
    await assert_history(async_session, original, [1, 2, 3] if stale else [1, 2])


async def test_omitting_both_goal_dates_keeps_existing_range(merge_client):
    original = goal_operation()
    original["payload"]["goal"].update(start_date="2026-09-01", due_date="2026-09-10")
    await merge_client(original)
    local = edit(original)
    local["payload"]["goal"] = {}
    local["payload"]["title"] = "Renamed without dates"
    result = await merge_client(local)
    assert result["status"] == "applied"
    assert result["entity"]["goal"]["start_date"] == "2026-09-01"
    assert result["entity"]["goal"]["due_date"] == "2026-09-10"
