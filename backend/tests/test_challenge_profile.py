"""Actual JWT/API HTTP, shared receipts, durable births and final COMMIT."""

from copy import deepcopy
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4
import json

import pytest
from sqlalchemy import text

from src.storage.logical_archive import export_archive, import_archive
from src.storage.sqlite_maintenance import (
    create_backup,
    inspect_database,
    restore_backup,
    StorageValidationError,
)
from src.v2.challenge_round import initial_round_head
from src.v2.challenge_sync_contract import (
    RoundSyncBootstrapResponse,
    RoundSyncPullResponse,
    RoundSyncPushResponse,
    RoundTimerCommandBatchResponse,
)
from src.v2.replica_context import EPOCH_HEADER, INSTANCE_HEADER, PROTOCOL_HEADER
from tests.test_http_commit_boundary import database_state, runtime_http as runtime_http
from tests.test_v5_production_sync import (
    production_v5 as production_v5,
    push as legacy_push,
)
from tests.test_next_structural_sync import node, operation, metric, goal
from tests.test_timer_sync import timer_command
from tests.test_logical_archive import migrate
from tests.test_logical_archive_identity import (
    read_bundle,
    replace_records,
    write_bundle,
    database_dump,
)

PUSH = "/api/v2/sync/rounds/push"
BOOT = "/api/v2/sync/rounds/bootstrap"
PULL = "/api/v2/sync/rounds/changes"
COMMANDS = "/api/v2/timers/rounds/commands"
ACTIVE = "/api/v2/timers/rounds/active"


def scope(source, head=None, *, legacy=False):
    return dict(source_uuid=source, head=head, legacy_initial=legacy)


def batch(device, operations, heads=None, *, legacy=False):
    return dict(
        challenge_contract=1,
        device_id=device,
        operations=operations,
        contexts=[
            scope(
                item["operation_id"],
                (heads or {}).get(item["operation_id"]),
                legacy=legacy,
            )
            for item in operations
        ],
    )


async def call_push(client, headers, body):
    response = await client.post(PUSH, headers=headers, json=body)
    assert response.status_code == 200, response.text
    RoundSyncPushResponse.model_validate(response.json())
    return response.json()


async def seed(production_v5, mode="check", countdown=False):
    client, engine, headers, device = production_v5
    activity = str(uuid4())
    plan = node(mode=mode, countdown=countdown)
    plan["title"] = f"{mode}-{activity}"
    plan["activity"]["target_cycles"] = 3
    original = operation(plan, identity=activity)
    head = initial_round_head(activity).model_dump(mode="json")
    response = await call_push(
        client, headers, batch(device, [original], {original["operation_id"]: head})
    )
    assert response["results"][0]["status"] == "applied", response
    return activity, plan, head


def restart(activity, head, revision):
    identity = str(uuid4())
    return dict(
        operation_id=str(uuid4()),
        entity_type="challenge_round",
        entity_uuid=identity,
        action="upsert",
        base_revision=None,
        payload=dict(
            activity_uuid=activity,
            round_uuid=identity,
            expected_round_uuid=head["round_uuid"],
            expected_generation=head["generation"],
            expected_plan_revision=revision,
        ),
    )


def fact(activity, *, mode="check_in", value=None, countdown=False, revert=None):
    payload = dict(
        activity_uuid=activity,
        event_type=mode,
        occurred_at="2026-10-08T01:00:00.123456Z",
        local_date="2026-10-08",
        timezone="UTC",
    )
    if value is not None:
        payload.update(
            value=value, count_policy=dict(target_value=1, is_countdown=countdown)
        )
    if revert is not None:
        payload["reverts_event_uuid"] = revert
    return operation(payload, identity=str(uuid4()), kind="activity_event")


async def snapshot(client, headers, device):
    response = await client.get(
        BOOT, headers=headers, params=dict(device_id=device, challenge_contract="1")
    )
    assert response.status_code == 200, response.text
    RoundSyncBootstrapResponse.model_validate(response.json())
    return response.json()


def timer_batch(device, commands, head):
    return dict(
        challenge_contract=1,
        device_id=device,
        commands=commands,
        contexts=[scope(item["command_id"], head) for item in commands],
    )


