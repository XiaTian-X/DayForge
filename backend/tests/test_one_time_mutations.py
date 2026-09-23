"""Production event mutation and journal, still without v5 HTTP activation/replay."""

import json
from uuid import UUID

import pytest
from sqlalchemy import select, text, update
from sqlalchemy.exc import IntegrityError
from sqlmodel import col

from src.v2.errors import DomainError
from src.v2.event_mutations import mutate_activity_event
from src.v2.models import (
    ActivityDetail,
    ActivityEvent,
    ClientDevice,
    EntityRevisionSnapshot,
    PlanNode,
    SyncChange,
)
from src.v2.one_time_storage import OneTimeStateConflict, load_one_time_activity
from src.v2.schemas import SyncOperationRequest
from tests.test_one_time_storage import ACTIVITY, EVENTS, intent, seed


async def setup_database(engine):
    factory = await seed(engine)
    async with factory.begin() as session:
        for user_id in (1, 2):
            session.add(
                ClientDevice(
                    id=user_id,
                    user_id=user_id,
                    public_id=f"93000000-0000-4000-8000-{user_id:012d}",
                    installation_id=f"one-time-device-{user_id}",
                    platform="android",
                    device_class="interactive",
                )
            )
    return factory


def operation(index=0):
    requested = intent(index)
    day = ["2026-09-23", "2026-09-24", "2026-09-25"][index]
    return SyncOperationRequest.model_validate(
        {
            "operation_id": f"92000000-0000-4000-8000-{index + 1:012d}",
            "entity_type": "activity_event",
            "entity_uuid": EVENTS[index],
            "action": "upsert",
            "payload": {
                "activity_uuid": ACTIVITY,
                "event_type": "revert" if index == 1 else "check_in",
                "occurred_at": f"{day}T23:59:00.123456Z",
                "local_date": day,
                "timezone": "UTC",
                "one_time": requested.model_dump(mode="json"),
                "reverts_event_uuid": requested.reverts_event_uuid,
            },
        }
    )


async def mutate(session, index=0, *, request=None, owner=1, next_contract=True):
    device = await session.get(ClientDevice, owner)
    assert device is not None
    return await mutate_activity_event(
        session,
        owner,
        device,
        request or operation(index),
        one_time_contract=next_contract,
    )


async def test_real_mutation_writes_fact_projection_and_immutable_journal(
    runtime_engine,
):
    factory = await setup_database(runtime_engine)
    results = []
    for index in range(3):
        async with factory.begin() as session:
            revision, entity = await mutate(session, index)
            assert revision == 1
            assert entity["one_time_state_after"]["version"] == index + 1
            results.append(entity)
    async with factory() as session:
        projection = (await load_one_time_activity(session, 1, ACTIVITY)).projection
        assert (
            projection.state.version == 3
            and projection.state.completion_event_uuid == EVENTS[2]
        )
        changes = (
            (
                await session.execute(
                    select(SyncChange).order_by(col(SyncChange.sequence))
                )
            )
            .scalars()
            .all()
        )
        snapshots = (
            (
                await session.execute(
                    select(EntityRevisionSnapshot).order_by(
                        col(EntityRevisionSnapshot.id)
                    )
                )
            )
            .scalars()
            .all()
        )
        assert [json.loads(change.payload_json) for change in changes] == results
        assert [json.loads(snapshot.payload_json) for snapshot in snapshots] == results
        assert len(changes) == len(snapshots) == 3
        node = await session.get(PlanNode, 1)
        assert node is not None and node.revision == 1 and node.deleted_at is None
        facts = (
            (
                await session.execute(
                    select(ActivityEvent).order_by(col(ActivityEvent.id))
                )
            )
            .scalars()
            .all()
        )
        assert [fact.one_time_expected_version for fact in facts] == [0, 1, 2]
        assert [fact.occurred_at.microsecond for fact in facts] == [123456] * 3


