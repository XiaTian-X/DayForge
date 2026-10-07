"""Actual production dispatch, migrated SQLite, authentication and final COMMIT."""

from copy import deepcopy
from datetime import UTC, datetime, timedelta
import json
from types import SimpleNamespace
from uuid import uuid4

import pytest
from sqlalchemy import text
from sqlalchemy.exc import OperationalError

from src.main import app
from src.auth.models import User
from src.auth.service import create_access_token
from tests.account_fixtures import account_password_hash
from sqlalchemy.ext.asyncio import async_sessionmaker
from src.v2.next_sync_contract import (
    NextSyncBootstrapResponse,
    NextSyncPullResponse,
    NextSyncPushResponse,
)
from src.v2.replica_context import EPOCH_HEADER, INSTANCE_HEADER, PROTOCOL_HEADER
from src.storage.database_adapter import is_sqlite_busy
from tests.test_http_commit_boundary import database_state, runtime_http as runtime_http
from tests.test_next_structural_sync import goal, metric, node, operation
from tests.test_one_time_mutations import operation as task_operation
from tests.test_one_time_storage import ACTIVITY, EVENTS
from tests.test_timer_sync import timer_command


SESSION = "81000000-0000-4000-8000-000000000099"
PATHS = (
    "/api/v2/devices/register",
    "/api/v2/sync/push",
    "/api/v2/sync/bootstrap",
    "/api/v2/sync/changes",
    "/api/v2/timers/commands",
    "/api/v2/timers/active",
    f"/api/v2/timers/{SESSION}",
    f"/api/v2/timers/{SESSION}/heartbeat",
)


def registration(version=5, installation="commit-primary-device"):
    return dict(
        installation_id=installation, platform="android", protocol_version=version
    )


@pytest.fixture(params=["jwt", "api"])
async def production_v5(runtime_http, request):
    client, engine, bearer, api, device = runtime_http
    # ONLY this isolated migrated fixture activates metadata. Device proof is
    # obtained through actual production registration, never an UPDATE shortcut.
    async with engine.begin() as connection:
        await connection.execute(text("UPDATE server_instances SET protocol_version=5"))
    identity = await client.get("/api/v2/system/identity")
    assert identity.status_code == 200 and identity.json()["protocol_version"] == 5
    headers = dict(bearer if request.param == "jwt" else api)
    headers.update(
        {
            PROTOCOL_HEADER: "5",
            INSTANCE_HEADER: identity.json()["server_instance_id"],
            EPOCH_HEADER: identity.json()["sync_epoch"],
        }
    )
    registered = await client.post(PATHS[0], headers=headers, json=registration())
    assert registered.status_code == 200 and registered.json()["device_id"] == device
    async with engine.connect() as connection:
        assert (
            await connection.execute(
                text("SELECT registered_protocol_version FROM client_devices")
            )
        ).scalar_one() == 5
    yield client, engine, headers, device


async def call_path(client, path, headers, device):
    if path == PATHS[0]:
        return await client.post(path, headers=headers, json=registration())
    if path == PATHS[1]:
        return await client.post(
            path,
            headers=headers,
            json=dict(
                device_id=device,
                operations=[operation(metric(), identity=str(uuid4()), kind="metric")],
            ),
        )
    if path == PATHS[4]:
        return await client.post(
            path,
            headers=headers,
            json=dict(
                device_id=device,
                commands=[
                    timer_command("cancel", SESSION, 2, datetime.now(UTC), revision=1)
                ],
            ),
        )
    if path == PATHS[-1]:
        return await client.post(
            path, headers=headers, json=dict(device_id=device, control_generation=1)
        )
    return await client.get(path, headers=headers, params=dict(device_id=device))


@pytest.mark.parametrize("path", PATHS)
async def test_all_actual_entries_reject_bad_raw_headers_without_any_db_change(
    production_v5, path
):
    client, engine, headers, device = production_v5
    cases = [
        (PROTOCOL_HEADER, (), 426, "CLIENT_UPGRADE_REQUIRED"),
        (PROTOCOL_HEADER, ("4",), 426, "CLIENT_UPGRADE_REQUIRED"),
        (PROTOCOL_HEADER, ("6",), 426, "CLIENT_UPGRADE_REQUIRED"),
        (PROTOCOL_HEADER, ("5", "5"), 426, "CLIENT_UPGRADE_REQUIRED"),
        (PROTOCOL_HEADER, ("05",), 426, "CLIENT_UPGRADE_REQUIRED"),
        (PROTOCOL_HEADER, (" 5",), 426, "CLIENT_UPGRADE_REQUIRED"),
        (INSTANCE_HEADER, (), 400, "INVALID_SYNC_CONTEXT"),
        (EPOCH_HEADER, (), 400, "INVALID_SYNC_CONTEXT"),
        (
            INSTANCE_HEADER,
            (headers[INSTANCE_HEADER], headers[INSTANCE_HEADER]),
            400,
            "INVALID_SYNC_CONTEXT",
        ),
        (
            EPOCH_HEADER,
            (headers[EPOCH_HEADER] + "," + headers[EPOCH_HEADER],),
            400,
            "INVALID_SYNC_CONTEXT",
        ),
        (EPOCH_HEADER, (" " + headers[EPOCH_HEADER],), 400, "INVALID_SYNC_CONTEXT"),
        (INSTANCE_HEADER, (str(uuid4()),), 409, "SERVER_IDENTITY_MISMATCH"),
        (EPOCH_HEADER, (str(uuid4()),), 409, "SYNC_EPOCH_MISMATCH"),
    ]
    for field, values, status, code in cases:
        raw = [(name, value) for name, value in headers.items() if name != field]
        raw.extend((field, value) for value in values)
        before = await database_state(engine)
        response = await call_path(client, path, raw, device)
        assert response.status_code == status, (path, field, values, response.text)
        assert response.json()["detail"]["code"] == code
        assert headers[INSTANCE_HEADER] not in response.text
        assert headers[EPOCH_HEADER] not in response.text
        assert await database_state(engine) == before