async def test_restart_http_preserves_facts_original_receipt_and_cold_recovery(
    production_v5,
):
    client, engine, headers, device = production_v5
    activity, _, initial = await seed(production_v5)
    old = fact(activity)
    body = batch(device, [old], {old["operation_id"]: initial})
    accepted = await call_push(client, headers, body)
    first_restart = restart(activity, initial, 1)
    original_restart = batch(device, [first_restart])
    first = await call_push(client, headers, original_restart)
    current = first["results"][0]["entity"]["head"]
    assert (
        current["generation"] == 1
        and current["round_uuid"] == first_restart["entity_uuid"]
    )
    assert first["results"][0]["entity"]["source_device_uuid"] == device
    second_restart = restart(activity, current, 2)
    second = await call_push(client, headers, batch(device, [second_restart]))
    latest = second["results"][0]["entity"]["head"]
    replay = await call_push(client, headers, original_restart)
    assert replay["results"] == [{**first["results"][0], "status": "already_applied"}]
    assert replay["checkpoints"][0]["head"] == latest
    old_replay = await call_push(client, headers, body)
    assert old_replay["results"] == [
        {**accepted["results"][0], "status": "already_applied"}
    ]
    assert old_replay["births"][0]["head"] == initial
    state = await snapshot(client, headers, device)
    assert state["checkpoints"][0]["head"] == latest
    assert len(state["checkpoints"][0]["records"]) == 3
    assert state["births"] == accepted["births"]
    assert (
        next(item for item in state["changes"] if item["entity_uuid"] == activity)[
            "payload"
        ]["revision"]
        == 3
    )
    page = await client.get(
        PULL,
        headers=headers,
        params=dict(device_id=device, challenge_contract="1", limit=1),
    )
    assert page.status_code == 200, page.text
    RoundSyncPullResponse.model_validate(page.json())
    assert page.json()["checkpoints"][0]["head"] == latest
    full = await client.get(
        PULL,
        headers=headers,
        params=dict(device_id=device, challenge_contract="1", limit=1000),
    )
    assert full.status_code == 200, full.text
    RoundSyncPullResponse.model_validate(full.json())
    assert (
        len(
            [
                change
                for change in full.json()["changes"]
                if change["entity_type"] == "challenge_round"
            ]
        )
        == 3
    )
    async with engine.connect() as connection:
        assert (
            await connection.execute(text("SELECT COUNT(*) FROM activity_events"))
        ).scalar_one() == 1
        assert (
            await connection.execute(
                text(
                    "SELECT COUNT(*) FROM sync_operations WHERE entity_type='challenge_round'"
                )
            )
        ).scalar_one() == 2


@pytest.mark.parametrize(
    "mode,countdown", [("check", False), ("count", False), ("count", True)]
)
async def test_offline_old_birth_new_round_fact_undo_and_day_policy(
    production_v5, mode, countdown
):
    client, engine, headers, device = production_v5
    activity, _, initial = await seed(production_v5, mode, countdown)
    original = fact(
        activity,
        mode="check_in" if mode == "check" else "count_delta",
        value=None if mode == "check" else "2",
        countdown=countdown,
    )
    response = await call_push(
        client, headers, batch(device, [restart(activity, initial, 1)])
    )
    head = response["results"][0]["entity"]["head"]
    late = await call_push(
        client, headers, batch(device, [original], {original["operation_id"]: initial})
    )
    assert (
        late["results"][0]["status"] == "applied"
        and late["births"][0]["head"] == initial
    )
    fresh = deepcopy(original)
    fresh.update(operation_id=str(uuid4()), entity_uuid=str(uuid4()))
    new = await call_push(
        client, headers, batch(device, [fresh], {fresh["operation_id"]: head})
    )
    assert new["results"][0]["status"] == "applied" and new["births"][0]["head"] == head
    undo = fact(activity, mode="revert", revert=original["entity_uuid"])
    invalid = await call_push(
        client, headers, batch(device, [undo], {undo["operation_id"]: head})
    )
    assert invalid["results"][0]["error_code"] == "CHALLENGE_BINDING_MISMATCH"
    undo["operation_id"] = str(uuid4())
    undone = await call_push(
        client, headers, batch(device, [undo], {undo["operation_id"]: initial})
    )
    assert (
        undone["results"][0]["status"] == "applied"
        and undone["births"][0]["head"] == initial
    )
    if mode == "count":
        state = await snapshot(client, headers, device)
        facts = [
            item["payload"]
            for item in state["changes"]
            if item["entity_type"] == "activity_event"
            and item["payload"]["event_type"] == "count_delta"
        ]
        assert [item["count_policy"] for item in facts] == [
            dict(target_value=1, is_countdown=countdown)
        ] * 2
        async with engine.connect() as connection:
            assert (
                await connection.execute(
                    text("SELECT COUNT(*) FROM activity_count_days")
                )
            ).scalar_one() == 1


