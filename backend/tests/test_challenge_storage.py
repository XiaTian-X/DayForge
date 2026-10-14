"""Real migrated storage; this is not restart API/protocol activation evidence."""

from copy import deepcopy
from datetime import UTC, date, datetime, timedelta
from uuid import uuid4

import pytest
from sqlalchemy import select, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import async_sessionmaker
from sqlmodel import col

from src.storage.logical_archive import export_archive, import_archive
from src.storage.sqlite_maintenance import (
    create_backup,
    inspect_database,
    restore_backup,
    StorageValidationError,
)
from src.v2.challenge_models import (
    ActivityChallengeRound,
    ActivityChallengeEventBinding,
)
from src.v2.challenge_round import ChallengeRestartIntent, initial_round_head
from src.v2.challenge_recovery import read_connection_challenges
from src.v2.challenge_storage import bind_created_event, restart_stored_challenge
from src.v2.models import ActivityEvent, PlanNode, TimerSession
from src.v2.errors import DomainError
from src.v2.challenge_recovery import require_roundless_view
from tests.test_next_structural_sync import snapshot
from src.v2.read_service import pull_changes
from src.auth.models import User
from tests.test_asset_declarations import setup
from tests.test_next_structural_sync import ACTIVITY, node, operation, submit
from tests.test_http_commit_boundary import database_state
from tests.test_http_commit_boundary import runtime_http as runtime_http
from tests.test_v5_production_sync import production_v5 as production_v5, push
from tests.test_timer_sync import timer_command
from tests.test_one_time_mutations import operation as fact_operation
from tests.test_logical_archive import migrate
from tests.test_logical_archive_identity import (
    read_bundle,
    replace_records,
    write_bundle,
    database_dump,
)


async def seeded(engine, *, mode="check", once=False, cycles=3, owner=1):
    factory, contexts = await setup(engine)
    body = node(mode=mode, once=once)
    if not once:
        body["activity"]["target_cycles"] = cycles
    assert (await submit(factory, contexts[owner], [operation(body)], owner))[0][
        "status"
    ] == "applied"
    return factory, contexts


async def intent_for(factory, owner=1):
    async with factory() as session:
        activity = (
            await session.execute(
                select(PlanNode).where(PlanNode.owner_user_id == owner)
            )
        ).scalar_one()
        histories = await session.run_sync(
            lambda current: read_connection_challenges(
                current.connection(), owner_user_id=owner
            )
        )
        head = (
            histories[activity.id][0]
            if activity.id in histories
            else initial_round_head(ACTIVITY)
        )
        return ChallengeRestartIntent(
            activity_uuid=ACTIVITY,
            round_uuid=str(uuid4()),
            expected_round_uuid=head.round_uuid,
            expected_generation=head.generation,
            expected_plan_revision=activity.revision,
        )


async def restart(factory, context, intent, operation_uuid, owner=1):
    async with factory.begin() as session:
        return await restart_stored_challenge(
            session, owner, context.device_id, operation_uuid, intent
        )


async def business_state(engine):
    return {
        key: rows
        for key, rows in (await database_state(engine)).items()
        if key != "client_devices"
    }


async def append_fact(factory, *, round_uuid=None, revert=None):
    async with factory.begin() as session:
        activity = (
            await session.execute(
                select(PlanNode).where(col(PlanNode.owner_user_id) == 1)
            )
        ).scalar_one()
        event = ActivityEvent(
            owner_user_id=1,
            activity_node_id=activity.id,
            event_type="revert" if revert else "check_in",
            reverts_event_id=revert,
            occurred_at=datetime(2026, 10, 8, tzinfo=UTC),
            local_date=date(2026, 10, 8),
            timezone="UTC",
        )
        session.add(event)
        await session.flush()
        await bind_created_event(session, event, round_uuid=round_uuid)
        return event.id


