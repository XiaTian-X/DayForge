"""Push transaction, replay and facts-only task-finalization audit (#19)."""

from copy import deepcopy
from uuid import uuid4

import pytest
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import async_sessionmaker
from sqlmodel import select

from src.auth.models import User
from src.v2.encoding import operation_hash
from src.v2.errors import DomainError
from src.v2.models import (
    ClientDevice,
    EntityRevisionSnapshot,
    PlanNode,
    SyncChange,
    SyncCursor,
    SyncOperation,
)
from src.v2.schemas import SyncOperationRequest, SyncPushRequest
from src.v2.service import process_push
from tests.test_event_link_mutations import event_operation
from tests.test_metric_mutations import deletion
from tests.test_sync_nested_policies import activity_with_rule
from tests.test_sync_v2 import (
    activity_operation,
    goal_operation,
    metric_operation,
    push,
    register_account,
    register_device,
)


@pytest.fixture
async def push_context(test_client, async_session):
    owner = await register_account(test_client, async_session, "push_audit_owner")
    token = owner["access_token"]
    writer = await register_device(test_client, token, "push-audit-writer")
    secondary = await register_device(test_client, token, "push-audit-secondary")
    user = (
        await async_session.execute(
            select(User).where(User.username == "push_audit_owner")
        )
    ).scalar_one()
    await async_session.commit()
    return user.id, token, writer, secondary


def hide_initial_operation_lookup(monkeypatch, session, operation_id):
    """Model a race-window stale lookup; the subsequent insert hits real SQLite uniqueness."""
    execute = session.execute
    hidden: list[bool] = []

    async def wrapped(statement, *args, **kwargs):
        is_operation = any(
            d.get("entity") is SyncOperation
            for d in getattr(statement, "column_descriptions", [])
        )
        if (
            not hidden
            and is_operation
            and operation_id in statement.compile().params.values()
        ):
            hidden.append(True)
            return await execute(statement.where(False), *args, **kwargs)
        return await execute(statement, *args, **kwargs)

    monkeypatch.setattr(session, "execute", wrapped)
    return hidden


@pytest.mark.parametrize("status", ["applied", "conflict", "rejected"])
@pytest.mark.parametrize("different_hash", [False, True])
async def test_unique_insert_race_reuses_result_without_mutating_it(
    test_client, async_session, monkeypatch, push_context, status, different_hash
):
    _, token, writer, _ = push_context
    operation = goal_operation()
    if status == "conflict":
        assert (await push(test_client, token, writer, [operation])).json()["results"][
            0
        ]["status"] == "applied"
        operation["operation_id"] = str(uuid4())
    elif status == "rejected":
        operation["payload"] = {}
    first = (await push(test_client, token, writer, [operation])).json()["results"][0]
    assert first["status"] == status
    await async_session.commit()
    row = (
        await async_session.execute(
            select(SyncOperation).where(
                SyncOperation.operation_id == operation["operation_id"]
            )
        )
    ).scalar_one()
    stored = row.result_json
    candidate = deepcopy(operation)
    if different_hash:
        candidate["payload"]["description"] = "Different request with the same ID"
    hidden = hide_initial_operation_lookup(
        monkeypatch, async_session, operation["operation_id"]
    )
    following = goal_operation(title="Following race")
    response = await push(test_client, token, writer, [candidate, following])
    assert response.status_code == 200, response.text
    result, later = response.json()["results"]
    assert hidden == [True]
    if different_hash:
        assert (
            result["status"] == "rejected"
            and result["error_code"] == "OPERATION_ID_REUSED"
        )
    else:
        assert result == (
            {**first, "status": "already_applied"} if status == "applied" else first
        )
    assert later["status"] == "applied"
    await async_session.refresh(row)
    assert row.result_json == stored
    for model in (EntityRevisionSnapshot, SyncChange):
        rows = (await async_session.execute(select(model))).scalars().all()
        assert len(rows) == (1 if status == "rejected" else 2)