async def test_changed_context_rejects_even_cached_negative_receipt_and_legacy_route(
    production_v5,
):
    client, _, headers, device = production_v5
    activity, plan, initial = await seed(production_v5)
    current = (
        await call_push(client, headers, batch(device, [restart(activity, initial, 1)]))
    )["results"][0]["entity"]["head"]
    old_edit = operation(
        {**plan, "title": "obsolete round"}, identity=activity, revision=1
    )
    body = batch(device, [old_edit], {old_edit["operation_id"]: initial})
    rejected = await call_push(client, headers, body)
    assert rejected["results"][0]["error_code"] == "CHALLENGE_STATE_CONFLICT"
    assert (await call_push(client, headers, body))["results"] == rejected["results"]
    changed = deepcopy(body)
    changed["contexts"][0]["head"] = current
    assert (await call_push(client, headers, changed))["results"][0][
        "error_code"
    ] == "CHALLENGE_SOURCE_REUSED"
    old = await client.post(
        "/api/v2/sync/push",
        headers=headers,
        json=dict(device_id=device, operations=[old_edit]),
    )
    assert (
        old.status_code == 200
        and old.json()["results"][0]["error_code"] == "CLIENT_UPGRADE_REQUIRED"
    )
    for path in ("/api/v2/sync/bootstrap", "/api/v2/sync/changes"):
        denied = await client.get(path, headers=headers, params=dict(device_id=device))
        assert denied.status_code == 426


@pytest.mark.parametrize("countdown", [False, True])
async def test_full_timer_chain_keeps_birth_and_blocks_restart_until_terminal(
    production_v5, countdown
):
    client, engine, headers, device = production_v5
    activity, _, initial = await seed(production_v5, "duration", countdown)
    head = (
        await call_push(client, headers, batch(device, [restart(activity, initial, 1)]))
    )["results"][0]["entity"]["head"]
    identity = str(uuid4())
    occurred = datetime.now(UTC) - timedelta(minutes=4)
    start = timer_command("start", identity, 1, occurred, activity_id=activity)
    start["start_policy"] = dict(
        target_seconds=60,
        is_countdown=countdown,
        max_duration_seconds=60 if countdown else 180,
    )
    commands = [
        start,
        timer_command(
            "pause",
            identity,
            2,
            occurred + timedelta(seconds=20),
            revision=1,
            active_elapsed_ms=20_000,
        ),
        timer_command(
            "resume", identity, 3, occurred + timedelta(seconds=30), revision=2
        ),
        timer_command(
            "stop",
            identity,
            4,
            occurred + timedelta(seconds=70),
            revision=3,
            active_elapsed_ms=60_000,
        ),
    ]
    for index, command in enumerate(commands):
        response = await client.post(
            COMMANDS, headers=headers, json=timer_batch(device, [command], head)
        )
        assert response.status_code == 200, response.text
        RoundTimerCommandBatchResponse.model_validate(response.json())
        assert response.json()["results"][0]["status"] == "applied", response.text
        assert all(birth["head"] == head for birth in response.json()["births"])
        if index in (0, 1):
            blocked = await call_push(
                client, headers, batch(device, [restart(activity, head, 2)])
            )
            assert blocked["results"][0]["error_code"] == "CHALLENGE_TIMER_UNFINISHED"
    latest = await call_push(
        client, headers, batch(device, [restart(activity, head, 2)])
    )
    assert latest["results"][0]["status"] == "applied"
    repeated = await client.post(
        COMMANDS, headers=headers, json=timer_batch(device, [start], head)
    )
    assert (
        repeated.status_code == 200
        and repeated.json()["results"][0]["status"] == "already_applied"
    )
    assert repeated.json()["results"][0]["session"]["state"] == "running"
    assert repeated.json()["births"][0]["head"] == head
    status = await client.get(
        f"/api/v2/timers/rounds/session/{identity}",
        headers=headers,
        params=dict(device_id=device, challenge_contract="1"),
    )
    assert (
        status.status_code == 200 and status.json()["session"]["state"] == "completed"
    )
    assert {item["entity_type"] for item in status.json()["births"]} == {
        "timer_session",
        "activity_event",
    }
    async with engine.connect() as connection:
        assert (
            await connection.execute(
                text("SELECT SUM(duration_ms) FROM duration_day_allocations")
            )
        ).scalar_one() == 60_000