async def test_real_v5_birth_is_atomic_and_does_not_change_current_wire_or_replay(
    runtime_engine,
):
    factory, contexts = await seeded(runtime_engine)
    fact = fact_operation()
    fact.payload.pop("one_time")
    result = (await submit(factory, contexts[1], [fact]))[0]
    assert result["status"] == "applied" and "round_uuid" not in result["entity"]
    before = await business_state(runtime_engine)
    replay = (await submit(factory, contexts[1], [fact]))[0]
    assert replay == {**result, "status": "already_applied"}
    after = await business_state(runtime_engine)
    assert (
        after["activity_challenge_event_bindings"]
        == before["activity_challenge_event_bindings"]
    )
    assert (
        len(after["activity_challenge_rounds"])
        == len(after["activity_challenge_heads"])
        == 1
    )
    assert len(after["activity_challenge_event_bindings"]) == 1
    assert not any(row[2] == "challenge_round" for row in after["sync_changes"])


@pytest.mark.parametrize("existing_birth", [False, True])
async def test_restart_outer_commit_failure_preserves_every_row_and_same_intent_retries(
    runtime_engine, existing_birth
):
    factory, contexts = await seeded(runtime_engine)
    if existing_birth:
        await append_fact(factory)
    intent = await intent_for(factory)
    operation_uuid = str(uuid4())
    async with runtime_engine.begin() as connection:
        await connection.execute(
            text(
                "CREATE TABLE commit_fault (bad_user INTEGER REFERENCES users(id) DEFERRABLE INITIALLY DEFERRED)"
            )
        )
        await connection.execute(
            text(
                "CREATE TRIGGER inject_restart_commit_failure AFTER INSERT ON activity_challenge_rounds WHEN NEW.generation > 0 BEGIN INSERT INTO commit_fault VALUES (-999999); END"
            )
        )
    before = await database_state(runtime_engine)
    # The storage function and inner SAVEPOINT succeed. The actual outer COMMIT
    # fails: neither the head, plan, evidence, journal nor device use may survive.
    with pytest.raises(IntegrityError):
        async with factory.begin() as session:
            result = await restart_stored_challenge(
                session, 1, contexts[1].device_id, operation_uuid, intent
            )
            assert result.head.generation == 1
    assert await database_state(runtime_engine) == before
    async with runtime_engine.begin() as connection:
        await connection.execute(text("DROP TRIGGER inject_restart_commit_failure"))
    result = await restart(factory, contexts[1], intent, operation_uuid)
    assert result.head.generation == 1
    assert await restart(factory, contexts[1], intent, operation_uuid) == result
    after = await database_state(runtime_engine)
    assert after["activity_events"] == before["activity_events"]
    assert (
        after["activity_challenge_event_bindings"]
        == before["activity_challenge_event_bindings"]
    )


async def test_restart_keeps_original_facts_and_cold_replay_does_not_reset_later_round(
    runtime_engine,
):
    factory, contexts = await seeded(runtime_engine)
    old_event = await append_fact(factory)
    before = await database_state(runtime_engine)
    intent = await intent_for(factory)
    original_id = str(uuid4())
    first = await restart(factory, contexts[1], intent, original_id)
    assert first.head.generation == 1
    assert (await database_state(runtime_engine))["activity_events"] == before[
        "activity_events"
    ]
    await append_fact(factory, round_uuid=first.head.round_uuid)
    # A late fact can explicitly retain an old, proven birth; never follows head.
    late = await append_fact(factory, round_uuid=intent.expected_round_uuid)
    undo = await append_fact(factory, revert=old_event)
    next_intent = await intent_for(factory)
    second = await restart(factory, contexts[1], next_intent, str(uuid4()))
    after = await business_state(runtime_engine)
    assert await restart(factory, contexts[1], intent, original_id) == first
    assert await business_state(runtime_engine) == after
    async with factory() as session:
        histories = await session.run_sync(
            lambda current: read_connection_challenges(
                current.connection(), owner_user_id=1
            )
        )
        assert next(iter(histories.values()))[0] == second.head
        bindings = (
            (await session.execute(select(ActivityChallengeEventBinding)))
            .scalars()
            .all()
        )
        rounds = {
            row.id: row.generation
            for row in (await session.execute(select(ActivityChallengeRound))).scalars()
        }
        assert {row.event_id: rounds[row.round_id] for row in bindings} == {
            old_event: 0,
            old_event + 1: 1,
            late: 0,
            undo: 0,
        }
    reused = intent.model_copy(update={"round_uuid": str(uuid4())})
    with pytest.raises(DomainError, match="reused") as failure:
        await restart(factory, contexts[1], reused, original_id)
    assert (
        failure.value.code == "OPERATION_ID_REUSED"
        and await business_state(runtime_engine) == after
    )