@pytest.mark.parametrize("path", PATHS[1:])
async def test_all_business_entries_require_current_owned_registered_v5_device(
    production_v5, path
):
    client, engine, headers, device = production_v5
    for version in (None, 4, 6):
        async with engine.begin() as connection:
            await connection.execute(
                text("UPDATE client_devices SET registered_protocol_version=:version"),
                {"version": version},
            )
        before = await database_state(engine)
        response = await call_path(client, path, headers, device)
        assert response.status_code == 426 and response.json()["detail"]["code"] == (
            "CLIENT_UPGRADE_REQUIRED"
        )
        assert await database_state(engine) == before
    async with engine.begin() as connection:
        await connection.execute(
            text(
                "UPDATE client_devices SET registered_protocol_version=5,revoked_at='2026-10-06 00:00:00'"
            )
        )
    before = await database_state(engine)
    response = await call_path(client, path, headers, device)
    assert response.status_code == 404
    assert await database_state(engine) == before
    async with engine.begin() as connection:
        await connection.execute(text("UPDATE client_devices SET revoked_at=NULL"))
    before = await database_state(engine)
    response = await call_path(client, path, headers, str(uuid4()))
    assert response.status_code == 404
    assert await database_state(engine) == before


async def test_original_registration_source_is_strict_and_failure_preserves_proof(
    production_v5,
):
    client, engine, headers, _ = production_v5
    for version in ("5", 5.0, True, None, 4, 6, 10**100, "missing"):
        body = registration(version)
        if version == "missing":
            del body["protocol_version"]
        before = await database_state(engine)
        rejected = await client.post(PATHS[0], headers=headers, json=body)
        assert rejected.status_code == 422, (version, rejected.text)
        assert await database_state(engine) == before


@pytest.mark.parametrize("path", PATHS)
async def test_identity_discovery_does_not_authorize_request_after_epoch_changes(
    production_v5, path
):
    client, engine, headers, device = production_v5
    async with engine.begin() as connection:
        await connection.execute(
            text("UPDATE server_instances SET sync_epoch=:epoch"),
            {"epoch": str(uuid4())},
        )
    current = await client.get("/api/v2/system/identity")
    assert current.json()["sync_epoch"] != headers[EPOCH_HEADER]
    before = await database_state(engine)
    response = await call_path(client, path, headers, device)
    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "SYNC_EPOCH_MISMATCH"
    assert await database_state(engine) == before


async def push(client, headers, device, operations):
    response = await client.post(
        PATHS[1], headers=headers, json=dict(device_id=device, operations=operations)
    )
    assert response.status_code == 200, response.text
    NextSyncPushResponse.model_validate(response.json())
    return response.json()["results"]


