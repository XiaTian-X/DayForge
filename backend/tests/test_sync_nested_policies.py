"""HTTP regressions for atomic nested policy comparison (issue #98)."""

from copy import deepcopy
import json
from uuid import uuid4

import pytest
from sqlmodel import select

from src.v2.models import SyncOperation
from tests.test_sync_merge_characterization import assert_history, edit
# Explicit re-export keeps the imported fixture visible to pytest and linters.
from tests.test_sync_merge_characterization import merge_client as merge_client
from tests.test_sync_v2 import activity_operation, goal_operation


RULES = [
    pytest.param({"type": "daily"}, {"type": "weekly", "weekdays": [1]}, id="daily"),
    pytest.param(
        {"type": "weekly", "weekdays": [3, 1]},
        {"type": "weekly", "weekdays": [2, 4]}, id="weekly",
    ),
    pytest.param(
        {"type": "monthly", "day_of_month": 5},
        {"type": "monthly", "day_of_month": 20}, id="monthly",
    ),
    pytest.param(
        {"type": "interval", "every_days": 2, "start_date": "2026-09-01"},
        {"type": "interval", "every_days": 3, "start_date": "2026-09-01"}, id="interval",
    ),
    pytest.param({"type": "once"}, {"type": "once", "due_date": "2026-12-31"}, id="once"),
]

OPTIONAL_DATES = [
    pytest.param({"type": "daily"}, "start_date", id="daily"),
    pytest.param({"type": "weekly", "weekdays": [1]}, "start_date", id="weekly"),
    pytest.param({"type": "monthly", "day_of_month": 5}, "start_date", id="monthly"),
    pytest.param({"type": "once"}, "due_date", id="once"),
]


def activity_with_rule(rule):
    operation = activity_operation()
    operation["payload"]["activity"]["recurrence_rule"] = deepcopy(rule)
    if rule["type"] == "once":
        operation["payload"]["activity"]["completion_policy"] = "one_and_done"
    return operation


@pytest.mark.parametrize("base_rule,remote_rule", RULES)
@pytest.mark.parametrize("rename", [False, True], ids=["no-op", "rename"])
async def test_sparse_unchanged_recurrence_retains_remote_policy_and_replays_once(
    merge_client, async_session, base_rule, remote_rule, rename,
):
    base = activity_with_rule(base_rule)
    assert (await merge_client(base))["revision"] == 1
    remote = edit(base)
    remote["payload"]["activity"]["recurrence_rule"] = deepcopy(remote_rule)
    remote_result = await merge_client(remote)
    assert remote_result["status"] == "applied", remote_result
    assert remote_result["revision"] == 2
    local = edit(base)
    if rename:
        local["payload"]["title"] = "Only renamed locally"
    result = await merge_client(local)
    assert result["status"] == "applied", result
    assert result["revision"] == (3 if rename else 2)
    assert result["entity"]["title"] == local["payload"]["title"]
    assert result["entity"]["activity"]["recurrence_rule"] == remote_result["entity"]["activity"]["recurrence_rule"]
    assert await merge_client(local) == {**result, "status": "already_applied"}
    await assert_history(async_session, base, [1, 2, 3] if rename else [1, 2])


@pytest.mark.parametrize("rule,date_field", OPTIONAL_DATES)
@pytest.mark.parametrize("clear", [False, True], ids=["omitted", "explicit-null"])
async def test_stale_optional_recurrence_date_matches_existing_update_semantics(
    merge_client, async_session, rule, date_field, clear,
):
    base = activity_with_rule({**rule, date_field: "2026-09-01"})
    assert (await merge_client(base))["revision"] == 1
    remote = edit(base)
    remote["payload"]["title"] = "Remote title"
    assert (await merge_client(remote))["revision"] == 2
    local = edit(base)
    if clear:
        local["payload"]["activity"]["recurrence_rule"][date_field] = None
    else:
        del local["payload"]["activity"]["recurrence_rule"][date_field]
    result = await merge_client(local)
    assert result["status"] == "applied", result
    assert result["entity"]["title"] == "Remote title"
    assert result["entity"]["activity"]["recurrence_rule"][date_field] == (None if clear else "2026-09-01")
    assert result["revision"] == (3 if clear else 2)
    await assert_history(async_session, base, [1, 2, 3] if clear else [1, 2])