async def test_two_devices_from_same_head_only_one_restart_wins(runtime_engine):
    factory, contexts = await seeded(runtime_engine)
    first = await intent_for(factory)
    second = first.model_copy(update={"round_uuid": str(uuid4())})
    from src.v2.models import ClientDevice

    async with factory.begin() as session:
        other = ClientDevice(
            user_id=1,
            installation_id="other-round-editor",
            platform="android",
            structural_edit_enabled=True,
        )
        session.add(other)
        await session.flush()
        device_uuid = other.public_id
    await restart(factory, contexts[1], first, str(uuid4()))
    before = await business_state(runtime_engine)
    async with factory.begin() as session:
        with pytest.raises(DomainError) as rejected:
            await restart_stored_challenge(
                session, 1, device_uuid, str(uuid4()), second
            )
        assert rejected.value.code == "CHALLENGE_STATE_CONFLICT"
    assert await business_state(runtime_engine) == before


@pytest.mark.parametrize(
    "case,code",
    [
        ("once", "CHALLENGE_NOT_SUPPORTED"),
        ("unbounded", "CHALLENGE_NOT_SUPPORTED"),
        ("plan-changed", "CHALLENGE_PLAN_CHANGED"),
        ("deleted", "ENTITY_DELETED"),
        ("running", "CHALLENGE_TIMER_UNFINISHED"),
        ("paused", "CHALLENGE_TIMER_UNFINISHED"),
        ("foreign-device", "DEVICE_NOT_FOUND"),
        ("capability", "DEVICE_CAPABILITY_DENIED"),
    ],
)
async def test_actual_policy_timer_plan_and_owner_checks_do_not_partially_restart(
    runtime_engine, case, code
):
    factory, contexts = await seeded(
        runtime_engine,
        once=case == "once",
        cycles=None if case == "unbounded" else 3,
        mode="duration" if case in {"running", "paused"} else "check",
    )
    intent = await intent_for(factory)
    async with factory.begin() as session:
        if case == "plan-changed":
            await session.execute(text("UPDATE plan_nodes SET revision=2"))
        elif case == "deleted":
            await session.execute(
                text("UPDATE plan_nodes SET deleted_at='2026-10-08 00:00:00'")
            )
        elif case == "capability":
            await session.execute(
                text("UPDATE user_sync_policies SET primary_editor_device_id=NULL")
            )
        elif case in {"running", "paused"}:
            activity = (await session.execute(select(PlanNode))).scalar_one()
            session.add(
                TimerSession(
                    owner_user_id=1,
                    activity_node_id=activity.id,
                    controller_device_id=1,
                    state=case,
                    started_at=datetime(2026, 10, 8, tzinfo=UTC),
                    state_changed_at=datetime(2026, 10, 8, tzinfo=UTC),
                    timezone="UTC",
                )
            )
    before = await business_state(runtime_engine)
    with pytest.raises(DomainError) as failure:
        await restart(
            factory,
            contexts[2] if case == "foreign-device" else contexts[1],
            intent,
            str(uuid4()),
        )
    assert failure.value.code == code
    assert await business_state(runtime_engine) == before