async def test_task_complete_cross_day_undo_conflict_replay_and_paginated_recovery(
    production_v5,
):
    client, engine, headers, device = production_v5
    goal_id, metric_id = str(uuid4()), str(uuid4())
    task = node(once=True) | {"parent_uuid": goal_id}
    created = await push(
        client,
        headers,
        device,
        [
            operation(goal(), identity=goal_id),
            operation(task),
            operation(metric(), identity=metric_id, kind="metric"),
        ],
    )
    assert [item["status"] for item in created] == ["applied"] * 3
    complete = task_operation(0).model_dump(mode="json")
    first = (await push(client, headers, device, [complete]))[0]
    assert first["status"] == "applied"
    replay = (await push(client, headers, device, [complete]))[0]
    assert replay == {**first, "status": "already_applied"}
    conflicting = deepcopy(complete)
    conflicting["operation_id"] = str(uuid4())
    conflicting["entity_uuid"] = str(uuid4())
    conflicting["payload"]["one_time"]["event_uuid"] = conflicting["entity_uuid"]
    refused = (await push(client, headers, device, [conflicting]))[0]
    assert refused["status"] in {"conflict", "rejected"}
    assert refused["entity"] is None and refused["revision"] is None
    assert refused["one_time_conflict"]["activity_uuid"] == ACTIVITY
    assert refused["one_time_conflict"]["state"]["completion_event_uuid"] == EVENTS[0]
    conflict_replay = (await push(client, headers, device, [conflicting]))[0]
    assert conflict_replay == refused
    for index in (1, 2):
        accepted = (
            await push(
                client, headers, device, [task_operation(index).model_dump(mode="json")]
            )
        )[0]
        assert accepted["status"] == "applied"
        assert accepted["entity"]["one_time_state_after"]["version"] == index + 1
    recovered = await client.get(
        PATHS[2], headers=headers, params=dict(device_id=device)
    )
    assert recovered.status_code == 200, recovered.text
    snapshot = recovered.json()
    NextSyncBootstrapResponse.model_validate(snapshot)
    assert snapshot["one_time_checkpoints"][0]["state"]["version"] == 3
    assert (
        snapshot["one_time_checkpoints"][0]["state"]["completion_event_uuid"]
        == EVENTS[2]
    )
    facts = [
        item for item in snapshot["changes"] if item["entity_type"] == "activity_event"
    ]
    assert len(facts) == 3
    assert [item["payload"]["occurred_at"] for item in facts] == [
        f"2026-09-{day}T23:59:00.123456Z" for day in (23, 24, 25)
    ]
    cursor, changes = 0, []
    while True:
        page = await client.get(
            PATHS[3],
            headers=headers,
            params=dict(device_id=device, cursor=cursor, limit=1),
        )
        assert page.status_code == 200, page.text
        body = page.json()
        NextSyncPullResponse.model_validate(body)
        changes.extend(body["changes"])
        cursor = body["next_cursor"]
        if not body["has_more"]:
            break
    assert cursor == snapshot["next_cursor"]
    assert [
        item["entity_uuid"]
        for item in changes
        if item["entity_type"] == "activity_event"
    ] == EVENTS[:3]
    async with engine.connect() as connection:
        assert (
            await connection.execute(text("SELECT COUNT(*) FROM plan_nodes"))
        ).scalar_one() == 2
        assert (
            await connection.execute(text("SELECT COUNT(*) FROM activity_events"))
        ).scalar_one() == 3


@pytest.mark.parametrize("countdown", [False, True])
async def test_full_timer_commands_reads_heartbeat_and_exact_replay(
    production_v5, countdown
):
    client, engine, headers, device = production_v5
    activity, timer = str(uuid4()), str(uuid4())
    assert (
        await push(
            client,
            headers,
            device,
            [operation(node(mode="duration", countdown=countdown), identity=activity)],
        )
    )[0]["status"] == "applied"
    start = datetime.now(UTC) - timedelta(minutes=5)
    commands = [
        timer_command("start", timer, 1, start, activity_id=activity),
        timer_command(
            "pause",
            timer,
            2,
            start + timedelta(seconds=30),
            revision=1,
            active_elapsed_ms=30000,
        ),
        timer_command("resume", timer, 3, start + timedelta(seconds=60), revision=2),
        timer_command(
            "stop",
            timer,
            4,
            start + timedelta(seconds=90),
            revision=3,
            active_elapsed_ms=60000,
        ),
    ]
    commands[0]["start_policy"] = dict(
        target_seconds=60,
        is_countdown=countdown,
        max_duration_seconds=60 if countdown else 180,
    )
    first = await client.post(
        PATHS[4], headers=headers, json=dict(device_id=device, commands=commands[:1])
    )
    assert (
        first.status_code == 200 and first.json()["results"][0]["status"] == "applied"
    )
    active = await client.get(PATHS[5], headers=headers, params=dict(device_id=device))
    status = await client.get(
        f"/api/v2/timers/{timer}", headers=headers, params=dict(device_id=device)
    )
    assert active.status_code == status.status_code == 200
    assert (
        active.json()["session"]["session_id"]
        == status.json()["session"]["session_id"]
        == timer
    )
    heartbeat = await client.post(
        f"/api/v2/timers/{timer}/heartbeat",
        headers=headers,
        json=dict(device_id=device, control_generation=1),
    )
    assert heartbeat.status_code == 200 and heartbeat.json()["accepted"]
    rest = await client.post(
        PATHS[4], headers=headers, json=dict(device_id=device, commands=commands[1:])
    )
    assert rest.status_code == 200, rest.text
    results = first.json()["results"] + rest.json()["results"]
    assert [item["status"] for item in results] == ["applied"] * 4
    assert results[-1]["session"]["state"] == "completed"
    assert results[-1]["session"]["active_elapsed_ms"] == 60000
    replay = await client.post(
        PATHS[4], headers=headers, json=dict(device_id=device, commands=commands)
    )
    assert replay.json()["results"] == [
        {**item, "status": "already_applied"} for item in results
    ]
    async with engine.connect() as connection:
        assert (
            await connection.execute(text("SELECT COUNT(*) FROM activity_events"))
        ).scalar_one() == 1
        assert (
            await connection.execute(
                text("SELECT SUM(duration_ms) FROM duration_day_allocations")
            )
        ).scalar_one() == 60000