@pytest.mark.parametrize("kind,field,base_type,remote_type", [
    ("goal", "failure_policy", "strict", "loose"),
    ("goal", "failure_policy", "loose", "strict"),
    ("activity", "failure_policy", "strict", "loose"),
    ("activity", "failure_policy", "loose", "strict"),
    ("goal", "evaluation_policy", "manual", "manual"),
])
async def test_sparse_policies_do_not_add_revisions_or_undo_remote_changes(
    merge_client, async_session, kind, field, base_type, remote_type,
):
    base = goal_operation() if kind == "goal" else activity_operation()
    base["payload"][kind][field] = {"type": base_type}
    assert (await merge_client(base))["revision"] == 1
    remote = edit(base)
    remote["payload"]["title"] = "Remote title"
    remote["payload"][kind][field] = {"type": remote_type}
    remote_result = await merge_client(remote)
    assert remote_result["revision"] == 2
    local = edit(base)
    result = await merge_client(local)
    assert result["status"] == "applied", result
    assert result["revision"] == 2
    assert result["entity"] == remote_result["entity"]
    assert await merge_client(local) == {**result, "status": "already_applied"}
    await assert_history(async_session, base, [1, 2])


async def test_omitted_whole_policy_is_not_an_explicit_reset_to_schema_defaults(merge_client):
    base = activity_with_rule({"type": "weekly", "weekdays": [1]})
    assert (await merge_client(base))["revision"] == 1
    remote = edit(base)
    remote["payload"]["activity"]["recurrence_rule"]["weekdays"] = [2]
    assert (await merge_client(remote))["revision"] == 2
    local = edit(base)
    local["payload"]["title"] = "Local title"
    del local["payload"]["activity"]["recurrence_rule"]
    result = await merge_client(local)
    assert result["status"] == "applied", result
    assert result["entity"]["activity"]["recurrence_rule"]["weekdays"] == [2]

    explicit = edit(base)
    explicit["payload"]["activity"]["recurrence_rule"] = {"type": "daily"}
    conflict = await merge_client(explicit)
    assert conflict["status"] == "conflict"
    assert conflict["conflicting_fields"] == ["activity.recurrence_rule"]


async def test_genuine_nested_conflict_is_cached_and_preserves_all_three_versions(
    merge_client, async_session,
):
    base = activity_with_rule({"type": "weekly", "weekdays": [1], "start_date": "2026-09-01"})
    assert (await merge_client(base))["revision"] == 1
    remote = edit(base)
    remote["payload"]["activity"]["recurrence_rule"]["start_date"] = "2026-09-02"
    assert (await merge_client(remote))["revision"] == 2
    local = edit(base)
    local["payload"]["activity"]["recurrence_rule"] = {"type": "weekly", "weekdays": [2]}
    result = await merge_client(local)
    assert result["status"] == "conflict"
    assert result["conflicting_fields"] == ["activity.recurrence_rule"]
    # Inherit an omitted local date from the base, never from the remote edit.
    assert result["local_entity"]["activity"]["recurrence_rule"]["start_date"] == "2026-09-01"
    assert result["base_entity"]["activity"]["recurrence_rule"]["weekdays"] == [1]
    assert result["entity"]["activity"]["recurrence_rule"]["start_date"] == "2026-09-02"
    assert await merge_client(local) == result
    await assert_history(async_session, base, [1, 2])