async def test_old_frozen_v5_receipts_require_explicit_initial_marker_without_rewrite(
    production_v5,
):
    client, engine, headers, device = production_v5
    identity = str(uuid4())
    plan = node()
    plan["activity"]["target_cycles"] = 3
    creation = operation(plan, identity=identity)
    original = fact(identity)
    assert [
        item["status"]
        for item in await legacy_push(client, headers, device, [creation, original])
    ] == ["applied", "applied"]
    initial = initial_round_head(identity).model_dump(mode="json")
    body = batch(device, [original], {original["operation_id"]: initial})
    assert (await call_push(client, headers, body))["results"][0][
        "error_code"
    ] == "CHALLENGE_LEGACY_SOURCE_REQUIRED"
    body["contexts"][0]["legacy_initial"] = True
    assert (await call_push(client, headers, body))["results"][0][
        "status"
    ] == "already_applied"
    await call_push(client, headers, batch(device, [restart(identity, initial, 1)]))
    assert (await call_push(client, headers, body))["births"][0]["head"] == initial
    async with engine.connect() as connection:
        assert (
            await connection.execute(
                text(
                    "SELECT challenge_context_json FROM sync_operations WHERE operation_id=:identity"
                ),
                {"identity": original["operation_id"]},
            )
        ).scalar_one() is None


@pytest.mark.parametrize("kind", ["push", "timer", "bootstrap", "pull"])
async def test_profile_final_commit_failure_rolls_back_receipt_binding_and_cursor(
    production_v5, kind
):
    client, engine, headers, device = production_v5
    activity, _, head = await seed(
        production_v5, "duration" if kind == "timer" else "check"
    )
    params = dict(device_id=device, challenge_contract="1")
    if kind == "push":
        path, body, table, action = (
            PUSH,
            batch(device, [restart(activity, head, 1)]),
            "sync_operations",
            "INSERT",
        )
    elif kind == "timer":
        start = timer_command(
            "start", str(uuid4()), 1, datetime.now(UTC), activity_id=activity
        )
        start["start_policy"] = dict(
            target_seconds=60, is_countdown=False, max_duration_seconds=180
        )
        path, body, table, action = (
            COMMANDS,
            timer_batch(device, [start], head),
            "timer_commands",
            "INSERT",
        )
    else:
        path, body, table, action = (
            BOOT if kind == "bootstrap" else PULL,
            None,
            "sync_cursors",
            "UPDATE",
        )
    async with engine.begin() as connection:
        await connection.execute(
            text(
                "CREATE TABLE challenge_commit_fault (bad_user INTEGER REFERENCES users(id) DEFERRABLE INITIALLY DEFERRED)"
            )
        )
        await connection.execute(
            text(
                f"CREATE TRIGGER inject_challenge_final_fault AFTER {action} ON {table} BEGIN INSERT INTO challenge_commit_fault VALUES (-999999); END"
            )
        )
    before = await database_state(engine)

    async def call():
        return (
            await client.post(path, headers=headers, json=body)
            if body is not None
            else await client.get(path, headers=headers, params=params)
        )

    failed = await call()
    assert failed.status_code == 500 and "applied" not in failed.text
    assert await database_state(engine) == before
    async with engine.begin() as connection:
        await connection.execute(text("DROP TRIGGER inject_challenge_final_fault"))
    retried = await call()
    assert retried.status_code == 200, retried.text
    if kind in {"push", "timer"}:
        assert retried.json()["results"][0]["status"] == "applied"


async def test_damaged_response_never_advances_cursor(production_v5, monkeypatch):
    import src.v2.challenge_router as routing

    client, engine, headers, device = production_v5
    await seed(production_v5)
    original = routing.read_challenge_metadata

    async def broken(*args, **kwargs):
        metadata = await original(*args, **kwargs)
        wire = metadata.model_dump(mode="json")
        wire["checkpoints"][0]["records"] = []
        return SimpleNamespace(model_dump=lambda **_unused: wire)

    monkeypatch.setattr(routing, "read_challenge_metadata", broken)
    before = await database_state(engine)
    response = await client.get(
        BOOT, headers=headers, params=dict(device_id=device, challenge_contract="1")
    )
    assert response.status_code == 500 and await database_state(engine) == before