@pytest.mark.parametrize("kind", ["register", "push", "timer", "bootstrap", "pull"])
async def test_real_final_commit_failure_never_acknowledges_and_original_request_retries(
    production_v5, kind
):
    client, engine, headers, device = production_v5
    params: dict[str, str]
    if kind == "register":
        method, path, body, params, table, action = (
            "POST",
            PATHS[0],
            registration(),
            {},
            "client_devices",
            "UPDATE",
        )
    elif kind == "push":
        method, path, body, params, table, action = (
            "POST",
            PATHS[1],
            dict(
                device_id=device,
                operations=[operation(metric(), identity=str(uuid4()), kind="metric")],
            ),
            {},
            "sync_operations",
            "INSERT",
        )
    elif kind == "timer":
        activity, timer = str(uuid4()), str(uuid4())
        assert (
            await push(
                client,
                headers,
                device,
                [operation(node(mode="duration"), identity=activity)],
            )
        )[0]["status"] == "applied"
        start_command = timer_command(
            "start", timer, 1, datetime.now(UTC), activity_id=activity
        )
        start_command["start_policy"] = dict(
            target_seconds=60, is_countdown=False, max_duration_seconds=180
        )
        method, path, body, params, table, action = (
            "POST",
            PATHS[4],
            dict(
                device_id=device,
                commands=[start_command],
            ),
            {},
            "timer_commands",
            "INSERT",
        )
    else:
        method, path, body, params, table, action = (
            "GET",
            PATHS[2 if kind == "bootstrap" else 3],
            None,
            dict(device_id=device),
            "sync_cursors",
            "INSERT",
        )
    async with engine.begin() as connection:
        await connection.execute(
            text(
                "CREATE TABLE commit_fault (bad_user INTEGER REFERENCES users(id) DEFERRABLE INITIALLY DEFERRED)"
            )
        )
        await connection.execute(
            text(
                f"CREATE TRIGGER inject_final_fault AFTER {action} ON {table} BEGIN INSERT INTO commit_fault VALUES (-999999); END"
            )
        )
    before = await database_state(engine)
    kwargs = dict(headers=headers, params=params)
    if body is not None:
        kwargs["json"] = body
    failed = await client.request(method, path, **kwargs)
    assert failed.status_code == 500 and "FOREIGN KEY" not in failed.text
    assert "applied" not in failed.text and "one_time_checkpoints" not in failed.text
    assert await database_state(engine) == before
    async with engine.begin() as connection:
        await connection.execute(text("DROP TRIGGER inject_final_fault"))
    retried = await client.request(method, path, **kwargs)
    assert retried.status_code == 200, retried.text
    if kind in {"push", "timer"}:
        assert retried.json()["results"][0]["status"] == "applied"
        replayed = await client.request(method, path, **kwargs)
        assert replayed.json()["results"][0] == {
            **retried.json()["results"][0],
            "status": "already_applied",
        }


@pytest.mark.parametrize(
    "policy,code",
    [
        (None, "TIMER_START_POLICY_REQUIRED"),
        (
            dict(target_seconds=120, is_countdown=False, max_duration_seconds=360),
            "TIMER_START_CONFIG_CHANGED",
        ),
        (
            dict(target_seconds=60, is_countdown=True, max_duration_seconds=60),
            "TIMER_START_CONFIG_CHANGED",
        ),
    ],
)
async def test_start_policy_rejection_is_durable_without_timer_or_fact(
    production_v5, policy, code
):
    client, engine, headers, device = production_v5
    activity, timer = str(uuid4()), str(uuid4())
    assert (
        await push(
            client,
            headers,
            device,
            [operation(node(mode="duration"), identity=activity)],
        )
    )[0]["status"] == "applied"
    command = timer_command("start", timer, 1, datetime.now(UTC), activity_id=activity)
    if policy is not None:
        command["start_policy"] = policy
    body = dict(device_id=device, commands=[command])
    first = await client.post(PATHS[4], headers=headers, json=body)
    assert first.status_code == 200, first.text
    result = first.json()["results"][0]
    assert result["status"] in {"rejected", "conflict"} and result["error_code"] == code
    assert result["session"] is None
    state = await database_state(engine)
    replay = await client.post(PATHS[4], headers=headers, json=body)
    assert replay.status_code == 200 and replay.json()["results"][0] == result
    replayed_state = await database_state(engine)
    # Authenticated admission legitimately updates visit metadata, not command/business history.
    for table in state:
        visit_column = {
            "client_devices": "last_seen_at",
            "api_tokens": "last_used_at",
        }.get(table)
        if visit_column is None:
            assert replayed_state[table] == state[table]
        else:
            assert len(replayed_state[table]) == len(state[table])
            for before, after in zip(state[table], replayed_state[table], strict=True):
                original, current = dict(before._mapping), dict(after._mapping)
                assert current[visit_column] == original[visit_column] or (
                    current[visit_column] is not None
                    and original[visit_column] is not None
                    and current[visit_column] >= original[visit_column]
                )
                del original[visit_column], current[visit_column]
                assert current == original
    async with engine.connect() as connection:
        for table in (
            "timer_sessions",
            "timer_segments",
            "duration_day_allocations",
            "activity_events",
        ):
            assert (
                await connection.execute(text(f"SELECT COUNT(*) FROM {table}"))
            ).scalar_one() == 0


