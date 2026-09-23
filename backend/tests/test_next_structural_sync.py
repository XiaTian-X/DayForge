"""Explicit v5 domain writes and recovery; the production HTTP router stays v4."""

from copy import deepcopy
import json
from uuid import uuid4

import pytest
from sqlalchemy import select, text, update
from sqlmodel import col

from src.auth.models import User
from src.v2.errors import DomainError
from src.v2.models import (
    ActivityDetail,
    PlanNode,
    TimerSession,
    UserSyncPolicy,
    utc_now,
)
from src.v2.next_sync_contract import NextSyncPushRequest
from src.v2.one_time_storage import load_one_time_activity
from src.v2.read_service import bootstrap, pull_changes
from src.v2.service import process_push
from tests.test_asset_declarations import ASSET, asset, setup, write_asset
from tests.test_http_commit_boundary import database_state
from tests.test_object_appearance import appearance
from tests.test_one_time_mutations import operation as fact_operation
from tests.test_one_time_storage import ACTIVITY


GOAL = "d2000000-0000-4000-8000-000000000001"
METRIC = "d2000000-0000-4000-8000-000000000002"


def node(*, once=False, mode="check", countdown=False, title="activity"):
    return dict(
        node_kind="activity",
        title=title,
        appearance=appearance(role="task.check" if once else "habit.water").model_dump(
            mode="json"
        ),
        activity=dict(
            tracking_mode=mode,
            is_countdown=countdown,
            completion_policy="one_and_done" if once else "recurring",
            recurrence_rule={"type": "once" if once else "daily"},
            target_value=60 if mode == "duration" else 1,
            failure_policy={"type": "loose" if once else "strict"},
            timezone="Asia/Shanghai",
        ),
    )


def goal():
    return dict(
        node_kind="goal",
        title="goal",
        goal={
            "start_date": "2026-09-01",
            "due_date": "2026-12-31",
        },
        appearance=appearance(role="goal.focus").model_dump(mode="json"),
    )


def metric():
    return dict(
        name="metric",
        unit="kg",
        appearance=appearance(role="metric.weight").model_dump(mode="json"),
    )


def operation(
    payload=None, *, identity=ACTIVITY, kind="plan_node", revision=0, action="upsert"
):
    return dict(
        operation_id=str(uuid4()),
        entity_type=kind,
        entity_uuid=identity,
        action=action,
        base_revision=revision,
        payload=payload or {},
    )


def request(context, operations):
    return NextSyncPushRequest.model_validate(
        dict(device_id=context.device_id, operations=operations)
    )


async def submit(factory, context, operations, owner=1):
    async with factory.begin() as session:
        user = await session.get(User, owner)
        assert user is not None
        response = await process_push(
            user, request(context, operations), session, next_protocol=True
        )
        return response.model_dump(mode="json")["results"]


async def snapshot(factory, context, owner=1):
    async with factory.begin() as session:
        user = await session.get(User, owner)
        assert user is not None
        return await bootstrap(user, context.device_id, session, next_protocol=True)


async def projection(factory):
    async with factory() as session:
        return (await load_one_time_activity(session, 1, ACTIVITY)).projection.state


async def test_create_all_modes_complete_edit_undo_and_recover_original_snapshots(
    runtime_engine,
):
    factory, contexts = await setup(runtime_engine)
    body = node(once=True)
    body["parent_uuid"] = GOAL
    operations = [
        operation(goal(), identity=GOAL),
        operation(body),
        operation(metric(), identity=METRIC, kind="metric"),
    ]
    for index, (mode, countdown) in enumerate(
        (
            ("check", False),
            ("count", False),
            ("count", True),
            ("duration", False),
            ("duration", True),
        )
    ):
        operations.append(
            operation(
                node(mode=mode, countdown=countdown, title=f"habit-{index}"),
                identity=f"d3000000-0000-4000-8000-{index:012d}",
            )
        )
    created = await submit(factory, contexts[1], operations)
    assert [item["status"] for item in created] == ["applied"] * 8
    for item in created:
        assert item["entity"]["appearance"]
        assert "icon" not in item["entity"] and "color_hex" not in item["entity"]
        assert "one_time_state" not in item["entity"]
    initial = await snapshot(factory, contexts[1])
    assert len(initial.changes) == 8
    assert initial.one_time_checkpoints[0].state.model_dump() == dict(
        version=0, head_event_uuid=None, completion_event_uuid=None
    )
    done = await submit(factory, contexts[1], [fact_operation()])
    assert done[0]["status"] == "applied"
    before_edit = await projection(factory)
    edited = deepcopy(body)
    edited.update(
        title="renamed", appearance=appearance(role="task.list").model_dump(mode="json")
    )
    result = (await submit(factory, contexts[1], [operation(edited, revision=1)]))[0]
    assert result["status"] == "applied" and result["revision"] == 2
    assert await projection(factory) == before_edit
    undone = (await submit(factory, contexts[1], [fact_operation(1)]))[0]
    assert undone["status"] == "applied"
    recovered = await snapshot(factory, contexts[1])
    assert recovered.one_time_checkpoints[0].state.version == 2
    assert recovered.one_time_checkpoints[0].state.completion_event_uuid is None
    assert len(recovered.changes) == 10
    async with factory.begin() as session:
        user = await session.get(User, 1)
        assert user is not None
        pulled = await pull_changes(
            user, contexts[1].device_id, 0, 100, session, next_protocol=True
        )
        assert len(pulled.changes) == 11
        assert pulled.changes[1].payload == created[1]["entity"]
        assert pulled.changes[-1].payload == undone["entity"]
    replay = await submit(factory, contexts[1], operations)
    assert replay == [{**item, "status": "already_applied"} for item in created]


