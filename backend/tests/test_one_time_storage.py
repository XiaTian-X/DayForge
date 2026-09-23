"""Real migrated storage primitives; not yet a v5 HTTP/service integration test."""

import asyncio
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select, update
from sqlalchemy.exc import OperationalError
from sqlalchemy.ext.asyncio import async_sessionmaker
from sqlmodel import col

from src.auth.models import User
from src.storage.database_adapter import is_sqlite_busy
from src.v2.errors import DomainError
from src.v2.models import ActivityDetail, ActivityEvent, PlanNode
from src.v2.one_time import OneTimeIntent
from src.v2.one_time_storage import (
    OneTimeStateConflict,
    advance_stored_one_time,
    capture_one_time_intent,
    load_one_time_activity,
    stored_event_proof,
)
from tests.account_fixtures import account_password_hash

ACTIVITY = "90000000-0000-4000-8000-000000000001"
EVENTS = [f"91000000-0000-4000-8000-{index:012d}" for index in range(1, 5)]


async def seed(engine):
    factory = async_sessionmaker(engine, expire_on_commit=False)
    async with factory.begin() as session:
        for user_id in (1, 2):
            session.add(
                User(
                    id=user_id,
                    username=f"one-time-{user_id}",
                    password_hash=account_password_hash(),
                )
            )
        await session.flush()
        for user_id in (1, 2):
            session.add(
                PlanNode(
                    id=user_id,
                    public_id=ACTIVITY,
                    owner_user_id=user_id,
                    created_by_user_id=user_id,
                    node_kind="activity",
                    title="One-time",
                )
            )
        await session.flush()
        for node_id in (1, 2):
            session.add(
                ActivityDetail(
                    node_id=node_id,
                    tracking_mode="check",
                    completion_policy="one_and_done",
                    recurrence_rule_json='{"schema_version":1,"type":"once","due_date":null}',
                    failure_policy_json='{"schema_version":1,"type":"loose"}',
                    one_time_version=0,
                )
            )
    return factory


def intent(index, *, event_uuid=None):
    return OneTimeIntent(
        event_uuid=event_uuid or EVENTS[index],
        action="undo" if index % 2 else "complete",
        expected_version=index,
        expected_head_event_uuid=EVENTS[index - 1] if index else None,
        reverts_event_uuid=EVENTS[index - 1] if index % 2 else None,
    )


async def append_in_test_transaction(session, owner, requested):
    # Exercise the storage API's caller-owned boundary. Production service, journal
    # and operation replay must be tested separately when the mutation path is wired.
    current = await load_one_time_activity(session, owner, ACTIVITY)
    revert = None
    if requested.reverts_event_uuid:
        revert = (
            await session.execute(
                select(ActivityEvent).where(
                    col(ActivityEvent.owner_user_id) == owner,
                    col(ActivityEvent.public_id) == requested.reverts_event_uuid,
                )
            )
        ).scalar_one()
    occurred = datetime(2026, 9, 23, 23, 59, tzinfo=UTC) + timedelta(
        days=requested.expected_version
    )
    event = ActivityEvent(
        public_id=requested.event_uuid,
        owner_user_id=owner,
        activity_node_id=current.node_id,
        event_type="revert" if revert else "check_in",
        reverts_event_id=revert.id if revert else None,
        occurred_at=occurred,
        local_date=occurred.date(),
        timezone="UTC",
    )
    capture_one_time_intent(event, requested, requested.reverts_event_uuid)
    after = await advance_stored_one_time(session, owner, ACTIVITY, requested)
    session.add(event)
    await session.flush()
    return after, event


