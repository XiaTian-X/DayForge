"""Stopped-server backups of real v5/challenge HTTP data and actual icon bytes.

Only accounts and the isolated protocol opt-in are seeded through SQLite. All
business facts, timer results, icon declarations and file readiness use actual
production TCP endpoints; every restore reopens an owned Uvicorn process.
"""

import base64
from contextlib import closing
from copy import deepcopy
from datetime import UTC, datetime, time, timedelta
import json
from pathlib import Path
import secrets
import sqlite3
from uuid import uuid4

from httpx import AsyncClient
import pytest
from sqlalchemy import create_engine, text
from sqlmodel import Session

from src.auth.models import User
from src.storage.logical_archive import export_archive, import_archive
from src.storage.sqlite_maintenance import (
    create_backup,
    inspect_database,
    restore_backup,
)
from src.v2.asset_api_contract import AssetSyncContext
from src.v2.challenge_round import initial_round_head
from src.v2.challenge_receipt_recovery import validate_challenge_receipts
from src.v2.challenge_sync_contract import RoundSyncPullResponse
from src.v2.errors import DomainError
from src.v2.replica_context import EPOCH_HEADER, INSTANCE_HEADER, PROTOCOL_HEADER
from src.v2.recovery_snapshot_contract import ReplicaRecoveryPreviewResponse
from tests.account_fixtures import TEST_ACCOUNT_PASSWORD, account_password_hash
from tests.asset_file_fixtures import SVG, blob
from tests.test_asset_declarations import ASSET, PACK, asset, pack
from tests.test_challenge_profile import (
    COMMANDS,
    PUSH,
    PULL,
    batch,
    call_push,
    fact,
    restart,
    snapshot,
    timer_batch,
)
from tests.test_jwt_runtime_server import isolated_server
from tests.test_logical_archive import migrate
from tests.test_logical_archive_identity import database_dump
from tests.test_next_structural_sync import goal, metric, node, operation
from tests.test_one_time_mutations import operation as task_operation
from tests.test_one_time_storage import ACTIVITY, EVENTS
from tests.test_timer_sync import timer_command
from tests.test_v5_production_sync import registration


TASK_ASSET = "a1000000-0000-4000-8000-000000000002"
PENDING_ASSET = "a1000000-0000-4000-8000-000000000003"
PENDING_BYTES = SVG.replace(b"<rect ", b'<rect fill="#123456" ')
ACCOUNTS = (
    (101, "jwt_acceptance_admin", "00000000-0000-4000-8000-000000000001"),
    (205, "restore-member", "00000000-0000-4000-8000-000000000002"),
    (3, "restore-isolated", "00000000-0000-4000-8000-000000000000"),
)


def source_accounts(database):
    url = migrate(database)
    engine = create_engine(url)
    try:
        with Session(engine) as session:
            for internal, name, public in ACCOUNTS:
                session.add(
                    User(
                        id=internal,
                        username=name,
                        public_id=public,
                        password_hash=account_password_hash(),
                        is_admin=internal == 101,
                    )
                )
            session.commit()
        with closing(sqlite3.connect(database)) as connection:
            connection.execute("UPDATE server_instances SET protocol_version=5")
            connection.commit()
    finally:
        engine.dispose()


async def authenticated(client, username, installation, *, original_device=None):
    logged_in = await client.post(
        "/api/v1/auth/login",
        json={
            "username": username,
            "password": TEST_ACCOUNT_PASSWORD,
        },
    )
    assert logged_in.status_code == 200, logged_in.text
    tokens = logged_in.json()
    discovered = await client.get("/api/v2/system/identity")
    assert discovered.status_code == 200 and discovered.json()["protocol_version"] == 5
    identity = discovered.json()
    headers = {
        "Authorization": "Bearer " + tokens["access_token"],
        PROTOCOL_HEADER: "5",
        INSTANCE_HEADER: identity["server_instance_id"],
        EPOCH_HEADER: identity["sync_epoch"],
    }
    registered = await client.post(
        "/api/v2/devices/register",
        headers=headers,
        json=registration(installation=installation),
    )
    assert registered.status_code == 200, registered.text
    device = registered.json()["device_id"]
    if original_device is not None:
        assert device == original_device
    return tokens, headers, device