@pytest.mark.parametrize("start_mode", ["check", "count", "duration", "one_and_done"])
async def test_policy_conversion_without_history_is_explicit_and_initializes_atomically(
    runtime_engine, start_mode
):
    factory, contexts = await setup(runtime_engine)
    before = node(
        once=start_mode == "one_and_done",
        mode="check" if start_mode == "one_and_done" else start_mode,
    )
    after = node(once=start_mode != "one_and_done")
    assert (await submit(factory, contexts[1], [operation(before)]))[0][
        "status"
    ] == "applied"
    changed = (await submit(factory, contexts[1], [operation(after, revision=1)]))[0]
    assert changed["status"] == "applied", changed
    async with factory() as session:
        detail = (await session.execute(select(ActivityDetail))).scalar_one()
        assert detail.completion_policy == after["activity"]["completion_policy"]
        assert detail.one_time_version == (None if start_mode == "one_and_done" else 0)
        assert (
            detail.one_time_head_event_uuid is None
            and detail.one_time_completion_event_uuid is None
        )
    assert len((await snapshot(factory, contexts[1])).one_time_checkpoints) == (
        0 if start_mode == "one_and_done" else 1
    )


@pytest.mark.parametrize(
    "history",
    [
        "completed",
        "undone",
        "ordinary",
        "tombstone",
        "running",
        "paused",
        "cancelled",
        "timer-completed",
    ],
)
async def test_policy_conversion_never_reinterprets_existing_facts_or_timer_history(
    runtime_engine, history
):
    factory, contexts = await setup(runtime_engine)
    once = history in {"completed", "undone"}
    timer = history in {"running", "paused", "cancelled", "timer-completed"}
    assert (
        await submit(
            factory,
            contexts[1],
            [operation(node(once=once, mode="duration" if timer else "check"))],
        )
    )[0]["status"] == "applied"
    if timer:
        async with factory.begin() as session:
            stored = (await session.execute(select(PlanNode))).scalar_one()
            now = utc_now()
            session.add(
                TimerSession(
                    owner_user_id=1,
                    activity_node_id=stored.id,
                    controller_device_id=1,
                    state="completed" if history == "timer-completed" else history,
                    started_at=now,
                    state_changed_at=now,
                    timezone="UTC",
                )
            )
    else:
        fact = fact_operation()
        if not once:
            fact.payload.pop("one_time")
        assert (await submit(factory, contexts[1], [fact]))[0]["status"] == "applied"
        if history == "undone":
            assert (await submit(factory, contexts[1], [fact_operation(1)]))[0][
                "status"
            ] == "applied"
        if history == "tombstone":
            async with factory.begin() as session:
                await session.execute(
                    text("UPDATE activity_events SET deleted_at=occurred_at")
                )
    before = await database_state(runtime_engine)
    rejected = (
        await submit(factory, contexts[1], [operation(node(once=not once), revision=1)])
    )[0]
    assert rejected["error_code"] == "COMPLETION_POLICY_LOCKED"
    after = await database_state(runtime_engine)
    for table in before:
        if table not in {"sync_operations", "sync_cursors", "client_devices"}:
            assert after[table] == before[table], table