@pytest.mark.parametrize("raced", [False, True])
@pytest.mark.parametrize("incomplete", ["processing", "empty-result"])
async def test_incomplete_replay_aborts_entire_batch_and_allows_safe_retry(
    async_session,
    async_engine,
    test_client,
    monkeypatch,
    push_context,
    raced,
    incomplete,
):
    owner, token, writer, _ = push_context
    operation = goal_operation(title="Pending operation")
    device = (
        await async_session.execute(
            select(ClientDevice).where(ClientDevice.public_id == writer)
        )
    ).scalar_one()
    pending = SyncOperation(
        user_id=owner,
        device_id=device.id,
        operation_id=operation["operation_id"],
        request_hash=operation_hash(SyncOperationRequest.model_validate(operation)),
        entity_type="plan_node",
        entity_uuid=operation["entity_uuid"],
        action="upsert",
        status="processing" if incomplete == "processing" else "applied",
        result_json="{}",
    )
    async_session.add(pending)
    await async_session.commit()
    before = goal_operation(title="Must roll back before pending operation")
    after = goal_operation(title="Must not run after pending operation")
    sessions = async_sessionmaker(async_engine, expire_on_commit=False)
    with pytest.raises(DomainError) as failure:
        async with sessions.begin() as session:
            if raced:
                hidden = hide_initial_operation_lookup(
                    monkeypatch, session, operation["operation_id"]
                )
            user = await session.get(User, owner)
            assert user is not None
            await process_push(
                user,
                SyncPushRequest.model_validate(
                    {"device_id": writer, "operations": [before, operation, after]}
                ),
                session,
            )
    assert failure.value.code == "OPERATION_IN_PROGRESS"
    if raced:
        assert hidden == [True]
    async with sessions() as session:
        for model in (PlanNode, EntityRevisionSnapshot, SyncChange, SyncCursor):
            assert (await session.execute(select(model))).scalars().all() == []
        rows = (await session.execute(select(SyncOperation))).scalars().all()
        assert len(rows) == 1 and rows[0].operation_id == operation["operation_id"]
    # A transient abort must not consume IDs of earlier or later batch items.
    response = await push(test_client, token, writer, [before, after])
    assert [r["status"] for r in response.json()["results"]] == ["applied", "applied"]


async def test_operation_insert_constraint_isolated_from_following_item(
    test_client, async_session, push_context
):
    _, token, writer, _ = push_context
    blocked, later = (
        goal_operation(title="Blocked operation record"),
        goal_operation(title="After operation failure"),
    )
    await async_session.execute(
        text(
            "CREATE TRIGGER reject_operation BEFORE INSERT ON sync_operations "
            f"WHEN NEW.operation_id = '{blocked['operation_id']}' "
            "BEGIN SELECT RAISE(ABORT, 'injected operation constraint'); END"
        )
    )
    await async_session.commit()
    response = await push(test_client, token, writer, [blocked, later])
    rejected, applied = response.json()["results"]
    assert (
        rejected["error_code"] == "CONSTRAINT_VIOLATION"
        and rejected["status"] == "rejected"
    )
    assert applied["status"] == "applied"
    for model in (EntityRevisionSnapshot, SyncChange, SyncOperation):
        rows = (await async_session.execute(select(model))).scalars().all()
        assert len(rows) == 1 and rows[0].entity_uuid == later["entity_uuid"]


@pytest.mark.parametrize("failure_point", ["result", "cursor"])
async def test_final_persistence_failure_rolls_back_business_and_replay_state(
    async_session, async_engine, test_client, push_context, failure_point
):
    owner, token, writer, _ = push_context
    operation = goal_operation(title="Retry after final persistence failure")
    target = (
        "UPDATE ON sync_operations"
        if failure_point == "result"
        else "INSERT ON sync_cursors"
    )
    await async_session.execute(
        text(
            f"CREATE TRIGGER reject_final_write BEFORE {target} "
            "BEGIN SELECT RAISE(ABORT, 'injected final persistence failure'); END"
        )
    )
    await async_session.commit()
    sessions = async_sessionmaker(async_engine, expire_on_commit=False)
    with pytest.raises(IntegrityError, match="injected final persistence failure"):
        async with sessions.begin() as session:
            user = await session.get(User, owner)
            assert user is not None
            await process_push(
                user,
                SyncPushRequest.model_validate(
                    {"device_id": writer, "operations": [operation]}
                ),
                session,
            )
    async with sessions.begin() as session:
        for model in (
            PlanNode,
            EntityRevisionSnapshot,
            SyncChange,
            SyncOperation,
            SyncCursor,
        ):
            assert (await session.execute(select(model))).scalars().all() == []
        await session.execute(text("DROP TRIGGER reject_final_write"))
    first = await push(test_client, token, writer, [operation])
    assert first.status_code == 200, first.text
    assert first.json()["results"][0]["status"] == "applied"
    retry = await push(test_client, token, writer, [operation])
    assert retry.json()["results"][0]["status"] == "already_applied"