@pytest.mark.parametrize(
    "table",
    [
        "activity_challenge_rounds",
        "activity_challenge_heads",
        "sync_changes",
        "entity_revision_snapshots",
    ],
)
async def test_restart_inner_failure_rolls_back_plan_head_source_and_journal_and_exact_intent_retries(
    runtime_engine, table
):
    factory, contexts = await seeded(runtime_engine)
    intent, original_id = await intent_for(factory), str(uuid4())
    async with runtime_engine.begin() as connection:
        await connection.execute(
            text(
                f"CREATE TRIGGER round_fault BEFORE INSERT ON {table} BEGIN SELECT RAISE(ABORT, 'round fault'); END"
            )
        )
    before = await business_state(runtime_engine)
    # Catch inside the outer transaction: the store's SAVEPOINT must undo all
    # business mutations even when the caller chooses to commit its other work.
    async with factory.begin() as session:
        with pytest.raises(IntegrityError):
            await restart_stored_challenge(
                session, 1, contexts[1].device_id, original_id, intent
            )
    assert await business_state(runtime_engine) == before
    async with runtime_engine.begin() as connection:
        await connection.execute(text("DROP TRIGGER round_fault"))
    assert (
        await restart(factory, contexts[1], intent, original_id)
    ).head.generation == 1


async def test_v5_fact_binding_failure_keeps_rejection_and_never_partially_saves_fact(
    runtime_engine,
):
    factory, contexts = await seeded(runtime_engine)
    async with runtime_engine.begin() as connection:
        await connection.execute(
            text(
                "CREATE TRIGGER birth_fault BEFORE INSERT ON activity_challenge_event_bindings BEGIN SELECT RAISE(ABORT,'birth fault'); END"
            )
        )
    fact = fact_operation()
    fact.payload.pop("one_time")
    before = await business_state(runtime_engine)
    rejected = (await submit(factory, contexts[1], [fact]))[0]
    assert rejected["error_code"] == "CONSTRAINT_VIOLATION"
    after = await business_state(runtime_engine)
    for table in (
        "activity_events",
        "activity_challenge_rounds",
        "activity_challenge_heads",
        "activity_challenge_event_bindings",
        "sync_changes",
        "entity_revision_snapshots",
    ):
        assert after[table] == before[table], table
    async with runtime_engine.begin() as connection:
        await connection.execute(text("DROP TRIGGER birth_fault"))
    assert (await submit(factory, contexts[1], [fact]))[0] == rejected
    fact.operation_id = uuid4()
    assert (await submit(factory, contexts[1], [fact]))[0]["status"] == "applied"


async def test_unbound_new_write_after_restart_is_rejected_not_claimed_by_head(
    runtime_engine,
):
    factory, contexts = await seeded(runtime_engine)
    await restart(factory, contexts[1], await intent_for(factory), str(uuid4()))
    before = await business_state(runtime_engine)
    with pytest.raises(DomainError) as failure:
        await append_fact(factory)
    assert (
        failure.value.code == "CHALLENGE_ROUND_REQUIRED"
        and await business_state(runtime_engine) == before
    )


async def test_recovery_same_public_id_is_owner_scoped_and_foreign_binding_is_sql_rejected(
    runtime_engine,
):
    factory, contexts = await seeded(runtime_engine)
    body = node()
    body["activity"]["target_cycles"] = 3
    assert (await submit(factory, contexts[2], [operation(body)], 2))[0][
        "status"
    ] == "applied"
    original_id = str(uuid4())
    for owner in (1, 2):
        await restart(
            factory,
            contexts[owner],
            await intent_for(factory, owner),
            original_id,
            owner,
        )
    async with factory() as session:
        states = await session.run_sync(
            lambda current: read_connection_challenges(current.connection())
        )
        assert len(states) == 2 and all(
            head.generation == 1 for head, _ in states.values()
        )
        bases = [rows[0].head.round_uuid for _, rows in states.values()]
        assert len(set(bases)) == 1
    event_id = await append_fact(
        factory, round_uuid=next(iter(states.values()))[0].round_uuid
    )
    before = await business_state(runtime_engine)
    async with factory.begin() as session:
        foreign_round = (
            await session.execute(
                select(col(ActivityChallengeRound.id)).where(
                    col(ActivityChallengeRound.owner_user_id) == 2,
                    col(ActivityChallengeRound.generation) == 1,
                )
            )
        ).scalar_one()
        with pytest.raises(IntegrityError):
            async with session.begin_nested():
                await session.execute(
                    text(
                        "UPDATE activity_challenge_event_bindings SET round_id=:round WHERE event_id=:event"
                    ),
                    {"round": foreign_round, "event": event_id},
                )
    assert await business_state(runtime_engine) == before