async def test_complete_cross_day_undo_complete_reopens_and_preserves_each_proof(
    runtime_engine,
):
    factory = await seed(runtime_engine)
    for index in range(3):
        async with factory.begin() as session:
            after, event = await append_in_test_transaction(session, 1, intent(index))
            assert after.state.version == index + 1
            assert event.id is not None
    async with factory() as session:
        first = await load_one_time_activity(session, 1, ACTIVITY)
        second = await load_one_time_activity(session, 2, ACTIVITY)
        assert first.projection.state.version == 3
        assert first.projection.state.completion_event_uuid == EVENTS[2]
        assert second.projection.state.version == 0
        events = (
            (
                await session.execute(
                    select(ActivityEvent)
                    .where(col(ActivityEvent.owner_user_id) == 1)
                    .order_by(col(ActivityEvent.id))
                )
            )
            .scalars()
            .all()
        )
        assert [event.local_date.isoformat() for event in events] == [
            "2026-09-23",
            "2026-09-24",
            "2026-09-25",
        ]
        for index, event in enumerate(events):
            proof = stored_event_proof(
                event, ACTIVITY, EVENTS[0] if index == 1 else None
            )
            assert proof is not None
            assert proof.one_time_state_after.version == index + 1
            assert proof.one_time_state_after.head_event_uuid == EVENTS[index]
        # An identity already in the ORM map must also reflect a SQL CAS update.
        detail = await session.get(ActivityDetail, 1)
        assert detail is not None and detail.one_time_version == 3
        await append_in_test_transaction(session, 1, intent(3))
        assert detail.one_time_version == 4
        await session.rollback()
    async with factory() as session:
        assert (
            await load_one_time_activity(session, 1, ACTIVITY)
        ).projection.state.version == 3


async def test_projection_and_fact_rollback_together_after_later_failure(
    runtime_engine,
):
    factory = await seed(runtime_engine)
    with pytest.raises(RuntimeError, match="later write failure"):
        async with factory.begin() as session:
            await append_in_test_transaction(session, 1, intent(0))
            raise RuntimeError("later write failure")
    async with factory() as session:
        assert (
            await load_one_time_activity(session, 1, ACTIVITY)
        ).projection.state.version == 0
        assert (await session.execute(select(ActivityEvent))).scalars().all() == []
    async with factory.begin() as session:
        await append_in_test_transaction(session, 1, intent(0))


@pytest.mark.parametrize(
    "case,code",
    [
        ("unknown", "ACTIVITY_NOT_FOUND"),
        ("deleted", "ENTITY_DELETED"),
        ("recurring", "INVALID_PAYLOAD"),
        ("legacy", "TASK_STATE_UNINITIALIZED"),
    ],
)
async def test_ineligible_activity_never_returns_a_state_or_changes_it(
    runtime_engine, case, code
):
    factory = await seed(runtime_engine)
    async with factory.begin() as session:
        if case == "deleted":
            await session.execute(
                update(PlanNode)
                .where(col(PlanNode.id) == 1)
                .values(deleted_at=datetime.now(UTC))
            )
        elif case in {"recurring", "legacy"}:
            values: dict[str, str | None] = {"one_time_version": None}
            if case == "recurring":
                values["completion_policy"] = "recurring"
            await session.execute(
                update(ActivityDetail)
                .where(col(ActivityDetail.node_id) == 1)
                .values(**values)
            )
    async with factory.begin() as session:
        with pytest.raises(DomainError) as error:
            await advance_stored_one_time(
                session, 3 if case == "unknown" else 1, ACTIVITY, intent(0)
            )
        assert error.value.code == code and not isinstance(
            error.value, OneTimeStateConflict
        )
        assert error.value.entity is None
        assert (
            await load_one_time_activity(session, 2, ACTIVITY)
        ).projection.state.version == 0


async def test_same_version_wrong_head_is_rejected_with_scoped_state(runtime_engine):
    factory = await seed(runtime_engine)
    async with factory.begin() as session:
        await append_in_test_transaction(session, 1, intent(0))
    wrong = intent(1).model_copy(update={"expected_head_event_uuid": EVENTS[3]})
    async with factory() as session:
        with pytest.raises(OneTimeStateConflict) as error:
            await advance_stored_one_time(session, 1, ACTIVITY, wrong)
        assert error.value.code == "TASK_STATE_CONFLICT"
        assert error.value.projection.state.completion_event_uuid == EVENTS[0]


async def test_two_real_snapshot_writers_cannot_both_complete(
    runtime_engine, monkeypatch
):
    from src.v2 import one_time_storage

    factory = await seed(runtime_engine)
    original = one_time_storage.load_one_time_activity
    barrier = asyncio.Event()
    arrivals = 0

    async def simultaneous_read(session, user_id, activity_uuid):
        nonlocal arrivals
        loaded = await original(session, user_id, activity_uuid)
        arrivals += 1
        if arrivals == 2:
            barrier.set()
        await asyncio.wait_for(barrier.wait(), timeout=5)
        return loaded

    monkeypatch.setattr(one_time_storage, "load_one_time_activity", simultaneous_read)

    async def write(event_uuid):
        async with factory.begin() as session:
            return await advance_stored_one_time(
                session, 1, ACTIVITY, intent(0, event_uuid=event_uuid)
            )

    results = await asyncio.gather(
        write(EVENTS[0]), write(EVENTS[1]), return_exceptions=True
    )
    failures = [value for value in results if isinstance(value, BaseException)]
    assert len(failures) == 1
    assert isinstance(failures[0], OperationalError) and is_sqlite_busy(failures[0])
    async with factory() as session:
        assert (await original(session, 1, ACTIVITY)).projection.state.version == 1