@pytest.mark.parametrize("countdown", [False, True])
@pytest.mark.parametrize("initial_target,next_target", [(60, 300), (120, 60)])
async def test_config_edit_only_changes_next_timer_not_current_completion_or_replay(
    production_v5, countdown, initial_target, next_target
):
    client, engine, headers, device = production_v5
    activity, timer = str(uuid4()), str(uuid4())
    plan = node(mode="duration", countdown=countdown)
    plan["activity"]["target_value"] = initial_target
    assert (await push(client, headers, device, [operation(plan, identity=activity)]))[
        0
    ]["status"] == "applied"
    occurred = datetime.now(UTC) - timedelta(minutes=5)
    command = timer_command("start", timer, 1, occurred, activity_id=activity)
    command["start_policy"] = dict(
        target_seconds=initial_target,
        is_countdown=countdown,
        max_duration_seconds=initial_target * (1 if countdown else 3),
    )
    original = dict(device_id=device, commands=[command])
    started = await client.post(PATHS[4], headers=headers, json=original)
    assert started.status_code == 200, started.text
    result = started.json()["results"][0]
    assert result["status"] == "applied"
    changed = deepcopy(plan)
    changed["activity"].update(target_value=next_target, is_countdown=not countdown)
    assert (
        await push(
            client, headers, device, [operation(changed, identity=activity, revision=1)]
        )
    )[0]["status"] == "applied"
    active = await client.get(PATHS[5], headers=headers, params=dict(device_id=device))
    assert active.status_code == 200 and active.json()["session"] == result["session"]
    stopped = await client.post(
        PATHS[4],
        headers=headers,
        json=dict(
            device_id=device,
            commands=[
                timer_command(
                    "stop",
                    timer,
                    2,
                    occurred + timedelta(seconds=initial_target),
                    revision=1,
                    active_elapsed_ms=initial_target * 1000,
                )
            ],
        ),
    )
    assert stopped.status_code == 200, stopped.text
    completed = stopped.json()["results"][0]
    assert (
        completed["status"] == "applied"
        and completed["session"]["state"] == "completed"
    )
    assert completed["session"]["active_elapsed_ms"] == initial_target * 1000
    replay = await client.post(PATHS[4], headers=headers, json=original)
    assert replay.json()["results"][0] == {**result, "status": "already_applied"}
    next_command = timer_command(
        "start", str(uuid4()), 1, occurred + timedelta(minutes=3), activity_id=activity
    )
    next_command["start_policy"] = dict(
        target_seconds=next_target,
        is_countdown=not countdown,
        max_duration_seconds=next_target * (3 if countdown else 1),
    )
    next_result = await client.post(
        PATHS[4], headers=headers, json=dict(device_id=device, commands=[next_command])
    )
    assert next_result.status_code == 200, next_result.text
    next_session = next_result.json()["results"][0]
    assert (
        next_session["status"] == "applied"
        and next_session["session"]["target_seconds"] == next_target
    )
    assert next_session["session"]["is_countdown"] == (not countdown)
    async with engine.connect() as connection:
        assert (
            await connection.execute(text("SELECT COUNT(*) FROM activity_events"))
        ).scalar_one() == 1
        assert (
            await connection.execute(
                text("SELECT SUM(duration_ms) FROM duration_day_allocations")
            )
        ).scalar_one() == initial_target * 1000


async def test_malformed_start_policy_is_rejected_before_any_write(production_v5):
    client, engine, headers, device = production_v5
    activity = str(uuid4())
    assert (
        await push(
            client,
            headers,
            device,
            [operation(node(mode="duration"), identity=activity)],
        )
    )[0]["status"] == "applied"
    for policy in (
        dict(target_seconds=60, is_countdown=False, max_duration_seconds=181),
        dict(target_seconds="60", is_countdown=False, max_duration_seconds=180),
        dict(target_seconds=60, is_countdown="false", max_duration_seconds=180),
        dict(target_seconds=0, is_countdown=True, max_duration_seconds=86400),
    ):
        command = timer_command(
            "start", str(uuid4()), 1, datetime.now(UTC), activity_id=activity
        )
        command["start_policy"] = policy
        before = await database_state(engine)
        response = await client.post(
            PATHS[4], headers=headers, json=dict(device_id=device, commands=[command])
        )
        assert response.status_code == 422, response.text
        assert await database_state(engine) == before
    command = timer_command("cancel", str(uuid4()), 2, datetime.now(UTC), revision=1)
    command["start_policy"] = dict(
        target_seconds=60, is_countdown=False, max_duration_seconds=180
    )
    before = await database_state(engine)
    response = await client.post(
        PATHS[4], headers=headers, json=dict(device_id=device, commands=[command])
    )
    assert response.status_code == 422 and await database_state(engine) == before