@pytest.mark.parametrize("kind", ["logical", "physical"])
async def test_rounds_original_facts_and_heads_survive_actual_backup_and_restore(
    runtime_engine, tmp_path, kind
):
    factory, contexts = await seeded(runtime_engine)
    await append_fact(factory)
    first = await restart(factory, contexts[1], await intent_for(factory), str(uuid4()))
    await append_fact(factory, round_uuid=first.head.round_uuid)
    await restart(factory, contexts[1], await intent_for(factory), str(uuid4()))
    from pathlib import Path

    source = Path(runtime_engine.url.database)
    before = database_dump(source)
    assert inspect_database(source).valid
    target = tmp_path / "restored.db"
    if kind == "physical":
        backup, _ = create_backup(source, tmp_path / "backups", kind="manual")
        restore_backup(backup, target, expected_alembic_head="000000000009")
    else:
        archive = export_archive(f"sqlite:///{source}", tmp_path / "rounds.zip")
        bundle = read_bundle(archive)
        assert bundle["manifest"]["format_version"] == 4
        import_archive(migrate(target), archive)
    assert inspect_database(target).valid
    import sqlite3
    from contextlib import closing

    with closing(sqlite3.connect(target)) as connection:
        assert (
            connection.execute(
                "SELECT count(*) FROM activity_challenge_rounds"
            ).fetchone()[0]
            == 3
        )
        assert (
            connection.execute(
                "SELECT count(*) FROM activity_challenge_event_bindings"
            ).fetchone()[0]
            == 2
        )
        assert (
            connection.execute(
                "SELECT r.generation FROM activity_challenge_heads h JOIN activity_challenge_rounds r ON r.id=h.round_id"
            ).fetchone()[0]
            == 2
        )
    assert database_dump(source) == before


@pytest.mark.parametrize(
    "corruption",
    [
        "intent",
        "missing-baseline",
        "old-head",
        "wrong-birth",
        "bool-generation",
        "numeric-string",
        "old-format",
    ],
)
async def test_rechecksummed_round_corruption_is_rejected_and_import_is_atomic(
    runtime_engine, tmp_path, corruption
):
    import json

    factory, contexts = await seeded(runtime_engine)
    await append_fact(factory)
    first = await restart(factory, contexts[1], await intent_for(factory), str(uuid4()))
    await append_fact(factory, round_uuid=first.head.round_uuid)
    source = runtime_engine.url.database
    archive = export_archive(f"sqlite:///{source}", tmp_path / "original.zip")
    bundle = deepcopy(read_bundle(archive))
    table = "activity_challenge_rounds"
    records = [json.loads(line) for line in bundle["collections"][table].splitlines()]
    if corruption == "intent":
        positive = next(
            record for record in records if record["data"]["generation"] == 1
        )
        positive["data"]["restart_intent_json"] = "{}"
    elif corruption == "missing-baseline":
        records = [record for record in records if record["data"]["generation"] != 0]
    elif corruption in {"bool-generation", "numeric-string"}:
        records[0]["data"]["generation"] = (
            False if corruption == "bool-generation" else "0"
        )
    elif corruption in {"old-head", "wrong-birth"}:
        table = (
            "activity_challenge_heads"
            if corruption == "old-head"
            else "activity_challenge_event_bindings"
        )
        changed = [
            json.loads(line) for line in bundle["collections"][table].splitlines()
        ]
        changed[-1]["data"]["round_id"]["key"] = (
            next(
                record["key"] for record in records if record["data"]["generation"] == 0
            )
            if corruption == "old-head"
            else "owner:missing:round"
        )
        replace_records(bundle, table, changed)
    else:
        bundle["manifest"]["format_version"] = 3
    if table == "activity_challenge_rounds":
        replace_records(bundle, table, records)
    damaged = write_bundle(tmp_path / "damaged.zip", bundle)
    target = tmp_path / "target.db"
    url = migrate(target)
    before = database_dump(target)
    with pytest.raises(StorageValidationError):
        import_archive(url, damaged)
    assert database_dump(target) == before


