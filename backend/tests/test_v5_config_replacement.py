"""Frozen six-phase replacement against migrated production routes and real TCP.

No configuration endpoint or direct history insertion is used: clients replace
structure through original sync operations, and timers through complete commands.
"""

from copy import deepcopy
from datetime import UTC, datetime, timedelta
import sqlite3
from typing import Any
from uuid import uuid4

from httpx import AsyncClient
from sqlalchemy import text

from src.v2.next_sync_contract import NextSyncBootstrapResponse, NextSyncPullResponse
from src.v2.replica_context import EPOCH_HEADER, INSTANCE_HEADER, PROTOCOL_HEADER
from tests.test_http_commit_boundary import database_state, runtime_http as runtime_http
from tests.test_jwt_runtime_server import isolated_server
from tests.test_next_structural_sync import goal, metric, node, operation
from tests.test_one_time_mutations import operation as task_operation
from tests.test_one_time_storage import ACTIVITY
from tests.test_timer_sync import timer_command
from tests.test_v5_production_sync import (
    production_v5 as production_v5,
    push,
    registration,
)


async def snapshot(client, headers, device):
    response = await client.get(
        "/api/v2/sync/bootstrap", headers=headers, params=dict(device_id=device)
    )
    assert response.status_code == 200, response.text
    NextSyncBootstrapResponse.model_validate(response.json())
    return response.json()


async def applied(client, headers, device, operations):
    results = await push(client, headers, device, operations)
    assert [item["status"] for item in results] == ["applied"] * len(operations), (
        results
    )
    return results


async def seed_history(client, headers, device):
    ids = {
        name: str(uuid4())
        for name in (
            "goal",
            "check",
            "up",
            "down",
            "time_up",
            "time_down",
            "metric",
            "link",
        )
    }
    ids["once"] = ACTIVITY
    payloads = {"goal": goal(), "metric": metric()}
    for name, mode, countdown in (
        ("check", "check", False),
        ("up", "count", False),
        ("down", "count", True),
        ("time_up", "duration", False),
        ("time_down", "duration", True),
        ("once", "check", False),
    ):
        payloads[name] = node(
            once=name == "once", mode=mode, countdown=countdown, title=name
        ) | {"parent_uuid": ids["goal"]}
    payloads["link"] = dict(
        activity_uuid=ids["check"],
        metric_uuid=ids["metric"],
        coefficient="1",
        prompt_on_complete=True,
        show_in_activity_detail=True,
        is_active=True,
    )
    kinds = {
        name: "metric"
        if name == "metric"
        else "activity_metric_link"
        if name == "link"
        else "plan_node"
        for name in ids
    }
    await applied(
        client,
        headers,
        device,
        [
            operation(
                payloads[name], identity=ids[name], kind=kinds[name], revision=None
            )
            for name in payloads
        ],
    )
    facts = []
    for name in ("check", "up", "down"):
        body: dict[str, Any] = dict(
            activity_uuid=ids[name],
            event_type="check_in" if name == "check" else "count_delta",
            occurred_at="2026-09-23T16:30:00.123456Z",
            local_date="2026-09-24",
            timezone="Asia/Shanghai",
        )
        if name != "check":
            body.update(
                value="2",
                count_policy=dict(target_value=1, is_countdown=name == "down"),
            )
        facts.append(
            operation(body, identity=str(uuid4()), kind="activity_event", revision=None)
        )
    facts.extend(
        [
            task_operation().model_dump(mode="json"),
            operation(
                dict(
                    metric_uuid=ids["metric"],
                    value="60.5",
                    unit="kg",
                    occurred_at="2026-01-01T07:30:00Z",
                    local_date="2025-12-31",
                    timezone="America/Los_Angeles",
                ),
                identity=str(uuid4()),
                kind="metric_observation",
                revision=None,
            ),
        ]
    )
    receipts = await applied(client, headers, device, facts)
    start = datetime.now(UTC) - timedelta(minutes=5)
    for name in ("time_up", "time_down"):
        timer = str(uuid4())
        commands = [
            timer_command("start", timer, 1, start, activity_id=ids[name]),
            timer_command(
                "pause",
                timer,
                2,
                start + timedelta(seconds=30),
                revision=1,
                active_elapsed_ms=30000,
            ),
            timer_command(
                "resume", timer, 3, start + timedelta(seconds=60), revision=2
            ),
            timer_command(
                "stop",
                timer,
                4,
                start + timedelta(seconds=90),
                revision=3,
                active_elapsed_ms=60000,
            ),
        ]
        countdown = name == "time_down"
        commands[0]["start_policy"] = dict(
            target_seconds=60,
            is_countdown=countdown,
            max_duration_seconds=60 if countdown else 180,
        )
        response = await client.post(
            "/api/v2/timers/commands",
            headers=headers,
            json=dict(device_id=device, commands=commands),
        )
        assert response.status_code == 200, response.text
        results = response.json()["results"]
        assert [item["status"] for item in results] == ["applied"] * 4, results
        assert results[-1]["session"]["state"] == "completed"
        assert results[-1]["session"]["active_elapsed_ms"] == 60000
        replay = await client.post(
            "/api/v2/timers/commands",
            headers=headers,
            json=dict(device_id=device, commands=commands),
        )
        assert replay.status_code == 200
        assert replay.json()["results"] == [
            {**item, "status": "already_applied"} for item in results
        ]
    before = await snapshot(client, headers, device)
    changes = {item["entity_uuid"]: item for item in before["changes"]}
    history = {
        key: value
        for key, value in changes.items()
        if value["entity_type"] in {"activity_event", "metric_observation"}
    }
    assert len(history) == 7
    assert before["one_time_checkpoints"][0]["state"]["version"] == 1
    assert (
        before["one_time_checkpoints"][0]["state"]["completion_event_uuid"] is not None
    )
    return ids, payloads, kinds, facts, receipts, changes, history