@pytest.mark.parametrize("reverted", [False, True])
async def test_secondary_can_only_finalize_task_with_an_effective_check_in(
    test_client, async_session, push_context, reverted
):
    _, token, writer, secondary = push_context
    task = activity_with_rule({"type": "once"})
    assert (await push(test_client, token, writer, [task])).json()["results"][0][
        "status"
    ] == "applied"
    event = event_operation(task["entity_uuid"])
    operations = [event]
    if reverted:
        operations.append(
            event_operation(
                task["entity_uuid"],
                event_type="revert",
                reverts_event_uuid=event["entity_uuid"],
            )
        )
    remove = deletion(task)
    operations.append(remove)
    response = await push(test_client, token, secondary, operations)
    assert response.status_code == 200, response.text
    results = response.json()["results"]
    assert all(r["status"] == "applied" for r in results[:-1])
    if reverted:
        assert (
            results[-1]["status"] == "rejected"
            and results[-1]["error_code"] == "DEVICE_CAPABILITY_DENIED"
        )
    else:
        assert results[-1]["status"] == "applied" and results[-1]["revision"] == 2
    node = (await async_session.execute(select(PlanNode))).scalar_one()
    assert (node.deleted_at is None) == reverted
    retry = await push(test_client, token, secondary, operations)
    assert retry.json()["results"] == [
        {**r, "status": "already_applied"} if r["status"] == "applied" else r
        for r in results
    ]
    if reverted:
        # Another effective check-in permits finalization; the previous denial
        # stays cached, so a corrected action needs its own operation ID.
        checked = await push(
            test_client,
            token,
            secondary,
            [event_operation(task["entity_uuid"]), remove, deletion(task)],
        )
        checked_results = checked.json()["results"]
        assert [r["status"] for r in checked_results] == [
            "applied",
            "rejected",
            "applied",
        ]
        assert checked_results[1] == results[-1]
        assert checked_results[2]["revision"] == 2


@pytest.mark.parametrize(
    "kind", ["unfinished", "recurring", "goal", "metric", "missing", "rename"]
)
async def test_secondary_finalization_exception_does_not_grant_structure_access(
    test_client, async_session, push_context, kind
):
    _, token, writer, secondary = push_context
    original = (
        metric_operation()
        if kind == "metric"
        else goal_operation()
        if kind == "goal"
        else activity_operation()
        if kind == "recurring"
        else activity_with_rule({"type": "once"})
    )
    assert (await push(test_client, token, writer, [original])).json()["results"][0][
        "status"
    ] == "applied"
    if kind in {"recurring", "rename"}:
        assert (
            await push(
                test_client,
                token,
                secondary,
                [event_operation(original["entity_uuid"])],
            )
        ).json()["results"][0]["status"] == "applied"
    candidate = deletion(original)
    if kind == "missing":
        candidate["entity_uuid"] = str(uuid4())
    elif kind == "rename":
        candidate = deepcopy(original)
        candidate.update(operation_id=str(uuid4()), base_revision=1)
        candidate["payload"]["title"] = "Unauthorized rename"
    response = await push(test_client, token, secondary, [candidate])
    denied = response.json()["results"][0]
    assert (
        denied["status"] == "rejected"
        and denied["error_code"] == "DEVICE_CAPABILITY_DENIED"
    )