async def test_actual_read_only_inspection_blocks_missing_head_and_unsafe_backup(
    runtime_engine, tmp_path
):
    factory, contexts = await seeded(runtime_engine)
    await restart(factory, contexts[1], await intent_for(factory), str(uuid4()))
    async with runtime_engine.begin() as connection:
        await connection.execute(text("DELETE FROM activity_challenge_heads"))
    from pathlib import Path

    source = Path(runtime_engine.url.database)
    inspection = inspect_database(source)
    assert not inspection.valid and any(
        "CHALLENGE_HISTORY_INVALID" in error for error in inspection.domain_errors
    )
    with pytest.raises(StorageValidationError):
        create_backup(source, tmp_path / "backups", kind="manual")


@pytest.mark.parametrize("countdown", [False, True])
async def test_actual_timer_http_freezes_birth_rejects_running_or_paused_restart_and_inherits_at_stop(
    production_v5, countdown
):
    client, engine, headers, device = production_v5
    factory = async_sessionmaker(engine, expire_on_commit=False)
    body = node(mode="duration", countdown=countdown)
    body["activity"]["target_cycles"] = 3
    assert (await push(client, headers, device, [operation(body)]))[0][
        "status"
    ] == "applied"
    started = datetime(2026, 10, 7, 15, 59, 30, tzinfo=UTC)
    session_uuid = str(uuid4())
    commands = [timer_command("start", session_uuid, 1, started, activity_id=ACTIVITY)]
    commands[0]["start_policy"] = dict(
        target_seconds=60,
        is_countdown=countdown,
        max_duration_seconds=60 if countdown else 180,
    )
    for kind, sequence, seconds, revision in (
        ("pause", 2, 20, 1),
        ("resume", 3, 40, 2),
        ("stop", 4, 85, 3),
    ):
        commands.append(
            timer_command(
                kind,
                session_uuid,
                sequence,
                started + timedelta(seconds=seconds),
                revision=revision,
            )
        )
    for index, command in enumerate(commands):
        response = await client.post(
            "/api/v2/timers/commands",
            headers=headers,
            json=dict(device_id=device, commands=[command]),
        )
        assert (
            response.status_code == 200
            and response.json()["results"][0]["status"] == "applied"
        ), response.text
        if index < 2:
            intent = await intent_for(factory)
            async with factory.begin() as session:
                with pytest.raises(DomainError) as blocked:
                    await restart_stored_challenge(
                        session, 1, device, str(uuid4()), intent
                    )
                assert blocked.value.code == "CHALLENGE_TIMER_UNFINISHED"
    # Independent SQL reads after HTTP prove the committed bindings and exact
    # cross-midnight split. Paused time never contributes to either day.
    async with engine.connect() as connection:
        bound = (
            await connection.execute(
                text(
                    "SELECT t.round_id,e.round_id,r.generation FROM activity_challenge_timer_bindings t JOIN timer_sessions s ON s.id=t.session_id JOIN activity_challenge_event_bindings e ON e.event_id=s.completed_event_id JOIN activity_challenge_rounds r ON r.id=t.round_id"
                )
            )
        ).one()
        assert bound[0] == bound[1] and bound[2] == 0
        allocations = (
            (
                await connection.execute(
                    text(
                        "SELECT duration_ms FROM duration_day_allocations ORDER BY local_date"
                    )
                )
            )
            .scalars()
            .all()
        )
        assert allocations == [20_000, 40_000 if countdown else 45_000]
    stop_result = response.json()["results"][0]
    intent = await intent_for(factory)
    async with factory.begin() as session:
        after = await restart_stored_challenge(session, 1, device, str(uuid4()), intent)
    assert after.head.generation == 1
    before = await business_state(engine)
    replay = await client.post(
        "/api/v2/timers/commands",
        headers=headers,
        json=dict(device_id=device, commands=[commands[-1]]),
    )
    assert replay.status_code == 200 and replay.json()["results"][0] == {
        **stop_result,
        "status": "already_applied",
    }
    replayed = await business_state(engine)
    # Successful API-token authentication records use, even for an idempotent
    # receipt. Every other token column and all business rows remain unchanged.
    for old, new in zip(before["api_tokens"], replayed["api_tokens"], strict=True):
        old_token, new_token = dict(old._mapping), dict(new._mapping)
        old_used = old_token.pop("last_used_at")
        new_used = new_token.pop("last_used_at")
        assert old_token == new_token
        if headers["Authorization"].startswith("Token "):
            assert old_used is not None and new_used >= old_used
        else:
            assert new_used == old_used
    assert {key: value for key, value in replayed.items() if key != "api_tokens"} == {
        key: value for key, value in before.items() if key != "api_tokens"
    }