async def test_database_cas_rejects_a_stale_projection_even_if_initial_read_is_stale(
    runtime_engine, monkeypatch
):
    from src.v2 import one_time_storage

    factory = await seed(runtime_engine)
    async with factory() as session:
        stale = await load_one_time_activity(session, 1, ACTIVITY)
    async with factory.begin() as session:
        await append_in_test_transaction(session, 1, intent(0))
    original = one_time_storage.load_one_time_activity
    reads = 0

    async def stale_first(session, user_id, activity_uuid):
        nonlocal reads
        reads += 1
        return stale if reads == 1 else await original(session, user_id, activity_uuid)

    monkeypatch.setattr(one_time_storage, "load_one_time_activity", stale_first)
    async with factory() as session:
        with pytest.raises(OneTimeStateConflict) as error:
            await advance_stored_one_time(
                session, 1, ACTIVITY, intent(0, event_uuid=EVENTS[1])
            )
        assert reads == 2
        assert error.value.projection.state.head_event_uuid == EVENTS[0]


async def test_logical_restore_keeps_account_scoped_heads_and_immutable_proofs(
    runtime_engine, tmp_path
):
    from src.storage.database_adapter import build_database_adapter
    from src.storage.logical_archive import export_archive, import_archive
    from tests.test_logical_archive import migrate

    factory = await seed(runtime_engine)
    async with factory.begin() as session:
        await append_in_test_transaction(session, 1, intent(0))
        await append_in_test_transaction(session, 1, intent(1))
        # Same activity and event UUIDs are valid in another account.
        await append_in_test_transaction(session, 2, intent(0))
    source_url = str(runtime_engine.url.set(drivername="sqlite"))
    archive = export_archive(source_url, tmp_path / "one-time.zip")
    restored = tmp_path / "restored.sqlite"
    import_archive(migrate(restored), archive)
    restored_engine = build_database_adapter(
        "sqlite", None, str(restored)
    ).create_async_engine()
    try:
        restored_factory = async_sessionmaker(restored_engine)
        async with restored_factory() as session:
            for name, version, completion in (
                ("one-time-1", 2, None),
                ("one-time-2", 1, EVENTS[0]),
            ):
                owner = (
                    await session.execute(
                        select(User).where(col(User.username) == name)
                    )
                ).scalar_one()
                assert owner.id is not None
                current = await load_one_time_activity(session, owner.id, ACTIVITY)
                assert current.projection.state.version == version
                assert current.projection.state.completion_event_uuid == completion
                records = (
                    (
                        await session.execute(
                            select(ActivityEvent)
                            .where(col(ActivityEvent.owner_user_id) == owner.id)
                            .order_by(col(ActivityEvent.one_time_expected_version))
                        )
                    )
                    .scalars()
                    .all()
                )
                assert len(records) == version
                for index, record in enumerate(records):
                    proof = stored_event_proof(
                        record, ACTIVITY, EVENTS[0] if index else None
                    )
                    assert (
                        proof is not None
                        and proof.one_time_state_after.version == index + 1
                    )
                    assert proof.one_time_state_after.head_event_uuid == EVENTS[index]
    finally:
        await restored_engine.dispose()


async def test_historical_event_identity_cannot_be_reused_for_a_later_completion(
    runtime_engine,
):
    factory = await seed(runtime_engine)
    async with factory.begin() as session:
        await append_in_test_transaction(session, 1, intent(0))
        await append_in_test_transaction(session, 1, intent(1))
    async with factory.begin() as session:
        with pytest.raises(DomainError) as error:
            await advance_stored_one_time(
                session, 1, ACTIVITY, intent(2, event_uuid=EVENTS[0])
            )
        assert error.value.code == "ENTITY_ALREADY_EXISTS"
        assert (
            await load_one_time_activity(session, 1, ACTIVITY)
        ).projection.state.version == 2