async def test_recurrence_type_change_does_not_inherit_previous_type_dates(merge_client):
    base = activity_with_rule({"type": "daily", "start_date": "2026-09-01"})
    assert (await merge_client(base))["revision"] == 1
    remote = edit(base)
    remote["payload"]["title"] = "Remote title"
    assert (await merge_client(remote))["revision"] == 2
    local = edit(base)
    local["payload"]["activity"]["recurrence_rule"] = {"type": "weekly", "weekdays": [1]}
    result = await merge_client(local)
    assert result["status"] == "applied", result
    assert result["entity"]["activity"]["recurrence_rule"] == {
        "type": "weekly", "schema_version": 1, "interval": 1, "weekdays": [1], "start_date": None,
    }


async def test_same_remote_policy_with_explicit_defaults_is_a_noop(merge_client, async_session):
    base = activity_with_rule({"type": "daily"})
    assert (await merge_client(base))["revision"] == 1
    remote = edit(base)
    remote["payload"]["activity"]["recurrence_rule"] = {"type": "weekly", "weekdays": [1, 3]}
    remote_result = await merge_client(remote)
    assert remote_result["revision"] == 2
    local = edit(base)
    local["payload"]["activity"]["recurrence_rule"] = {
        "schema_version": 1, "type": "weekly", "weekdays": [3, 1], "interval": 1, "start_date": None,
    }
    result = await merge_client(local)
    assert result["status"] == "applied", result
    assert result["revision"] == 2
    assert result["entity"] == remote_result["entity"]
    assert await merge_client(local) == {**result, "status": "already_applied"}
    await assert_history(async_session, base, [1, 2])


async def test_pre_upgrade_cached_conflict_stays_immutable_but_new_operation_can_merge(
    merge_client, async_session,
):
    base = activity_with_rule({"type": "daily"})
    assert (await merge_client(base))["revision"] == 1
    remote = edit(base)
    remote["payload"]["activity"]["recurrence_rule"] = {"type": "weekly", "weekdays": [1]}
    remote_result = await merge_client(remote)
    assert remote_result["revision"] == 2
    local = edit(base)
    local["payload"]["title"] = "Only renamed locally"

    # Seed the persisted result of the pre-upgrade algorithm, without changing the request hash.
    from src.v2.encoding import canonical_json, operation_hash
    from src.v2.schemas import SyncOperationRequest, SyncOperationResult

    previous = (await async_session.execute(select(SyncOperation).where(
        SyncOperation.operation_id == base["operation_id"],
    ))).scalar_one()
    result = SyncOperationResult(
        operation_id=local["operation_id"], entity_type="plan_node", entity_uuid=base["entity_uuid"],
        status="conflict", error_code="REVISION_CONFLICT", revision=2,
        entity=remote_result["entity"], conflicting_fields=["activity.recurrence_rule"],
        conflict_kind="overlapping_fields", message="The same fields changed on another device",
    ).model_dump(mode="json")
    async_session.add(SyncOperation(
        user_id=previous.user_id, device_id=previous.device_id,
        operation_id=local["operation_id"], request_hash=operation_hash(SyncOperationRequest.model_validate(local)),
        status="conflict", entity_type="plan_node", entity_uuid=base["entity_uuid"],
        action="upsert", base_revision=1, error_code="REVISION_CONFLICT",
        result_json=canonical_json(result),
    ))
    await async_session.commit()
    assert await merge_client(local) == result
    await assert_history(async_session, base, [1, 2])
    retry = deepcopy(local)
    retry["operation_id"] = str(uuid4())
    applied = await merge_client(retry)
    assert applied["status"] == "applied", applied
    assert applied["entity"]["title"] == "Only renamed locally"
    assert applied["entity"]["activity"]["recurrence_rule"]["type"] == "weekly"
    assert await merge_client(retry) == {**applied, "status": "already_applied"}
    stored = (await async_session.execute(select(SyncOperation).where(
        SyncOperation.operation_id == local["operation_id"],
    ))).scalar_one()
    assert json.loads(stored.result_json) == result
    await assert_history(async_session, base, [1, 2, 3])