def projection(state):
    # Transport cursors reset on logical import by design. Compare every public
    # object field and every immutable proof, independent only of list order.
    return {
        "changes": {
            (item["entity_type"], item["entity_uuid"]): item
            for item in state["changes"]
        },
        "checkpoints": {
            item["head"]["activity_uuid"]: item for item in state["checkpoints"]
        },
        "births": {
            (item["entity_type"], item["entity_uuid"]): item for item in state["births"]
        },
        "one_time": {
            item["activity_uuid"]: item for item in state["one_time_checkpoints"]
        },
    }


def context(headers, device):
    return AssetSyncContext(
        server_instance_id=headers[INSTANCE_HEADER],
        sync_epoch=headers[EPOCH_HEADER],
        device_id=device,
    )


async def appearance_state(client, headers, device):
    query = context(headers, device).model_dump() | {"limit": 100}
    catalog = await client.get(
        "/api/v2/appearance/catalog", params=query, headers=headers
    )
    quota = await client.get(
        "/api/v2/appearance/quota",
        params=context(headers, device).model_dump(),
        headers=headers,
    )
    assert catalog.status_code == quota.status_code == 200
    assert not catalog.json()["has_more"]
    return catalog.json()["entries"], {
        key: value for key, value in quota.json().items() if key != "context"
    }


async def install_appearance(client, headers, device, index, png):
    light, dark = (SVG, png) if index == 0 else (png, SVG)
    descriptions = []
    for identity, purpose, variants in (
        (ASSET, "general", {"light": light, "dark": dark}),
        (TASK_ASSET, "task", {"light": SVG}),
        (PENDING_ASSET, "general", {"light": PENDING_BYTES}),
    ):
        declaration = asset(
            context(headers, device),
            asset_id=identity,
            purpose=purpose,
            name=f"owner-{index}-{purpose}-{identity[-1]}",
            light=blob(
                variants["light"],
                "image/png" if variants["light"] == png else "image/svg+xml",
            ),
            dark=blob(dark, "image/png" if dark == png else "image/svg+xml")
            if identity == ASSET
            else None,
        )
        declared = await client.put(
            f"/api/v2/appearance/assets/{identity}",
            headers=headers,
            json=declaration.model_dump(mode="json"),
        )
        assert declared.status_code == 200, declared.text
        if identity != PENDING_ASSET:
            for variant, data in variants.items():
                uploaded = await client.put(
                    f"/api/v2/appearance/assets/{identity}/content/{variant}",
                    params=context(headers, device).model_dump(),
                    headers=headers | {"Content-Type": "application/octet-stream"},
                    content=data,
                )
                assert uploaded.status_code == 200, uploaded.text
                assert uploaded.json()["blob"] == blob(
                    data, "image/png" if data == png else "image/svg+xml"
                ).model_dump(mode="json")
        descriptions.append(declaration)
    for revision in (1, 2):
        declared_pack = pack(
            context(headers, device), descriptions[:2], revision=revision
        ).model_dump(mode="json")
        declared_pack["pack"]["roles"].update(
            {"goal.focus": ASSET, "metric.weight": ASSET, "task.check": TASK_ASSET}
        )
        accepted = await client.put(
            f"/api/v2/appearance/packs/{PACK}/versions/{revision}",
            headers=headers,
            json=declared_pack,
        )
        assert accepted.status_code == 200, accepted.text
        assert accepted.json() == declared_pack
    return {(ASSET, "light"): light, (ASSET, "dark"): dark, (TASK_ASSET, "light"): SVG}


async def accepted(client, headers, body):
    response = await call_push(client, headers, body)
    assert [item["status"] for item in response["results"]] == ["applied"] * len(
        body["operations"]
    ), response
    repeated = await call_push(client, headers, body)
    assert repeated["results"] == [
        {**item, "status": "already_applied"} for item in response["results"]
    ]
    return response


async def complete_timer(
    client, headers, device, activity, head, countdown, start, *, session_id=None
):
    session = session_id if session_id is not None else str(uuid4())
    first = timer_command("start", session, 1, start, activity_id=activity)
    first["start_policy"] = dict(
        target_seconds=60,
        is_countdown=countdown,
        max_duration_seconds=60 if countdown else 180,
    )
    commands = [
        first,
        timer_command(
            "pause",
            session,
            2,
            start + timedelta(seconds=20),
            revision=1,
            active_elapsed_ms=20000,
        ),
        timer_command("resume", session, 3, start + timedelta(seconds=30), revision=2),
        timer_command(
            "stop",
            session,
            4,
            start + timedelta(seconds=70),
            revision=3,
            active_elapsed_ms=60000,
        ),
    ]
    body = timer_batch(device, commands, head)
    response = await client.post(COMMANDS, headers=headers, json=body)
    assert response.status_code == 200, response.text
    results = response.json()["results"]
    assert [item["status"] for item in results] == ["applied"] * 4, results
    assert results[-1]["session"]["state"] == "completed"
    assert results[-1]["session"]["active_elapsed_ms"] == 60000
    repeated = await client.post(COMMANDS, headers=headers, json=body)
    assert repeated.status_code == 200
    assert repeated.json()["results"] == [
        {**item, "status": "already_applied"} for item in results
    ]
    return body, session