@pytest.mark.parametrize(
    "case",
    [
        "cross-account",
        "wrong-purpose",
        "missing",
        "role-purpose",
        "legacy",
        "injected-state",
    ],
)
async def test_bad_appearance_or_policy_is_per_item_rejected_without_partial_writes(
    runtime_engine, case
):
    factory, contexts = await setup(runtime_engine)
    body = node()
    if case in {"cross-account", "wrong-purpose", "missing"}:
        body["appearance"] = appearance(asset_id=ASSET).model_dump(mode="json")
        if case != "missing":
            owner = 2 if case == "cross-account" else 1
            await write_asset(
                factory,
                asset(
                    contexts[owner],
                    purpose="task" if case == "wrong-purpose" else "general",
                ),
                owner=owner,
            )
    elif case == "role-purpose":
        body["appearance"] = appearance(role="task.check").model_dump(mode="json")
    elif case == "legacy":
        body.pop("appearance")
        body.update(icon="favorite", color_hex="#123456")
    else:
        body["activity"]["one_time_version"] = 1
    bad = operation(body)
    result = await submit(
        factory, contexts[1], [bad, operation(metric(), identity=METRIC, kind="metric")]
    )
    expected = {
        "cross-account": "ASSET_NOT_FOUND",
        "missing": "ASSET_NOT_FOUND",
        "wrong-purpose": "ICON_PURPOSE_MISMATCH",
    }.get(case, "INVALID_PAYLOAD")
    assert result[0]["error_code"] == expected and result[0]["entity"] is None
    assert result[1]["status"] == "applied"
    async with factory() as session:
        assert (await session.execute(select(PlanNode))).scalars().all() == []
        for table in ("activity_details", "plan_node_appearances"):
            assert (await session.execute(text(f"SELECT * FROM {table}"))).all() == []
    assert (await submit(factory, contexts[1], [bad]))[0] == result[0]


@pytest.mark.parametrize("kind", ["plan_node", "metric"])
async def test_atomic_icon_merge_independent_color_changes_and_overlap(
    runtime_engine, kind
):
    factory, contexts = await setup(runtime_engine)
    await write_asset(factory, asset(contexts[1]))
    original = node() if kind == "plan_node" else metric()
    create = operation(original, kind=kind)
    first = (await submit(factory, contexts[1], [create]))[0]
    assert first["status"] == "applied"
    remote = deepcopy(original)
    remote["appearance"]["icon"] = {"kind": "asset", "asset_id": ASSET}
    assert (
        await submit(factory, contexts[1], [operation(remote, kind=kind, revision=1)])
    )[0]["status"] == "applied"
    local = deepcopy(original)
    local["appearance"]["accent_color"] = "#112233"
    merged = (
        await submit(factory, contexts[1], [operation(local, kind=kind, revision=1)])
    )[0]
    assert merged["status"] == "applied" and merged["revision"] == 3
    assert merged["entity"]["appearance"] == {
        **remote["appearance"],
        "accent_color": "#112233",
    }
    overlap = deepcopy(original)
    overlap["appearance"]["icon"] = {"kind": "role", "role": "habit.sleep"}
    conflict = (
        await submit(factory, contexts[1], [operation(overlap, kind=kind, revision=1)])
    )[0]
    assert conflict["status"] == "conflict"
    assert conflict["conflicting_fields"] == ["appearance.icon"]
    assert conflict["entity"] == merged["entity"]
    assert conflict["base_entity"] == first["entity"]
    no_op = (
        await submit(factory, contexts[1], [operation(local, kind=kind, revision=1)])
    )[0]
    assert no_op["revision"] == 3 and no_op["entity"] == merged["entity"]


@pytest.mark.parametrize("policy", ["cascade_children", "detach_children"])
async def test_goal_delete_preserves_appearance_history_and_task_completion(
    runtime_engine, policy
):
    factory, contexts = await setup(runtime_engine)
    body = node(once=True)
    body["parent_uuid"] = GOAL
    assert all(
        item["status"] == "applied"
        for item in await submit(
            factory,
            contexts[1],
            [operation(goal(), identity=GOAL), operation(body), fact_operation()],
        )
    )
    before = await projection(factory)
    deleted = (
        await submit(
            factory,
            contexts[1],
            [
                operation(
                    {"child_policy": policy}, identity=GOAL, revision=1, action="delete"
                )
            ],
        )
    )[0]
    assert (
        deleted["status"] == "applied"
        and deleted["entity"]["appearance"] == goal()["appearance"]
    )
    recovered = await snapshot(factory, contexts[1])
    assert len(recovered.one_time_checkpoints) == (
        0 if policy == "cascade_children" else 1
    )
    if policy == "detach_children":
        assert recovered.one_time_checkpoints[0].state == before
        assert recovered.changes[0].payload["parent_uuid"] is None
    async with factory() as session:
        assert (
            await session.execute(text("SELECT count(*) FROM plan_node_appearances"))
        ).scalar_one() == 2
        changes = (
            (
                await session.execute(
                    text(
                        "SELECT payload_json FROM sync_changes WHERE entity_uuid=:id AND entity_type='plan_node' ORDER BY sequence"
                    ),
                    {"id": ACTIVITY},
                )
            )
            .scalars()
            .all()
        )
        assert len(changes) == 2
        assert (
            json.loads(changes[0])["appearance"]
            == json.loads(changes[1])["appearance"]
            == body["appearance"]
        )
        detail = (await session.execute(select(ActivityDetail))).scalar_one()
        assert (
            detail.one_time_version == 1
            and detail.one_time_completion_event_uuid == before.completion_event_uuid
        )