async def test_backup_and_archive_preserve_original_scope_and_reject_tampered_context(
    production_v5, tmp_path
):
    client, engine, headers, device = production_v5
    activity, _, initial = await seed(production_v5)
    original = fact(activity)
    await call_push(
        client, headers, batch(device, [original], {original["operation_id"]: initial})
    )
    await call_push(client, headers, batch(device, [restart(activity, initial, 1)]))
    source = Path(engine.url.database)
    assert inspect_database(source).valid
    backup, _ = create_backup(source, tmp_path / "backups", kind="manual")
    restored = tmp_path / "physical.sqlite"
    restore_backup(backup, restored, expected_alembic_head="000000000009")
    assert inspect_database(restored).valid
    archive = tmp_path / "archive.zip"
    export_archive(f"sqlite:///{source}", archive)
    target = tmp_path / "logical.sqlite"
    migrate(target)
    import_archive(f"sqlite:///{target}", archive)
    assert inspect_database(target).valid
    bundle = read_bundle(archive)
    records = [
        json.loads(line)
        for line in bundle["collections"]["sync_operations"].splitlines()
    ]
    record = next(
        item
        for item in records
        if item["data"]["operation_id"] == original["operation_id"]
    )
    record["data"]["challenge_context_json"] = "{}"
    replace_records(bundle, "sync_operations", records)
    corrupt = tmp_path / "corrupt.zip"
    write_bundle(corrupt, bundle)
    empty = tmp_path / "empty.sqlite"
    migrate(empty)
    before = database_dump(empty)
    with pytest.raises(StorageValidationError, match="challenge receipt"):
        import_archive(f"sqlite:///{empty}", corrupt)
    assert database_dump(empty) == before


@pytest.mark.parametrize(
    "entry", ["push", "bootstrap", "pull", "commands", "active", "status"]
)
async def test_all_profile_entries_admit_actual_v5_and_exact_captured_scope(
    production_v5, entry
):
    client, engine, headers, device = production_v5

    async def call(given):
        params = dict(device_id=device, challenge_contract="1")
        if entry == "push":
            return await client.post(
                PUSH,
                headers=given,
                json=batch(
                    device, [operation(metric(), identity=str(uuid4()), kind="metric")]
                ),
            )
        if entry == "commands":
            command = timer_command(
                "cancel", str(uuid4()), 2, datetime.now(UTC), revision=1
            )
            return await client.post(
                COMMANDS,
                headers=given,
                json=timer_batch(
                    device,
                    [command],
                    initial_round_head(str(uuid4())).model_dump(mode="json"),
                ),
            )
        path = {
            "bootstrap": BOOT,
            "pull": PULL,
            "active": ACTIVE,
            "status": f"/api/v2/timers/rounds/session/{uuid4()}",
        }[entry]
        return await client.get(path, headers=given, params=params)

    for field, value, code in (
        (PROTOCOL_HEADER, "4", "CLIENT_UPGRADE_REQUIRED"),
        (INSTANCE_HEADER, str(uuid4()), "SERVER_IDENTITY_MISMATCH"),
        (EPOCH_HEADER, str(uuid4()), "SYNC_EPOCH_MISMATCH"),
    ):
        before = await database_state(engine)
        invalid = {**headers, field: value}
        response = await call(invalid)
        assert (
            response.status_code in {409, 426}
            and response.json()["detail"]["code"] == code
        )
        assert await database_state(engine) == before
    async with engine.begin() as connection:
        await connection.execute(text("UPDATE server_instances SET protocol_version=4"))
    before = await database_state(engine)
    response = await call(headers)
    assert response.status_code == 426 and await database_state(engine) == before


@pytest.mark.parametrize("value", [True, "1", 1.0, 0, 2, None])
async def test_profile_marker_is_strict_and_cannot_write(production_v5, value):
    client, engine, headers, device = production_v5
    body = batch(device, [operation(goal(), identity=str(uuid4()))])
    body["challenge_contract"] = value
    before = await database_state(engine)
    response = await client.post(PUSH, headers=headers, json=body)
    assert response.status_code == 422 and await database_state(engine) == before