async def test_v5_response_validation_cannot_fallback_to_v4_or_commit_cursor(
    production_v5, monkeypatch
):
    import src.v2.router as routing

    client, engine, headers, device = production_v5
    original = routing.bootstrap

    async def broken(*args, **kwargs):
        response = await original(*args, **kwargs)
        wire = response.model_dump(mode="json")
        del wire["one_time_checkpoints"]
        return SimpleNamespace(model_dump=lambda **_unused: wire)

    monkeypatch.setattr(routing, "bootstrap", broken)
    before = await database_state(engine)
    response = await client.get(
        PATHS[2], headers=headers, params=dict(device_id=device)
    )
    assert response.status_code == 500
    assert await database_state(engine) == before


async def test_v4_and_v5_page_limits_do_not_change_legacy_acceptance(production_v5):
    client, engine, headers, device = production_v5
    for limit, status in ((500, 200), (1000, 200), (1001, 422)):
        response = await client.get(
            PATHS[3], headers=headers, params=dict(device_id=device, limit=limit)
        )
        assert response.status_code == status, response.text
    async with engine.begin() as connection:
        await connection.execute(text("UPDATE server_instances SET protocol_version=4"))
    legacy = {
        name: value
        for name, value in headers.items()
        if name not in {PROTOCOL_HEADER, INSTANCE_HEADER, EPOCH_HEADER}
    }
    for limit, status in ((500, 200), (501, 422), (1000, 422)):
        before = await database_state(engine)
        response = await client.get(
            PATHS[3], headers=legacy, params=dict(device_id=device, limit=limit)
        )
        assert response.status_code == status, response.text
        if status == 422:
            assert response.json()["detail"][0]["loc"] == ["query", "limit"]
            assert await database_state(engine) == before


def test_current_openapi_honestly_marks_all_guarded_v5_alternatives():
    paths = app.openapi()["paths"]
    for path in PATHS:
        canonical = path.replace(SESSION, "{session_id}")
        method = "post" if path in {PATHS[0], PATHS[1], PATHS[4], PATHS[-1]} else "get"
        operation_schema = paths[canonical][method]
        alternate = operation_schema["x-dayforge-protocol-5"]
        assert alternate["requires_actual_server_version"] == 5
        assert alternate["required_headers"] == [
            PROTOCOL_HEADER,
            INSTANCE_HEADER,
            EPOCH_HEADER,
        ]
    assert paths[PATHS[1]]["post"]["requestBody"]["content"]["application/json"][
        "schema"
    ] == {"$ref": "#/components/schemas/SyncPushRequest"}


async def test_actual_sync_covers_normal_habits_counts_metrics_links_and_tombstones(
    production_v5,
):
    client, engine, headers, device = production_v5
    goal_id, check, up, down, metric_id, link = [str(uuid4()) for _ in range(6)]
    operations = [operation(goal(), identity=goal_id)]
    for identity, mode, countdown in (
        (check, "check", False),
        (up, "count", False),
        (down, "count", True),
    ):
        operations.append(
            operation(
                node(mode=mode, countdown=countdown, title=f"{mode}-{countdown}")
                | {"parent_uuid": goal_id},
                identity=identity,
            )
        )
    operations.extend(
        [
            operation(metric(), identity=metric_id, kind="metric"),
            operation(
                dict(
                    activity_uuid=check,
                    metric_uuid=metric_id,
                    coefficient="1",
                    prompt_on_complete=True,
                    show_in_activity_detail=True,
                    is_active=True,
                ),
                identity=link,
                kind="activity_metric_link",
            ),
        ]
    )
    for identity, mode in (
        (check, "check_in"),
        (up, "count_delta"),
        (down, "count_delta"),
    ):
        payload = dict(
            activity_uuid=identity,
            event_type=mode,
            occurred_at="2026-09-23T16:30:00.123456Z",
            local_date="2026-09-24",
            timezone="Asia/Shanghai",
        )
        if mode == "count_delta":
            payload["value"] = "2"
        operations.append(
            operation(payload, identity=str(uuid4()), kind="activity_event")
        )
    operations.append(
        operation(
            dict(
                metric_uuid=metric_id,
                value="60.5",
                unit="kg",
                occurred_at="2026-01-01T07:30:00Z",
                local_date="2025-12-31",
                timezone="America/Los_Angeles",
            ),
            identity=str(uuid4()),
            kind="metric_observation",
        )
    )
    first = await push(client, headers, device, operations)
    assert [item["status"] for item in first] == ["applied"] * len(operations), first
    replay = await push(client, headers, device, operations)
    assert replay == [{**item, "status": "already_applied"} for item in first]
    snapshot = await client.get(
        PATHS[2], headers=headers, params=dict(device_id=device)
    )
    assert snapshot.status_code == 200, snapshot.text
    NextSyncBootstrapResponse.model_validate(snapshot.json())
    changes = {item["entity_uuid"]: item for item in snapshot.json()["changes"]}
    assert changes[down]["payload"]["activity"]["is_countdown"] is True
    assert changes[up]["payload"]["activity"]["is_countdown"] is False
    assert changes[link]["payload"]["prompt_on_complete"] is True
    assert len(changes) == len(operations)
    deletion = operation(
        dict(child_policy="cascade_children"),
        identity=goal_id,
        action="delete",
        revision=1,
    )
    assert (await push(client, headers, device, [deletion]))[0]["status"] == "applied"
    pulled = await client.get(
        PATHS[3],
        headers=headers,
        params=dict(device_id=device, cursor=snapshot.json()["next_cursor"]),
    )
    assert pulled.status_code == 200, pulled.text
    NextSyncPullResponse.model_validate(pulled.json())
    tombstones = {
        item["entity_uuid"]
        for item in pulled.json()["changes"]
        if item["operation"] == "delete"
    }
    assert {goal_id, check, up, down} <= tombstones
    after = await client.get(PATHS[2], headers=headers, params=dict(device_id=device))
    assert after.status_code == 200
    NextSyncBootstrapResponse.model_validate(after.json())
    assert all(
        item["entity_type"]
        not in {"plan_node", "activity_event", "activity_metric_link"}
        for item in after.json()["changes"]
    )
    async with engine.connect() as connection:
        assert (
            await connection.execute(text("SELECT COUNT(*) FROM activity_events"))
        ).scalar_one() == 3