async def test_same_account_ids_are_independent_and_fact_device_cannot_edit_structure(
    runtime_engine,
):
    factory, contexts = await setup(runtime_engine)
    original = operation(node(once=True))
    for owner in (1, 2):
        assert (await submit(factory, contexts[owner], [original], owner))[0][
            "status"
        ] == "applied"
    assert (await submit(factory, contexts[1], [fact_operation()]))[0][
        "status"
    ] == "applied"
    assert (await snapshot(factory, contexts[1])).one_time_checkpoints[
        0
    ].state.version == 1
    assert (await snapshot(factory, contexts[2], 2)).one_time_checkpoints[
        0
    ].state.version == 0
    async with factory.begin() as session:
        await session.execute(
            update(UserSyncPolicy)
            .where(col(UserSyncPolicy.user_id) == 1)
            .values(primary_editor_device_id=None)
        )
    denied = (
        await submit(
            factory,
            contexts[1],
            [operation(node(once=True, title="denied"), revision=1)],
        )
    )[0]
    assert denied["error_code"] == "DEVICE_CAPABILITY_DENIED"
    assert (await submit(factory, contexts[1], [fact_operation(1)]))[0][
        "status"
    ] == "applied"


@pytest.mark.parametrize(
    "table", ["plan_node_appearances", "sync_changes", "entity_revision_snapshots"]
)
async def test_inner_appearance_failure_rolls_back_structure_and_keeps_rejection_identity(
    runtime_engine, table
):
    factory, contexts = await setup(runtime_engine)
    async with runtime_engine.begin() as connection:
        await connection.execute(
            text(
                f"CREATE TRIGGER injected BEFORE INSERT ON {table} BEGIN SELECT RAISE(ABORT, 'appearance fault'); END"
            )
        )
    body = operation(node(once=True))
    rejected = (await submit(factory, contexts[1], [body]))[0]
    assert rejected["error_code"] == "CONSTRAINT_VIOLATION"
    async with factory() as session:
        for name in (
            "plan_nodes",
            "activity_details",
            "plan_node_appearances",
            "sync_changes",
            "entity_revision_snapshots",
        ):
            assert (await session.execute(text(f"SELECT * FROM {name}"))).all() == []
    async with runtime_engine.begin() as connection:
        await connection.execute(text("DROP TRIGGER injected"))
    assert (await submit(factory, contexts[1], [body]))[0] == rejected
    body["operation_id"] = str(uuid4())
    assert (await submit(factory, contexts[1], [body]))[0]["status"] == "applied"


@pytest.mark.parametrize(
    "corruption", ["missing-history", "missing-appearance", "invalid-appearance"]
)
async def test_bootstrap_failure_never_advances_cursor_or_accepts_partial_state(
    runtime_engine, corruption
):
    factory, contexts = await setup(runtime_engine)
    assert all(
        item["status"] == "applied"
        for item in await submit(
            factory, contexts[1], [operation(node(once=True)), fact_operation()]
        )
    )
    async with runtime_engine.begin() as connection:
        if corruption == "missing-history":
            await connection.execute(text("DELETE FROM activity_events"))
        elif corruption == "missing-appearance":
            await connection.execute(text("DELETE FROM plan_node_appearances"))
        else:
            await connection.execute(
                text("UPDATE plan_node_appearances SET accent_color='#gggggg'")
            )
    before = await database_state(runtime_engine)
    with pytest.raises(DomainError) as error:
        await snapshot(factory, contexts[1])
    assert (
        error.value.code
        == {
            "missing-history": "TASK_HISTORY_INCOMPLETE",
            "missing-appearance": "APPEARANCE_STATE_UNINITIALIZED",
            "invalid-appearance": "APPEARANCE_STATE_INVALID",
        }[corruption]
    )
    assert await database_state(runtime_engine) == before