@pytest.mark.parametrize("policy", ["cascade_children", "detach_children"])
async def test_goal_child_mutations_require_complete_current_round_set(
    production_v5, policy
):
    client, _, headers, device = production_v5
    activity, plan, initial = await seed(production_v5)
    parent = str(uuid4())
    assert (
        await call_push(
            client, headers, batch(device, [operation(goal(), identity=parent)])
        )
    )["results"][0]["status"] == "applied"
    edit = operation({**plan, "parent_uuid": parent}, identity=activity, revision=1)
    assert (
        await call_push(
            client, headers, batch(device, [edit], {edit["operation_id"]: initial})
        )
    )["results"][0]["status"] == "applied"
    head = (
        await call_push(client, headers, batch(device, [restart(activity, initial, 2)]))
    )["results"][0]["entity"]["head"]
    deletion = operation(
        dict(child_policy=policy), identity=parent, revision=1, action="delete"
    )
    for captured in ([], [initial]):
        body = batch(device, [deletion])
        body["contexts"][0]["affected_heads"] = captured
        assert (await call_push(client, headers, body))["results"][0][
            "error_code"
        ] == "CHALLENGE_STATE_CONFLICT"
        deletion["operation_id"] = str(uuid4())
    body = batch(device, [deletion])
    body["contexts"][0]["affected_heads"] = [head]
    assert (await call_push(client, headers, body))["results"][0]["status"] == "applied"
    state = await snapshot(client, headers, device)
    nodes = {
        item["entity_uuid"]: item
        for item in state["changes"]
        if item["entity_type"] == "plan_node"
    }
    assert parent not in nodes
    assert (activity in nodes) == (policy == "detach_children")
    assert state["checkpoints"][0]["head"] == head


@pytest.mark.parametrize("countdown", [False, True])
async def test_late_offline_timer_stays_in_its_initial_round_and_cancel_has_no_fact(
    production_v5, countdown
):
    client, engine, headers, device = production_v5
    activity, _, initial = await seed(production_v5, "duration", countdown)
    head = (
        await call_push(client, headers, batch(device, [restart(activity, initial, 1)]))
    )["results"][0]["entity"]["head"]
    identity = str(uuid4())
    occurred = datetime.now(UTC) - timedelta(minutes=2)
    start = timer_command("start", identity, 1, occurred, activity_id=activity)
    start["start_policy"] = dict(
        target_seconds=60,
        is_countdown=countdown,
        max_duration_seconds=60 if countdown else 180,
    )
    cancel = timer_command(
        "cancel",
        identity,
        2,
        occurred + timedelta(seconds=30),
        revision=1,
        active_elapsed_ms=30_000,
    )
    response = await client.post(
        COMMANDS, headers=headers, json=timer_batch(device, [start, cancel], initial)
    )
    assert response.status_code == 200, response.text
    assert [item["status"] for item in response.json()["results"]] == [
        "applied",
        "applied",
    ]
    assert response.json()["results"][1]["session"]["state"] == "cancelled"
    assert response.json()["births"] == [
        dict(entity_type="timer_session", entity_uuid=identity, head=initial)
    ]
    assert response.json()["checkpoints"][0]["head"] == head
    async with engine.connect() as connection:
        assert (
            await connection.execute(text("SELECT COUNT(*) FROM activity_events"))
        ).scalar_one() == 0
    changed = timer_batch(device, [start], head)
    rejected = await client.post(COMMANDS, headers=headers, json=changed)
    assert (
        rejected.status_code == 200
        and rejected.json()["results"][0]["error_code"] == "CHALLENGE_SOURCE_REUSED"
    )
    old = await client.post(
        "/api/v2/timers/commands",
        headers=headers,
        json=dict(device_id=device, commands=[start]),
    )
    assert (
        old.status_code == 200
        and old.json()["results"][0]["error_code"] == "CLIENT_UPGRADE_REQUIRED"
    )


async def test_stale_restart_can_be_rejected_without_poisoning_later_batch_operation(
    production_v5,
):
    client, _, headers, device = production_v5
    activity, _, initial = await seed(production_v5)
    await call_push(client, headers, batch(device, [restart(activity, initial, 1)]))
    stale = restart(activity, initial, 1)
    later = operation(metric(), identity=str(uuid4()), kind="metric")
    response = await call_push(client, headers, batch(device, [stale, later]))
    assert response["results"][0]["error_code"] == "CHALLENGE_STATE_CONFLICT"
    assert response["results"][1]["status"] == "applied"