async def product_history(client, headers, device, ids, name):
    initial = {
        key: initial_round_head(ids[key]).model_dump(mode="json")
        for key in ("check", "up", "down", "time_up", "time_down")
    }
    payloads = {"goal": goal(), "metric": metric()}
    payloads["metric"]["name"] = name + "-metric"
    payloads["metric"]["appearance"]["icon"] = dict(kind="asset", asset_id=ASSET)
    kinds = {"metric": "metric", "link": "activity_metric_link"}
    for key, mode, countdown in (
        ("check", "check", False),
        ("up", "count", False),
        ("down", "count", True),
        ("time_up", "duration", False),
        ("time_down", "duration", True),
        ("once", "check", False),
    ):
        body = node(
            once=key == "once", mode=mode, countdown=countdown, title=name + "-" + key
        )
        body["parent_uuid"] = ids["goal"]
        body["appearance"]["icon"] = dict(
            kind="asset", asset_id=TASK_ASSET if key == "once" else ASSET
        )
        body["appearance"]["icon_tint"] = "theme"
        if key != "once":
            body["activity"]["target_cycles"] = 3
        payloads[key] = body
    payloads["link"] = dict(
        activity_uuid=ids["check"],
        metric_uuid=ids["metric"],
        coefficient="1",
        prompt_on_complete=True,
        show_in_activity_detail=True,
        is_active=True,
    )
    creation = [
        operation(
            body, identity=ids[key], kind=kinds.get(key, "plan_node"), revision=None
        )
        for key, body in payloads.items()
    ]
    heads = {
        item["operation_id"]: initial[key]
        for key, item in zip(payloads, creation)
        if key in initial
    }
    await accepted(client, headers, batch(device, creation, heads))
    frozen = []
    for key in ("check", "up", "down"):
        event = fact(
            ids[key],
            mode="check_in" if key == "check" else "count_delta",
            value=None if key == "check" else "2",
            countdown=key == "down",
        )
        body = batch(device, [event], {event["operation_id"]: initial[key]})
        await accepted(client, headers, body)
        frozen.append((PUSH, body))
    # Use full local-date CAS history; completed items remain visible and undoable.
    for index in range(3):
        await accepted(
            client,
            headers,
            batch(device, [task_operation(index).model_dump(mode="json")]),
        )
    observation = operation(
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
    )
    await accepted(client, headers, batch(device, [observation]))
    started = datetime.combine(
        (datetime.now(UTC) - timedelta(days=1)).date(),
        time(15, 59, 40, 123456),
        tzinfo=UTC,
    )
    timers = {}
    for key in ("time_up", "time_down"):
        body, timers[key] = await complete_timer(
            client,
            headers,
            device,
            ids[key],
            initial[key],
            key == "time_down",
            started,
            session_id=ids[key + "_session"],
        )
        frozen.append((COMMANDS, body))
    current = {}
    for key, old in initial.items():
        response = await accepted(
            client, headers, batch(device, [restart(ids[key], old, 1)])
        )
        current[key] = response["results"][0]["entity"]["head"]
    for key in ("check", "up", "down"):
        event = fact(
            ids[key],
            mode="check_in" if key == "check" else "count_delta",
            value=None if key == "check" else "2",
            countdown=key == "down",
        )
        await accepted(
            client,
            headers,
            batch(device, [event], {event["operation_id"]: current[key]}),
        )
    state = await snapshot(client, headers, device)
    projected = projection(state)
    assert len(projected["checkpoints"]) == 5
    assert all(
        item["head"]["generation"] == 1 and len(item["records"]) == 2
        for item in projected["checkpoints"].values()
    )
    assert projected["one_time"][ACTIVITY]["state"]["version"] == 3
    assert projected["one_time"][ACTIVITY]["state"]["completion_event_uuid"] is not None
    duration_facts = [
        item["payload"]
        for item in state["changes"]
        if item["entity_type"] == "activity_event"
        and item["payload"]["event_type"] == "duration_session"
    ]
    assert len(duration_facts) == 2
    for event in duration_facts:
        assert event["duration_milliseconds"] == 60000
        assert [item["duration_milliseconds"] for item in event["day_allocations"]] == [
            19876,
            40124,
        ]
        assert event["started_at"].endswith(".123456Z")
    assert (
        len(projected["births"]) == 10
    )  # 8 recurring facts + 2 original timer sessions
    return state, current, frozen, timers