async def test_secondary_device_cannot_write_structure_but_can_complete_retained_task(
    production_v5,
):
    client, engine, headers, primary = production_v5
    assert (await push(client, headers, primary, [operation(node(once=True))]))[0][
        "status"
    ] == "applied"
    registration_response = await client.post(
        PATHS[0], headers=headers, json=registration(installation="secondary-v5-device")
    )
    assert registration_response.status_code == 200
    secondary = registration_response.json()["device_id"]
    assert "structure.write" not in registration_response.json()["capabilities"]
    refused = (
        await push(
            client,
            headers,
            secondary,
            [operation(metric(), identity=str(uuid4()), kind="metric")],
        )
    )[0]
    assert refused["error_code"] == "DEVICE_CAPABILITY_DENIED"
    completed = (
        await push(
            client, headers, secondary, [task_operation(0).model_dump(mode="json")]
        )
    )[0]
    assert completed["status"] == "applied"
    snapshot = await client.get(
        PATHS[2], headers=headers, params=dict(device_id=secondary)
    )
    assert snapshot.status_code == 200
    assert (
        snapshot.json()["one_time_checkpoints"][0]["state"]["completion_event_uuid"]
        == EVENTS[0]
    )
    async with engine.connect() as connection:
        assert (
            await connection.execute(text("SELECT deleted_at FROM plan_nodes"))
        ).scalar_one() is None


async def test_actual_accounts_can_share_public_ids_without_sharing_data_or_devices(
    production_v5,
):
    client, engine, headers, device = production_v5
    factory = async_sessionmaker(engine, expire_on_commit=False)
    async with factory.begin() as session:
        foreign = User(
            username="other-v5-account", password_hash=account_password_hash()
        )
        session.add(foreign)
        await session.flush()
        foreign_auth = "Bearer " + create_access_token(
            {"sub": str(foreign.id), "ver": foreign.auth_version}
        )
    foreign_headers = headers | {"Authorization": foreign_auth}
    registered = await client.post(
        PATHS[0], headers=foreign_headers, json=registration()
    )
    assert registered.status_code == 200
    foreign_device = registered.json()["device_id"]
    assert foreign_device != device
    metric_id = str(uuid4())
    for authorization, target, label in (
        (headers, device, "private-one"),
        (foreign_headers, foreign_device, "private-two"),
    ):
        written = await push(
            client,
            authorization,
            target,
            [
                operation(node(once=True, title=label)),
                operation(
                    metric() | {"name": label}, identity=metric_id, kind="metric"
                ),
                task_operation(0).model_dump(mode="json"),
            ],
        )
        assert [item["status"] for item in written] == ["applied"] * 3
        recovered = await client.get(
            PATHS[2], headers=authorization, params=dict(device_id=target)
        )
        assert recovered.status_code == 200
        NextSyncBootstrapResponse.model_validate(recovered.json())
        assert len(recovered.json()["changes"]) == 3
        assert label in recovered.text
        assert (
            "private-two" if label == "private-one" else "private-one"
        ) not in recovered.text
    for path in PATHS[1:]:
        before = await database_state(engine)
        rejected = await call_path(client, path, headers, foreign_device)
        assert rejected.status_code == 404
        assert await database_state(engine) == before