async def test_restart_cannot_appropriate_internal_round_with_missing_original_receipt(
    production_v5,
):
    from sqlalchemy.ext.asyncio import async_sessionmaker
    from src.v2.challenge_storage import restart_stored_challenge
    from src.v2.challenge_round import ChallengeRestartIntent

    client, engine, headers, device = production_v5
    activity, _, initial = await seed(production_v5)
    original = restart(activity, initial, 1)
    async with async_sessionmaker(engine, expire_on_commit=False).begin() as session:
        record = await restart_stored_challenge(
            session,
            1,
            device,
            original["operation_id"],
            ChallengeRestartIntent.model_validate(original["payload"]),
        )
        assert record.head.generation == 1
    response = await call_push(client, headers, batch(device, [original]))
    assert response["results"][0]["error_code"] == "CHALLENGE_RECEIPT_INVALID"


@pytest.mark.parametrize("damage", ["context", "hash", "result-source", "birth"])
async def test_damaged_receipt_or_birth_blocks_reads_without_advancing_cursor(
    production_v5, damage
):
    client, engine, headers, device = production_v5
    activity, _, initial = await seed(production_v5)
    original = fact(activity)
    await call_push(
        client, headers, batch(device, [original], {original["operation_id"]: initial})
    )
    await call_push(client, headers, batch(device, [restart(activity, initial, 1)]))
    async with engine.begin() as connection:
        if damage == "birth":
            await connection.execute(
                text(
                    "UPDATE activity_challenge_event_bindings SET round_id=(SELECT id FROM activity_challenge_rounds WHERE generation=1)"
                )
            )
        elif damage == "context":
            await connection.execute(
                text(
                    "UPDATE sync_operations SET challenge_context_json='{}' WHERE operation_id=:source"
                ),
                {"source": original["operation_id"]},
            )
        elif damage == "hash":
            await connection.execute(
                text(
                    "UPDATE sync_operations SET request_hash=:hash WHERE operation_id=:source"
                ),
                {"hash": "f" * 64, "source": original["operation_id"]},
            )
        else:
            row = (
                await connection.execute(
                    text(
                        "SELECT result_json FROM sync_operations WHERE operation_id=:source"
                    ),
                    {"source": original["operation_id"]},
                )
            ).scalar_one()
            result = json.loads(row)
            result["operation_id"] = str(uuid4())
            await connection.execute(
                text(
                    "UPDATE sync_operations SET result_json=:result WHERE operation_id=:source"
                ),
                {"result": json.dumps(result), "source": original["operation_id"]},
            )
    before = await database_state(engine)
    for path in (BOOT, PULL):
        response = await client.get(
            path, headers=headers, params=dict(device_id=device, challenge_contract="1")
        )
        assert (
            response.status_code == 400
            and response.json()["detail"]["code"] == "CHALLENGE_RECEIPT_INVALID"
        )
        assert await database_state(engine) == before
    assert not inspect_database(Path(engine.url.database)).valid


@pytest.mark.parametrize("change", ["missing", "duplicate", "foreign", "unknown-field"])
async def test_context_source_set_is_strict_before_receipt_reservation(
    production_v5, change
):
    client, engine, headers, device = production_v5
    body = batch(device, [operation(metric(), identity=str(uuid4()), kind="metric")])
    if change == "missing":
        body["contexts"] = []
    elif change == "duplicate":
        body["contexts"].append(deepcopy(body["contexts"][0]))
    elif change == "foreign":
        body["contexts"][0]["source_uuid"] = str(uuid4())
    else:
        body["contexts"][0]["owner_user_id"] = 1
    before = await database_state(engine)
    response = await client.post(PUSH, headers=headers, json=body)
    assert response.status_code == 422 and await database_state(engine) == before