async def replace_history(client, headers, device):
    old, payloads, kinds, facts, receipts, changes, history = await seed_history(
        client, headers, device
    )
    new = {name: str(uuid4()) for name in old}
    replacement = deepcopy(payloads)
    for name in old:
        if name not in {"goal", "metric", "link"}:
            replacement[name]["parent_uuid"] = new["goal"]
    replacement["link"].update(activity_uuid=new["check"], metric_uuid=new["metric"])

    def deletion(name):
        return operation(
            dict(child_policy="cascade_children") if name == "goal" else {},
            identity=old[name],
            kind=kinds[name],
            revision=changes[old[name]]["revision"],
            action="delete",
        )

    def creation(name):
        return operation(
            replacement[name], identity=new[name], kind=kinds[name], revision=None
        )

    # Freeze all original IDs before delivery, exactly as the Android journal does.
    phases = [
        [deletion("link")],
        [deletion(name) for name in old if name not in {"goal", "link"}],
        [deletion("goal")],
        [creation("metric"), creation("goal")],
        [creation(name) for name in old if name not in {"goal", "metric", "link"}],
        [creation("link")],
    ]
    for phase in phases:
        first = await applied(client, headers, device, phase)
        # A lost HTTP response must retry these same operations, not a second deletion/create.
        assert await push(client, headers, device, phase) == [
            {**item, "status": "already_applied"} for item in first
        ]
    assert await push(client, headers, device, facts) == [
        {**item, "status": "already_applied"} for item in receipts
    ]
    after = await snapshot(client, headers, device)
    assert {item["entity_uuid"] for item in after["changes"]} == set(new.values())
    assert not any(
        item["entity_type"] in {"activity_event", "metric_observation"}
        for item in after["changes"]
    )
    checkpoint = after["one_time_checkpoints"]
    assert len(checkpoint) == 1 and checkpoint[0]["activity_uuid"] == new["once"]
    assert checkpoint[0]["state"]["version"] == 0
    assert checkpoint[0]["state"]["head_event_uuid"] is None
    assert checkpoint[0]["state"]["completion_event_uuid"] is None
    cursor, pulled = 0, []
    while True:
        response = await client.get(
            "/api/v2/sync/changes",
            headers=headers,
            params=dict(device_id=device, cursor=cursor),
        )
        assert response.status_code == 200, response.text
        page = response.json()
        NextSyncPullResponse.model_validate(page)
        pulled.extend(page["changes"])
        if not page["has_more"]:
            break
        assert page["next_cursor"] > cursor
        cursor = page["next_cursor"]
    # The append-only server log legitimately still returns old history. The
    # terminal must retain its accepted parent tombstones and not resurrect it.
    historical = {
        item["entity_uuid"]: item
        for item in pulled
        if item["entity_uuid"] in history and item["operation"] == "upsert"
    }
    assert set(historical) == set(history)
    assert all(
        historical[key]["payload"] == value["payload"] for key, value in history.items()
    )
    tombstones = {
        item["entity_uuid"] for item in pulled if item["operation"] == "delete"
    }
    assert set(old.values()) <= tombstones
    return old, new, history