async def stored_bytes(client, headers, device, expected):
    for (identity, variant), content in expected.items():
        response = await client.get(
            f"/api/v2/appearance/assets/{identity}/content/{variant}",
            params=context(headers, device).model_dump(),
            headers=headers,
        )
        assert response.status_code == 200, response.text
        assert response.content == content
        assert response.headers["cache-control"] == "private, no-store"
        assert response.headers["x-content-type-options"] == "nosniff"
    pending = await client.get(
        f"/api/v2/appearance/assets/{PENDING_ASSET}/content/light",
        params=context(headers, device).model_dump(),
        headers=headers,
    )
    assert (
        pending.status_code == 409
        and pending.json()["detail"]["code"] == "ASSET_CONTENT_PENDING"
    )


def require_owned_receipts(database, ids):
    engine = create_engine(f"sqlite:///{database}")
    try:
        with engine.connect() as connection:

            def read_rows(statement, parameters):
                return [
                    dict(row)
                    for row in connection.execute(
                        text(statement), parameters
                    ).mappings()
                ]

            validate_challenge_receipts(read_rows)
            for owner, _, _ in ACCOUNTS:
                validate_challenge_receipts(read_rows, owner_user_id=owner)
            for table, identity in (
                ("activity_events", EVENTS[0]),
                ("timer_sessions", ids["time_up_session"]),
            ):
                # Removing just the owned source from this read snapshot must
                # not let the valid SAME-UUID foreign source substitute for it.
                rows = read_rows(f"SELECT * FROM {table}", {})
                assert {
                    row["owner_user_id"] for row in rows if row["public_id"] == identity
                } == {101, 205}

                def incomplete(statement, parameters):
                    rows = read_rows(statement, parameters)
                    if statement == f"SELECT * FROM {table}":
                        return [
                            row
                            for row in rows
                            if (row["owner_user_id"], row["public_id"])
                            != (101, identity)
                        ]
                    return rows

                with pytest.raises(DomainError) as rejected:
                    validate_challenge_receipts(incomplete)
                assert rejected.value.code == "CHALLENGE_RECEIPT_INVALID"
    finally:
        engine.dispose()