async def test_legacy_new_timer_commands_cannot_bypass_positive_birth_profile(
    production_v5,
):
    client, _, headers, device = production_v5
    activity, _, initial = await seed(production_v5, "duration")
    head = (
        await call_push(client, headers, batch(device, [restart(activity, initial, 1)]))
    )["results"][0]["entity"]["head"]
    identity = str(uuid4())
    occurred = datetime.now(UTC) - timedelta(minutes=1)
    start = timer_command("start", identity, 1, occurred, activity_id=activity)
    start["start_policy"] = dict(
        target_seconds=60, is_countdown=False, max_duration_seconds=180
    )
    started = await client.post(
        COMMANDS, headers=headers, json=timer_batch(device, [start], head)
    )
    assert (
        started.status_code == 200
        and started.json()["results"][0]["status"] == "applied"
    )
    pause = timer_command(
        "pause",
        identity,
        2,
        occurred + timedelta(seconds=20),
        revision=1,
        active_elapsed_ms=20_000,
    )
    old = await client.post(
        "/api/v2/timers/commands",
        headers=headers,
        json=dict(device_id=device, commands=[pause]),
    )
    assert (
        old.status_code == 200
        and old.json()["results"][0]["error_code"] == "CLIENT_UPGRADE_REQUIRED"
    )
    assert old.json()["results"][0]["session"]["state"] == "running"
    cancel = timer_command(
        "cancel",
        identity,
        2,
        occurred + timedelta(seconds=20),
        revision=1,
        active_elapsed_ms=20_000,
    )
    stopped = await client.post(
        COMMANDS, headers=headers, json=timer_batch(device, [cancel], head)
    )
    assert (
        stopped.status_code == 200
        and stopped.json()["results"][0]["status"] == "applied"
    )


async def test_legacy_baseline_timer_can_finish_when_another_activity_has_new_round(
    production_v5,
):
    client, _, headers, device = production_v5
    duration, _, _ = await seed(production_v5, "duration")
    check, _, initial = await seed(production_v5)
    identity = str(uuid4())
    occurred = datetime.now(UTC) - timedelta(minutes=1)
    start = timer_command("start", identity, 1, occurred, activity_id=duration)
    start["start_policy"] = dict(
        target_seconds=60, is_countdown=False, max_duration_seconds=180
    )
    started = await client.post(
        "/api/v2/timers/commands",
        headers=headers,
        json=dict(device_id=device, commands=[start]),
    )
    assert (
        started.status_code == 200
        and started.json()["results"][0]["status"] == "applied"
    )
    assert (
        await call_push(client, headers, batch(device, [restart(check, initial, 1)]))
    )["results"][0]["status"] == "applied"
    cancel = timer_command(
        "cancel",
        identity,
        2,
        occurred + timedelta(seconds=20),
        revision=1,
        active_elapsed_ms=20_000,
    )
    stopped = await client.post(
        "/api/v2/timers/commands",
        headers=headers,
        json=dict(device_id=device, commands=[cancel]),
    )
    assert (
        stopped.status_code == 200
        and stopped.json()["results"][0]["status"] == "applied"
    )
    assert stopped.json()["results"][0]["session"]["state"] == "cancelled"


@pytest.mark.parametrize("kind", ["sync", "timer"])
async def test_corrupt_receipt_owner_cannot_publish_another_accounts_cached_result(
    production_v5, kind
):
    from sqlalchemy.ext.asyncio import async_sessionmaker
    from src.auth.models import User
    from tests.account_fixtures import account_password_hash

    client, engine, headers, device = production_v5
    activity, _, initial = await seed(
        production_v5, "duration" if kind == "timer" else "check"
    )
    if kind == "sync":
        original = fact(activity)
        path, body, table, field, identity = (
            PUSH,
            batch(device, [original], {original["operation_id"]: initial}),
            "sync_operations",
            "operation_id",
            original["operation_id"],
        )
    else:
        original = timer_command(
            "start", str(uuid4()), 1, datetime.now(UTC), activity_id=activity
        )
        original["start_policy"] = dict(
            target_seconds=60, is_countdown=False, max_duration_seconds=180
        )
        path, body, table, field, identity = (
            COMMANDS,
            timer_batch(device, [original], initial),
            "timer_commands",
            "command_id",
            original["command_id"],
        )
    accepted = await client.post(path, headers=headers, json=body)
    assert (
        accepted.status_code == 200
        and accepted.json()["results"][0]["status"] == "applied"
    )
    async with async_sessionmaker(engine, expire_on_commit=False).begin() as session:
        foreign = User(
            username="foreign-receipt-owner", password_hash=account_password_hash()
        )
        session.add(foreign)
        await session.flush()
        await session.execute(
            text(f"UPDATE {table} SET user_id=:owner WHERE {field}=:identity"),
            {"owner": foreign.id, "identity": identity},
        )
    before = await database_state(engine)
    rejected = await client.post(path, headers=headers, json=body)
    assert (
        rejected.status_code == 400
        and rejected.json()["detail"]["code"] == "SYNC_RECEIPT_INVALID"
    )
    assert await database_state(engine) == before