async def test_nonempty_history_six_phases_and_exact_replays_never_inherit_old_facts(
    production_v5,
):
    client, engine, headers, device = production_v5
    old, new, _ = await replace_history(client, headers, device)
    async with engine.connect() as connection:
        plans = (
            await connection.execute(
                text("SELECT public_id,deleted_at FROM plan_nodes")
            )
        ).all()
        assert {row[0] for row in plans if row[1] is not None} == {
            value for name, value in old.items() if name not in {"metric", "link"}
        }
        assert {row[0] for row in plans if row[1] is None} == {
            value for name, value in new.items() if name not in {"metric", "link"}
        }
        assert (
            await connection.execute(text("SELECT COUNT(*) FROM activity_events"))
        ).scalar_one() == 6
        assert (
            await connection.execute(text("SELECT COUNT(*) FROM metric_observations"))
        ).scalar_one() == 1
        assert (
            await connection.execute(
                text("SELECT SUM(duration_ms) FROM duration_day_allocations")
            )
        ).scalar_one() == 120000


async def test_early_same_name_creation_and_fresh_fact_on_deleted_parent_are_not_retry_shortcuts(
    production_v5,
):
    client, _, headers, device = production_v5
    old = str(uuid4())
    create = operation(node(title="retained-name"), identity=old, revision=None)
    await applied(client, headers, device, [create])
    fresh = operation(node(title="retained-name"), identity=str(uuid4()), revision=None)
    rejected = (await push(client, headers, device, [fresh]))[0]
    assert (
        rejected["status"] == "rejected" and rejected["error_code"] == "DUPLICATE_TITLE"
    )
    await applied(
        client, headers, device, [operation(identity=old, revision=1, action="delete")]
    )
    # Rejected operations are also immutable idempotent outcomes. A legitimate
    # journal avoids the rejection by waiting for the old deletion acceptance.
    assert (await push(client, headers, device, [fresh]))[0] == rejected
    correct = operation(
        node(title="retained-name"), identity=str(uuid4()), revision=None
    )
    await applied(client, headers, device, [correct])
    fact = operation(
        dict(
            activity_uuid=old,
            event_type="check_in",
            occurred_at="2026-09-23T16:30:00Z",
            local_date="2026-09-24",
            timezone="Asia/Shanghai",
        ),
        identity=str(uuid4()),
        kind="activity_event",
        revision=None,
    )
    rejected_fact = (await push(client, headers, device, [fact]))[0]
    assert rejected_fact["status"] == "rejected"
    assert rejected_fact["error_code"] == "ENTITY_DELETED"
    assert (await push(client, headers, device, [fact]))[0] == rejected_fact
    current = await snapshot(client, headers, device)
    assert {item["entity_uuid"] for item in current["changes"]} == {
        correct["entity_uuid"]
    }