async def test_current_readers_and_new_writes_refuse_rounds_without_consuming_original_receipts(
    runtime_engine,
):
    factory, contexts = await seeded(runtime_engine)
    fact = fact_operation()
    fact.payload.pop("one_time")
    original = (await submit(factory, contexts[1], [fact]))[0]
    await restart(factory, contexts[1], await intent_for(factory), str(uuid4()))
    assert (await submit(factory, contexts[1], [fact]))[0] == {
        **original,
        "status": "already_applied",
    }
    before = await business_state(runtime_engine)
    with pytest.raises(DomainError) as failure:
        await snapshot(factory, contexts[1])
    assert (
        failure.value.code == "CLIENT_UPGRADE_REQUIRED"
        and await business_state(runtime_engine) == before
    )
    async with factory.begin() as session:
        user = await session.get(User, 1)
        assert user is not None
        with pytest.raises(DomainError) as rejected:
            await pull_changes(
                user, contexts[1].device_id, 0, 100, session, next_protocol=True
            )
        assert rejected.value.code == "CLIENT_UPGRADE_REQUIRED"
    fresh = fact_operation()
    fresh.payload.pop("one_time")
    fresh.operation_id = uuid4()
    fresh.entity_uuid = uuid4()
    result = (await submit(factory, contexts[1], [fresh]))[0]
    assert result["error_code"] == "CLIENT_UPGRADE_REQUIRED"
    after = await business_state(runtime_engine)
    for name in (
        "activity_events",
        "activity_challenge_event_bindings",
        "activity_challenge_rounds",
        "activity_challenge_heads",
    ):
        assert after[name] == before[name]
    # Another account with the same public activity UUID is still independent.
    assert (await submit(factory, contexts[2], [operation(node())], 2))[0][
        "status"
    ] == "applied"


async def test_roundless_gate_never_flushes_caller_work(runtime_engine):
    factory, contexts = await seeded(runtime_engine)
    async with factory() as session:
        activity = (await session.execute(select(PlanNode))).scalar_one()
        activity.title = "must-not-flush"
        await require_roundless_view(session, 1)
        async with runtime_engine.connect() as connection:
            assert (
                await connection.execute(text("SELECT title FROM plan_nodes"))
            ).scalar_one() == "activity"
        assert activity in session.dirty
        await session.rollback()