@pytest.mark.parametrize("kind", ["physical", "logical"])
async def test_real_product_tcp_restore_and_continue_without_cross_account_or_replay_loss(
    tmp_path, kind
):
    source = tmp_path / "source.sqlite"
    source_accounts(source)
    root = tmp_path / "source-assets"
    root.mkdir()
    credentials = (secrets.token_urlsafe(48), TEST_ACCOUNT_PASSWORD)
    server_dir = tmp_path / "initial-server"
    server_dir.mkdir()
    cases = json.loads(
        (Path(__file__).resolve().parents[2] / "contracts/next/png.json").read_text()
    )
    png = base64.b64decode(
        next(case["png"] for case in cases if case["name"] == "rgba")
    )
    ids = {
        key: str(uuid4())
        for key in (
            "goal",
            "check",
            "up",
            "down",
            "time_up",
            "time_down",
            "metric",
            "link",
            "time_up_session",
            "time_down_session",
        )
    }
    ids["once"] = ACTIVITY
    saved = {}
    with isolated_server(
        server_dir, asset_root=root, database=source, credentials=credentials
    ) as (server, _, _):
        async with AsyncClient(base_url=server.base_url) as client:
            for index, (internal, name, public) in enumerate(ACCOUNTS):
                tokens, headers, device = await authenticated(
                    client, name, f"restore-device-{internal}"
                )
                assert tokens["user_id"] == public
                api = await client.post(
                    "/api/v1/auth/tokens",
                    headers=headers,
                    json={"name": "restore-joint-probe"},
                )
                assert api.status_code == 201, api.text
                record = dict(
                    tokens=tokens,
                    headers=headers,
                    device=device,
                    api=api.json()["token"],
                )
                if index < 2:
                    record["bytes"] = await install_appearance(
                        client, headers, device, index, png
                    )
                    (
                        record["state"],
                        record["heads"],
                        record["frozen"],
                        record["timers"],
                    ) = await product_history(client, headers, device, ids, name)
                    record["appearance"] = await appearance_state(
                        client, headers, device
                    )
                    await stored_bytes(client, headers, device, record["bytes"])
                else:
                    record["state"] = await snapshot(client, headers, device)
                    assert record["state"]["changes"] == []
                saved[internal] = record
    # The original service and all file workers are now gone; no online restore.
    require_owned_receipts(source, ids)
    source_inspection = inspect_database(source, asset_root=root)
    assert source_inspection.valid and source_inspection.active_timer_count == 0
    assert source_inspection.alembic_head is not None
    destination, destination_root = (
        tmp_path / "restored.sqlite",
        tmp_path / "restored-assets",
    )
    destination_root.mkdir()
    if kind == "physical":
        backup, _ = create_backup(
            source, tmp_path / "backups", kind="manual", asset_root=root
        )
        _, epoch = restore_backup(
            backup,
            destination,
            asset_root=destination_root,
            expected_alembic_head=source_inspection.alembic_head,
        )
    else:
        archive = export_archive(
            f"sqlite:///{source}", tmp_path / "archive.zip", asset_root=root
        )
        epoch = import_archive(
            migrate(destination), archive, asset_root=destination_root
        )
    assert epoch != source_inspection.sync_epoch
    assert inspect_database(destination, asset_root=destination_root).valid
    restored_server = tmp_path / "restored-server"
    restored_server.mkdir()
    with isolated_server(
        restored_server,
        asset_root=destination_root,
        database=destination,
        credentials=credentials,
    ) as (server, _, _):
        async with AsyncClient(base_url=server.base_url) as client:
            for index, (internal, name, public) in enumerate(ACCOUNTS):
                record = saved[internal]
                old_access = await client.get(
                    "/api/v1/auth/users/me", headers=record["headers"]
                )
                old_refresh = await client.post(
                    "/api/v1/auth/refresh",
                    json={"refresh_token": record["tokens"]["refresh_token"]},
                )
                assert old_access.status_code == old_refresh.status_code == 401
                _, fresh, device = await authenticated(
                    client,
                    name,
                    f"restore-device-{internal}",
                    original_device=record["device"],
                )
                assert fresh[INSTANCE_HEADER] == record["headers"][INSTANCE_HEADER]
                assert fresh[EPOCH_HEADER] == epoch
                for headers in (
                    fresh,
                    fresh | {"Authorization": "Token " + record["api"]},
                ):
                    me = await client.get("/api/v1/auth/users/me", headers=headers)
                    assert me.status_code == 200 and me.json()["public_id"] == public
                    assert me.json()["id"] == (
                        {101: 2, 205: 3, 3: 1}[internal]
                        if kind == "logical"
                        else internal
                    )
                    before_preview = database_dump(destination)
                    preview = await client.get(
                        "/api/v2/sync/rounds/recovery-preview",
                        headers=headers,
                        params=dict(
                            device_id=device,
                            installation_id=f"restore-device-{internal}",
                            challenge_contract="1",
                        ),
                    )
                    assert preview.status_code == 200, preview.text
                    assert preview.headers["cache-control"] == "private, no-store"
                    candidate = ReplicaRecoveryPreviewResponse.model_validate(
                        preview.json()
                    )
                    assert candidate.account_id == public
                    assert candidate.server_instance_id == fresh[INSTANCE_HEADER]
                    assert candidate.sync_epoch == epoch
                    assert candidate.active_timer is None
                    assert projection(preview.json()["snapshot"]) == projection(
                        record["state"]
                    )
                    assert database_dump(destination) == before_preview
                    state = await snapshot(client, headers, device)
                    assert projection(state) == projection(record["state"])
                    if kind == "logical":
                        assert state["next_cursor"] == 0
                    else:
                        assert state["next_cursor"] == record["state"]["next_cursor"]
                    if index < 2:
                        await stored_bytes(client, headers, device, record["bytes"])
                        assert (
                            await appearance_state(client, headers, device)
                            == record["appearance"]
                        )
                    else:
                        denied = await client.get(
                            f"/api/v2/appearance/assets/{ASSET}/content/light",
                            params=context(headers, device).model_dump(),
                            headers=headers,
                        )
                        assert denied.status_code == 404
                if index == 2:
                    continue
                frozen_copy = deepcopy(record["frozen"])
                for path, body in record["frozen"]:
                    denied = await client.post(
                        path,
                        headers=fresh | {EPOCH_HEADER: record["headers"][EPOCH_HEADER]},
                        json=body,
                    )
                    assert (
                        denied.status_code == 409
                        and denied.json()["detail"]["code"] == "SYNC_EPOCH_MISMATCH"
                    )
                    retargeted = await client.post(path, headers=fresh, json=body)
                    assert retargeted.status_code == 200, retargeted.text
                    expected = (
                        "COMMAND_ID_REUSED"
                        if path == COMMANDS
                        else "OPERATION_ID_REUSED"
                    )
                    assert all(
                        item["error_code"] == expected
                        for item in retargeted.json()["results"]
                    ), retargeted.text
                assert record["frozen"] == frozen_copy
                assert projection(await snapshot(client, fresh, device)) == projection(
                    record["state"]
                )
                # A fresh fact keeps its CURRENT birth; old history is not relabelled.
                checkpoint = next(
                    item
                    for item in record["state"]["checkpoints"]
                    if item["head"]["activity_uuid"] == ids["check"]
                )
                fresh_restart = restart(ids["check"], checkpoint["head"], 2)
                repeated = await accepted(client, fresh, batch(device, [fresh_restart]))
                assert repeated["results"][0]["entity"]["head"]["generation"] == 2
                today = fact(ids["check"])
                accepted_new = await accepted(
                    client,
                    fresh,
                    batch(
                        device,
                        [today],
                        {
                            today["operation_id"]: repeated["results"][0]["entity"][
                                "head"
                            ]
                        },
                    ),
                )
                assert accepted_new["births"][0]["head"]["generation"] == 2
                for key in ("time_up", "time_down"):
                    await complete_timer(
                        client,
                        fresh,
                        device,
                        ids[key],
                        record["heads"][key],
                        key == "time_down",
                        datetime.now(UTC) - timedelta(minutes=4),
                    )
                once = projection(record["state"])["one_time"][ACTIVITY]["state"]
                undo_uuid = str(uuid4())
                undo = operation(
                    dict(
                        activity_uuid=ACTIVITY,
                        event_type="revert",
                        occurred_at="2026-10-11T00:00:00Z",
                        local_date="2026-10-11",
                        timezone="UTC",
                        reverts_event_uuid=once["completion_event_uuid"],
                        one_time=dict(
                            event_uuid=undo_uuid,
                            action="undo",
                            expected_version=3,
                            expected_head_event_uuid=once["head_event_uuid"],
                            reverts_event_uuid=once["completion_event_uuid"],
                        ),
                    ),
                    identity=undo_uuid,
                    kind="activity_event",
                    revision=None,
                )
                await accepted(client, fresh, batch(device, [undo]))
                installed = await client.put(
                    f"/api/v2/appearance/assets/{PENDING_ASSET}/content/light",
                    params=context(fresh, device).model_dump(),
                    headers=fresh | {"Content-Type": "application/octet-stream"},
                    content=PENDING_BYTES,
                )
                assert installed.status_code == 200, installed.text
                after = projection(await snapshot(client, fresh, device))
                assert after["one_time"][ACTIVITY]["state"]["version"] == 4
                assert (
                    after["one_time"][ACTIVITY]["state"]["completion_event_uuid"]
                    is None
                )
                assert all(
                    identity in after["births"] and after["births"][identity] == birth
                    for identity, birth in projection(record["state"])["births"].items()
                )
                assert (
                    after["changes"][("plan_node", ACTIVITY)]["operation"] == "upsert"
                )
                page = await client.get(
                    PULL,
                    headers=fresh,
                    params=dict(
                        device_id=device,
                        challenge_contract="1",
                        cursor=record["state"]["next_cursor"]
                        if kind == "physical"
                        else 0,
                        limit=1000,
                    ),
                )
                assert page.status_code == 200, page.text
                RoundSyncPullResponse.model_validate(page.json())
                assert not page.json()["has_more"]
                assert page.json()["changes"] and all(
                    item["sequence"] > 0 for item in page.json()["changes"]
                )
    assert inspect_database(destination, asset_root=destination_root).valid