async def test_new_creation_final_commit_failure_keeps_previous_deletion_and_exact_id_retry(
    production_v5,
):
    client, engine, headers, device = production_v5
    old, new = str(uuid4()), str(uuid4())
    await applied(
        client,
        headers,
        device,
        [operation(node(title="same-name"), identity=old, revision=None)],
    )
    deletion = operation(identity=old, revision=1, action="delete")
    deleted = await applied(client, headers, device, [deletion])
    create = operation(node(title="same-name"), identity=new, revision=None)
    async with engine.begin() as connection:
        await connection.execute(
            text(
                "CREATE TABLE commit_fault (bad_user INTEGER REFERENCES users(id) DEFERRABLE INITIALLY DEFERRED)"
            )
        )
        await connection.execute(
            text(
                "CREATE TRIGGER replacement_commit_fault AFTER INSERT ON plan_nodes BEGIN INSERT INTO commit_fault VALUES (-999999); END"
            )
        )
    before = await database_state(engine)
    failed = await client.post(
        "/api/v2/sync/push",
        headers=headers,
        json=dict(device_id=device, operations=[create]),
    )
    assert (
        failed.status_code == 500
        and "applied" not in failed.text
        and "FOREIGN KEY" not in failed.text
    )
    assert await database_state(engine) == before
    async with engine.begin() as connection:
        await connection.execute(text("DROP TRIGGER replacement_commit_fault"))
    assert await push(client, headers, device, [deletion]) == [
        {**deleted[0], "status": "already_applied"}
    ]
    first = await applied(client, headers, device, [create])
    assert await push(client, headers, device, [create]) == [
        {**first[0], "status": "already_applied"}
    ]
    assert {
        item["entity_uuid"]
        for item in (await snapshot(client, headers, device))["changes"]
    } == {new}


async def test_real_tcp_server_nonempty_history_replacement(tmp_path):
    with isolated_server(tmp_path) as (server, _, password):
        account = server.login("jwt_acceptance_admin", password)
        # Only this fresh isolated acceptance DB activates the v5 production dispatch.
        database = tmp_path / "jwt-acceptance.sqlite"
        with sqlite3.connect(database) as connection:
            connection.execute("UPDATE server_instances SET protocol_version=5")
        async with AsyncClient(base_url=server.base_url) as client:
            identity = await client.get("/api/v2/system/identity")
            assert (
                identity.status_code == 200 and identity.json()["protocol_version"] == 5
            )
            headers = {
                "Authorization": "Bearer " + account["access_token"],
                PROTOCOL_HEADER: "5",
                INSTANCE_HEADER: identity.json()["server_instance_id"],
                EPOCH_HEADER: identity.json()["sync_epoch"],
            }
            registered = await client.post(
                "/api/v2/devices/register", headers=headers, json=registration()
            )
            assert registered.status_code == 200, registered.text
            old, new, _ = await replace_history(
                client, headers, registered.json()["device_id"]
            )
        # Independent SQLite connection after all HTTP responses, not an old writer's view.
        with sqlite3.connect(database) as connection:
            assert (
                connection.execute("SELECT COUNT(*) FROM activity_events").fetchone()[0]
                == 6
            )
            assert (
                connection.execute(
                    "SELECT COUNT(*) FROM metric_observations"
                ).fetchone()[0]
                == 1
            )
            assert (
                connection.execute(
                    "SELECT COUNT(*) FROM plan_nodes WHERE deleted_at IS NULL"
                ).fetchone()[0]
                == 7
            )
            assert (
                connection.execute(
                    "SELECT deleted_at FROM tracked_metrics WHERE public_id=?",
                    (old["metric"],),
                ).fetchone()[0]
                is not None
            )
            assert (
                connection.execute(
                    "SELECT deleted_at FROM tracked_metrics WHERE public_id=?",
                    (new["metric"],),
                ).fetchone()[0]
                is None
            )