@pytest.mark.parametrize(
    "table", ["activity_events", "sync_changes", "entity_revision_snapshots"]
)
async def test_failure_at_each_durable_write_rolls_back_state_fact_and_journal(
    runtime_engine, table
):
    factory = await setup_database(runtime_engine)
    async with runtime_engine.begin() as connection:
        await connection.execute(
            text(
                f"CREATE TRIGGER fail_task_write BEFORE INSERT ON {table} BEGIN SELECT RAISE(ABORT, 'injected one-time write'); END"
            )
        )
    expected = DomainError if table == "activity_events" else IntegrityError
    with pytest.raises(expected) as error:
        async with factory.begin() as session:
            await mutate(session)
    if isinstance(error.value, DomainError):
        assert error.value.code == "CONSTRAINT_VIOLATION"
    else:
        assert isinstance(error.value, IntegrityError)
        assert "injected one-time write" in str(error.value.orig)
    async with factory() as session:
        assert (
            await load_one_time_activity(session, 1, ACTIVITY)
        ).projection.state.version == 0
        for model in (ActivityEvent, SyncChange, EntityRevisionSnapshot):
            assert (await session.execute(select(model))).scalars().all() == []
    async with runtime_engine.begin() as connection:
        await connection.execute(text("DROP TRIGGER fail_task_write"))
    async with factory.begin() as session:
        revision, entity = await mutate(session)
        assert revision == 1 and entity["one_time_state_after"]["version"] == 1


@pytest.mark.parametrize(
    "case", ["missing-intent", "recurring", "old-writer", "source-device"]
)
async def test_invalid_entry_paths_never_modify_projection_or_facts(
    runtime_engine, case
):
    factory = await setup_database(runtime_engine)
    request = operation()
    if case in {"missing-intent", "old-writer"}:
        request.payload.pop("one_time")
    if case == "source-device":
        request.payload["source_device_id"] = "93000000-0000-4000-8000-000000000002"
    async with factory.begin() as session:
        if case == "recurring":
            await session.execute(
                update(ActivityDetail)
                .where(col(ActivityDetail.node_id) == 1)
                .values(
                    completion_policy="recurring",
                    one_time_version=None,
                    recurrence_rule_json='{"schema_version":1,"type":"daily","interval":1}',
                )
            )
    async with factory.begin() as session:
        with pytest.raises(DomainError) as error:
            await mutate(session, request=request, next_contract=case != "old-writer")
        assert (
            error.value.code
            == {
                "missing-intent": "INVALID_PAYLOAD",
                "recurring": "INVALID_PAYLOAD",
                "old-writer": "CLIENT_UPGRADE_REQUIRED",
                "source-device": "SOURCE_DEVICE_MISMATCH",
            }[case]
        )
        assert (await session.execute(select(ActivityEvent))).scalars().all() == []
        detail = await session.get(ActivityDetail, 1)
        assert detail is not None and detail.one_time_version == (
            None if case == "recurring" else 0
        )


async def test_stale_undo_reports_task_context_before_generic_duplicate_revert(
    runtime_engine,
):
    factory = await setup_database(runtime_engine)
    async with factory.begin() as session:
        await mutate(session, 0)
        await mutate(session, 1)
    request = operation(1)
    request.entity_uuid = UUID(EVENTS[3])
    request.payload["one_time"]["event_uuid"] = EVENTS[3]
    async with factory.begin() as session:
        with pytest.raises(OneTimeStateConflict) as error:
            await mutate(session, request=request)
        assert error.value.code == "TASK_STATE_CONFLICT"
        assert error.value.projection.state.version == 2


async def test_v5_recurring_facts_keep_the_existing_shape(runtime_engine):
    factory = await setup_database(runtime_engine)
    request = operation()
    request.payload.pop("one_time")
    async with factory.begin() as session:
        await session.execute(
            update(ActivityDetail)
            .where(col(ActivityDetail.node_id) == 1)
            .values(
                completion_policy="recurring",
                one_time_version=None,
                recurrence_rule_json='{"schema_version":1,"type":"daily","interval":1}',
            )
        )
        revision, entity = await mutate(session, request=request)
        assert revision == 1
        assert "one_time" not in entity and "one_time_state_after" not in entity


async def test_initialized_items_cannot_use_the_legacy_fact_device_delete_exception(
    runtime_engine,
):
    from src.v2.service import _is_fact_derived_one_time_delete

    factory = await setup_database(runtime_engine)
    async with factory.begin() as session:
        await mutate(session)
        deletion = SyncOperationRequest.model_validate(
            {
                "operation_id": "92000000-0000-4000-8000-000000000004",
                "entity_type": "plan_node",
                "entity_uuid": ACTIVITY,
                "action": "delete",
                "base_revision": 1,
            }
        )
        assert not await _is_fact_derived_one_time_delete(session, 1, deletion)