async def test_actual_epoch_transition_does_not_relabel_or_reexecute_receipt(
    production_v5,
):
    client, engine, headers, device = production_v5
    queued = operation(metric(), identity=str(uuid4()), kind="metric")
    frozen = deepcopy(queued)
    first = (await push(client, headers, device, [queued]))[0]
    assert first["status"] == "applied"
    async with engine.connect() as connection:
        receipts = (
            await connection.execute(text("SELECT * FROM sync_operations ORDER BY id"))
        ).all()
    new_epoch = str(uuid4())
    async with engine.begin() as connection:
        await connection.execute(
            text("UPDATE server_instances SET sync_epoch=:epoch"), {"epoch": new_epoch}
        )
    before = await database_state(engine)
    old = await client.post(
        PATHS[1], headers=headers, json=dict(device_id=device, operations=[queued])
    )
    assert (
        old.status_code == 409 and old.json()["detail"]["code"] == "SYNC_EPOCH_MISMATCH"
    )
    assert await database_state(engine) == before
    retargeted = (
        await push(client, headers | {EPOCH_HEADER: new_epoch}, device, [queued])
    )[0]
    assert (
        retargeted["error_code"] == "OPERATION_ID_REUSED"
        and retargeted["entity"] is None
    )
    assert queued == frozen
    async with engine.connect() as connection:
        assert (
            await connection.execute(text("SELECT * FROM sync_operations ORDER BY id"))
        ).all() == receipts
        assert (
            await connection.execute(text("SELECT COUNT(*) FROM tracked_metrics"))
        ).scalar_one() == 1


@pytest.mark.parametrize("fault", ["legacy-metric", "legacy-activity"])
async def test_pull_cannot_publish_partial_v4_snapshot_as_v5_or_commit_cursor(
    production_v5, fault
):
    client, engine, headers, device = production_v5
    original = metric() if fault == "legacy-metric" else node()
    kind = "metric" if fault == "legacy-metric" else "plan_node"
    assert (
        await push(
            client,
            headers,
            device,
            [operation(original, identity=str(uuid4()), kind=kind)],
        )
    )[0]["status"] == "applied"
    async with engine.begin() as connection:
        record = (
            await connection.execute(
                text("SELECT sequence,payload_json FROM sync_changes")
            )
        ).one()
        damaged = json.loads(record.payload_json)
        del damaged["appearance"]
        if fault == "legacy-activity":
            del damaged["activity"]["completion_policy"]
        await connection.execute(
            text(
                "UPDATE sync_changes SET payload_json=:payload WHERE sequence=:sequence"
            ),
            {"payload": json.dumps(damaged), "sequence": record.sequence},
        )
    before = await database_state(engine)
    response = await client.get(
        PATHS[3], headers=headers, params=dict(device_id=device)
    )
    assert response.status_code == 500
    assert await database_state(engine) == before


@pytest.mark.parametrize("path", PATHS[:5])
async def test_epoch_writer_after_admission_cannot_acknowledge_a_stale_snapshot(
    production_v5, monkeypatch, path
):
    import src.v2.protocol_admission as admission

    client, engine, headers, device = production_v5
    real_gate = admission.require_next_replica
    new_epoch = str(uuid4())
    outcome: list[str] = []
    committed_state = None

    async def concurrent_epoch(*args, **kwargs):
        nonlocal committed_state
        captured = await real_gate(*args, **kwargs)
        if not outcome:
            try:
                # An actual independent connection, not an injected COMMIT error.
                # JWT starts read-only; API-token usage already holds a write lock.
                async with engine.begin() as writer:
                    await writer.execute(
                        text("UPDATE server_instances SET sync_epoch=:epoch"),
                        {"epoch": new_epoch},
                    )
            except OperationalError as error:
                assert is_sqlite_busy(error)
                outcome.append("writer-blocked")
            else:
                outcome.append("writer-committed")
                committed_state = await database_state(engine)
        return captured

    monkeypatch.setattr(admission, "require_next_replica", concurrent_epoch)
    response = await call_path(client, path, headers, device)
    if headers["Authorization"].startswith("Bearer "):
        assert outcome == ["writer-committed"]
        assert (
            response.status_code == 503
            and response.json()["detail"]["code"] == "DATABASE_BUSY"
        )
        assert await database_state(engine) == committed_state
    else:
        assert outcome == ["writer-blocked"]
        # The protected snapshot is still current, so the admitted request may
        # finish. Only after its COMMIT may the external writer rotate the epoch.
        assert response.status_code == 200, response.text
        async with engine.begin() as writer:
            await writer.execute(
                text("UPDATE server_instances SET sync_epoch=:epoch"),
                {"epoch": new_epoch},
            )
    before = await database_state(engine)
    stale = await call_path(client, path, headers, device)
    assert (
        stale.status_code == 409
        and stale.json()["detail"]["code"] == "SYNC_EPOCH_MISMATCH"
    )
    assert await database_state(engine) == before
