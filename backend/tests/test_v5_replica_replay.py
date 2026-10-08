"""Captured replica and receipt boundaries; the bridge is NOT production v5."""

from copy import deepcopy
from dataclasses import FrozenInstanceError
from datetime import UTC, datetime, timedelta
import hashlib
import json
from pathlib import Path
from uuid import uuid4

from fastapi import Depends, FastAPI, Request
from httpx import ASGITransport, AsyncClient
import pytest
from sqlalchemy import event, text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from src.auth.dependencies import get_current_user
from src.auth.models import User
from src.database import get_session
from src.main import app
from src.v2.encoding import operation_hash, timer_command_hash
from src.v2.errors import DomainError
from src.v2.models import ServerInstance
from src.v2.next_sync_contract import (
    NextDeviceRegisterRequest,
    NextSyncPushRequest,
    NextSyncPushResponse,
)
from src.v2.protocol_admission import require_next_business, require_next_replica
from src.v2.replica_context import (
    EPOCH_HEADER,
    INSTANCE_HEADER,
    PROTOCOL_HEADER,
    ReplicaIdentity,
    replica_from_headers,
    verify_replica_identity,
    replay_replica,
)
from src.v2.router import _http_error
from src.v2.schemas import (
    SyncOperationRequest,
    TimerCommandBatchRequest,
    TimerCommandBatchResponse,
    TimerCommandRequest,
)
from src.v2.service import process_push
from src.v2.timer_service import process_timer_commands
from tests.test_http_commit_boundary import database_state, runtime_http as runtime_http
from tests.test_next_structural_sync import metric, node, operation
from tests.test_push_orchestration import hide_initial_operation_lookup
from tests.test_timer_sync import timer_command


ROOT = Path(__file__).resolve().parents[2]
VECTOR = json.loads((ROOT / "contracts/next/replica.json").read_text())
REPLICA = ReplicaIdentity(VECTOR["server_instance_id"], VECTOR["sync_epoch"])


@pytest.mark.parametrize("field", ["instance", "epoch"])
@pytest.mark.parametrize("values", VECTOR["invalid_header_values"])
def test_raw_replica_header_vectors_reject_without_normalization(field, values):
    instance = tuple(values) if field == "instance" else (REPLICA.server_instance_id,)
    epoch = tuple(values) if field == "epoch" else (REPLICA.sync_epoch,)
    with pytest.raises(DomainError) as error:
        replica_from_headers(instance, epoch)
    assert error.value.code == "INVALID_SYNC_CONTEXT"
    assert "81000000" not in error.value.message


def test_captured_identity_is_strict_immutable_and_does_not_accept_a_joined_header():
    assert (
        replica_from_headers((REPLICA.server_instance_id,), (REPLICA.sync_epoch,))
        == REPLICA
    )
    with pytest.raises(FrozenInstanceError):
        setattr(REPLICA, "sync_epoch", str(uuid4()))
    for value in VECTOR["invalid_header_containers"]:
        with pytest.raises(DomainError):
            replica_from_headers(value, (REPLICA.sync_epoch,))


@pytest.mark.parametrize("kind", ["sync_operation", "timer_command"])
def test_namespace_wire_is_explicit_and_v4_hashes_keep_the_exact_old_algorithm(kind):
    wire = VECTOR["operation" if kind == "sync_operation" else "timer_command"]
    parsed = (
        SyncOperationRequest.model_validate(wire)
        if kind == "sync_operation"
        else TimerCommandRequest.model_validate(wire)
    )

    def hash_value(replica: ReplicaIdentity | None = None) -> str:
        if isinstance(parsed, SyncOperationRequest):
            return operation_hash(parsed, replica=replica)
        return timer_command_hash(parsed, replica=replica)

    assert parsed.model_dump(mode="json") == wire

    def independent_hash(value):
        return hashlib.sha256(
            json.dumps(
                value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
            ).encode("utf-8")
        ).hexdigest()

    old = independent_hash(wire)
    assert hash_value() == old == VECTOR["digests"][kind]["v4"]
    expected = independent_hash(
        {
            "protocol_version": 5,
            "request_kind": kind,
            "server_instance_id": REPLICA.server_instance_id,
            "sync_epoch": REPLICA.sync_epoch,
            "payload": wire,
        }
    )
    assert hash_value(replica=REPLICA) == expected == VECTOR["digests"][kind]["v5"]
    assert expected != old
    for replica in (
        ReplicaIdentity(str(uuid4()), REPLICA.sync_epoch),
        ReplicaIdentity(REPLICA.server_instance_id, str(uuid4())),
    ):
        assert hash_value(replica=replica) not in {old, expected}
    assert wire == VECTOR["operation" if kind == "sync_operation" else "timer_command"]


@pytest.mark.parametrize("version", [None, 4, 6, True, "5", 5.0, 10**100])
def test_next_registration_requires_explicit_exact_integer(version):
    body = dict(installation_id="replica-register-test", platform="android")
    if version is not None:
        body["protocol_version"] = version
    with pytest.raises(ValueError):
        NextDeviceRegisterRequest.model_validate(body)


def test_valid_registration_does_not_default_or_change_device_shape():
    parsed = NextDeviceRegisterRequest.model_validate(
        dict(
            installation_id="replica-register-test",
            platform="android",
            protocol_version=5,
        )
    )
    assert (
        parsed.protocol_version == 5 and "protocol_version" in parsed.model_fields_set
    )
    assert parsed.model_dump(mode="json")["installation_id"] == "replica-register-test"


@pytest.fixture(params=["jwt", "api"])
async def replica_http(runtime_http, request):
    production, engine, bearer, api, device = runtime_http
    factory = async_sessionmaker(engine, expire_on_commit=False)
    async with factory.begin() as session:
        identity = await session.get(ServerInstance, 1)
        assert identity is not None
        replica = ReplicaIdentity(identity.instance_uuid, identity.sync_epoch)
        identity.protocol_version = 5
        await session.execute(
            text("UPDATE client_devices SET registered_protocol_version=5")
        )
    headers = dict(bearer if request.param == "jwt" else api)
    headers.update(
        {
            PROTOCOL_HEADER: "5",
            INSTANCE_HEADER: replica.server_instance_id,
            EPOCH_HEADER: replica.sync_epoch,
        }
    )
    bridge = FastAPI()
    bridge.exception_handlers.update(app.exception_handlers)

    async def admit(body, http, user, session):
        return await require_next_business(
            session,
            user,
            str(body.device_id),
            tuple(http.headers.getlist(PROTOCOL_HEADER)),
            tuple(http.headers.getlist(INSTANCE_HEADER)),
            tuple(http.headers.getlist(EPOCH_HEADER)),
        )

    @bridge.post("/__test/v5/push", response_model=NextSyncPushResponse)
    async def push(
        body: NextSyncPushRequest,
        http: Request,
        user: User = Depends(get_current_user),
        session: AsyncSession = Depends(get_session, scope="function"),
    ):
        try:
            _, captured = await admit(body, http, user, session)
            return await process_push(
                user, body, session, next_protocol=True, replica=captured
            )
        except DomainError as error:
            raise _http_error(error) from error

    @bridge.post("/__test/v5/timers", response_model=TimerCommandBatchResponse)
    async def timers(
        body: TimerCommandBatchRequest,
        http: Request,
        user: User = Depends(get_current_user),
        session: AsyncSession = Depends(get_session, scope="function"),
    ):
        try:
            _, captured = await admit(body, http, user, session)
            return await process_timer_commands(
                user, body, session, next_protocol=True, replica=captured
            )
        except DomainError as error:
            raise _http_error(error) from error

    async with AsyncClient(
        transport=ASGITransport(app=bridge, raise_app_exceptions=False),
        base_url="http://test",
    ) as client:
        yield client, production, engine, factory, headers, device, replica


def metric_request(device):
    return dict(
        device_id=device,
        operations=[operation(metric(), identity=str(uuid4()), kind="metric")],
    )


async def legacy_post(probe, path, body):
    """Exercise the unchanged v4 namespace ONLY with a v4 test server.

    Production now refuses legacy sync/timer requests on a v5 server. This
    isolated fixture switch is for receipt boundary proofs, not a supported
    deployment/downgrade or permission to modify any real server metadata.
    """
    _, production, engine, _, headers, _, _ = probe
    async with engine.begin() as connection:
        await connection.execute(text("UPDATE server_instances SET protocol_version=4"))
    legacy_headers = {
        name: value
        for name, value in headers.items()
        if name not in {PROTOCOL_HEADER, INSTANCE_HEADER, EPOCH_HEADER}
    }
    try:
        return await production.post(path, headers=legacy_headers, json=body)
    finally:
        async with engine.begin() as connection:
            await connection.execute(
                text("UPDATE server_instances SET protocol_version=5")
            )


@pytest.mark.parametrize("field", [INSTANCE_HEADER, EPOCH_HEADER])
@pytest.mark.parametrize(
    "case", ["missing", "duplicate", "comma", "whitespace", "changed"]
)
async def test_http_admission_rejects_context_before_any_receipt_or_business_mutation(
    replica_http, field, case
):
    client, _, engine, _, headers, device, _ = replica_http
    headers = list(headers.items())
    value = next(value for name, value in headers if name == field)
    headers = [(name, value) for name, value in headers if name != field]
    if case == "duplicate":
        headers.extend([(field, value), (field, value)])
    elif case != "missing":
        headers.append(
            (
                field,
                value + "," + value
                if case == "comma"
                else " " + value
                if case == "whitespace"
                else str(uuid4()),
            )
        )
    before = await database_state(engine)
    response = await client.post(
        "/__test/v5/push", headers=headers, json=metric_request(device)
    )
    assert response.status_code == (409 if case == "changed" else 400), response.text
    assert response.json()["detail"]["code"] == (
        "SERVER_IDENTITY_MISMATCH"
        if case == "changed" and field == INSTANCE_HEADER
        else "SYNC_EPOCH_MISMATCH"
        if case == "changed"
        else "INVALID_SYNC_CONTEXT"
    )
    assert await database_state(engine) == before


@pytest.mark.parametrize("kind", ["sync", "timer"])
async def test_discovery_then_restore_epoch_change_rejects_old_context_and_no_retarget(
    replica_http, kind
):
    client, _, engine, _, headers, device, _ = replica_http
    body = (
        metric_request(device)
        if kind == "sync"
        else dict(device_id=device, commands=[VECTOR["timer_command"]])
    )
    frozen = deepcopy(body)
    async with engine.begin() as connection:
        await connection.execute(
            text("UPDATE server_instances SET sync_epoch=:epoch"),
            {"epoch": str(uuid4())},
        )
    before = await database_state(engine)
    response = await client.post(
        "/__test/v5/push" if kind == "sync" else "/__test/v5/timers",
        headers=headers,
        json=body,
    )
    assert (
        response.status_code == 409
        and response.json()["detail"]["code"] == "SYNC_EPOCH_MISMATCH"
    )
    assert await database_state(engine) == before and body == frozen


@pytest.mark.parametrize("race", [False, True])
async def test_v4_success_receipt_is_not_a_v5_metric_receipt_even_with_identical_body(
    replica_http, monkeypatch, race
):
    client, production, engine, factory, headers, device, replica = replica_http
    body = dict(device_id=device, operations=[VECTOR["operation"]])
    old = await legacy_post(replica_http, "/api/v2/sync/push", body)
    assert old.status_code == 200 and old.json()["results"][0]["status"] == "applied"
    if not race:
        response = await client.post("/__test/v5/push", headers=headers, json=body)
        assert response.status_code == 200
        result = response.json()["results"][0]
    else:
        async with factory.begin() as session:
            hidden = hide_initial_operation_lookup(
                monkeypatch, session, VECTOR["operation"]["operation_id"]
            )
            user = await session.get(User, 1)
            assert user is not None
            response = await process_push(
                user,
                NextSyncPushRequest.model_validate(body),
                session,
                next_protocol=True,
                replica=replica,
            )
            result = response.results[0].model_dump(mode="json")
            assert hidden == [True]
    assert (
        result["status"] == "rejected" and result["error_code"] == "OPERATION_ID_REUSED"
    )
    assert result["entity"] is None
    replay = await legacy_post(replica_http, "/api/v2/sync/push", body)
    assert replay.json()["results"][0] == {
        **old.json()["results"][0],
        "status": "already_applied",
    }
    async with engine.connect() as connection:
        assert (
            await connection.execute(text("SELECT COUNT(*) FROM tracked_metrics"))
        ).scalar_one() == 1
        assert (
            await connection.execute(text("SELECT COUNT(*) FROM sync_operations"))
        ).scalar_one() == 1
        assert (
            await connection.execute(text("SELECT COUNT(*) FROM metric_appearances"))
        ).scalar_one() == 0


async def test_timer_receipt_namespaces_reject_cross_protocol_and_cross_restore_reuse(
    replica_http,
):
    client, production, engine, _, headers, device, _ = replica_http
    body = dict(device_id=device, commands=[VECTOR["timer_command"]])
    old = await legacy_post(replica_http, "/api/v2/timers/commands", body)
    assert (
        old.status_code == 200
        and old.json()["results"][0]["error_code"] == "TIMER_NOT_FOUND"
    )
    response = await client.post("/__test/v5/timers", headers=headers, json=body)
    assert response.json()["results"][0]["error_code"] == "COMMAND_ID_REUSED"
    # New v5 work has its own immutable rejected receipt and exact replay.
    body = deepcopy(body)
    body["commands"][0]["command_id"] = str(uuid4())
    first = await client.post("/__test/v5/timers", headers=headers, json=body)
    again = await client.post("/__test/v5/timers", headers=headers, json=body)
    assert first.status_code == again.status_code == 200
    assert first.json()["results"] == again.json()["results"]
    epoch = str(uuid4())
    async with engine.begin() as connection:
        await connection.execute(
            text("UPDATE server_instances SET sync_epoch=:epoch"), {"epoch": epoch}
        )
    headers = headers | {EPOCH_HEADER: epoch}
    response = await client.post("/__test/v5/timers", headers=headers, json=body)
    assert response.json()["results"][0]["error_code"] == "COMMAND_ID_REUSED"


@pytest.mark.parametrize("failure", ["commit", "none"])
async def test_new_sync_exact_replay_commit_failure_and_epoch_namespace(
    replica_http, failure
):
    client, _, engine, _, headers, device, _ = replica_http
    body = metric_request(device)
    if failure == "commit":
        async with engine.begin() as connection:
            await connection.execute(
                text(
                    "CREATE TABLE commit_fault (bad_user INTEGER REFERENCES users(id) DEFERRABLE INITIALLY DEFERRED)"
                )
            )
            await connection.execute(
                text(
                    "CREATE TRIGGER receipt_commit_fault AFTER INSERT ON sync_operations BEGIN INSERT INTO commit_fault VALUES (-999999); END"
                )
            )
        before = await database_state(engine)
        response = await client.post("/__test/v5/push", headers=headers, json=body)
        assert response.status_code == 500 and "FOREIGN KEY" not in response.text
        assert await database_state(engine) == before
        async with engine.begin() as connection:
            await connection.execute(text("DROP TRIGGER receipt_commit_fault"))
    first = await client.post("/__test/v5/push", headers=headers, json=body)
    again = await client.post("/__test/v5/push", headers=headers, json=body)
    assert first.status_code == again.status_code == 200
    assert first.json()["results"][0]["status"] == "applied"
    assert again.json()["results"] == [
        {**first.json()["results"][0], "status": "already_applied"}
    ]
    epoch = str(uuid4())
    async with engine.begin() as connection:
        await connection.execute(
            text("UPDATE server_instances SET sync_epoch=:epoch"), {"epoch": epoch}
        )
    response = await client.post(
        "/__test/v5/push", headers=headers | {EPOCH_HEADER: epoch}, json=body
    )
    assert response.json()["results"][0]["error_code"] == "OPERATION_ID_REUSED"


async def test_full_timer_state_machine_lost_response_replay_uses_new_namespace(
    replica_http,
):
    client, production, engine, _, headers, device, _ = replica_http
    activity = str(uuid4())
    created = await client.post(
        "/__test/v5/push",
        headers=headers,
        json=dict(
            device_id=device,
            operations=[operation(node(mode="duration"), identity=activity)],
        ),
    )
    assert (
        created.status_code == 200
        and created.json()["results"][0]["status"] == "applied"
    )
    start = datetime.now(UTC) - timedelta(minutes=5)
    session_id = str(uuid4())
    commands = [
        timer_command("start", session_id, 1, start, activity_id=activity),
        timer_command(
            "pause",
            session_id,
            2,
            start + timedelta(seconds=30),
            revision=1,
            active_elapsed_ms=30000,
        ),
        timer_command(
            "resume", session_id, 3, start + timedelta(seconds=60), revision=2
        ),
        timer_command(
            "stop",
            session_id,
            4,
            start + timedelta(seconds=90),
            revision=3,
            active_elapsed_ms=60000,
        ),
    ]
    commands[0]["start_policy"] = dict(
        target_seconds=60, is_countdown=False, max_duration_seconds=180
    )
    body = dict(device_id=device, commands=commands)
    first = await client.post("/__test/v5/timers", headers=headers, json=body)
    again = await client.post("/__test/v5/timers", headers=headers, json=body)
    assert first.status_code == again.status_code == 200, first.text
    assert [item["status"] for item in first.json()["results"]] == ["applied"] * 4
    assert first.json()["results"][-1]["session"]["state"] == "completed"
    assert again.json()["results"] == [
        {**item, "status": "already_applied"} for item in first.json()["results"]
    ]
    legacy = await legacy_post(replica_http, "/api/v2/timers/commands", body)
    assert legacy.status_code == 200
    assert [item["error_code"] for item in legacy.json()["results"]] == [
        "COMMAND_ID_REUSED"
    ] * 4
    async with engine.connect() as connection:
        assert (
            await connection.execute(text("SELECT COUNT(*) FROM activity_events"))
        ).scalar_one() == 1
        assert (
            await connection.execute(
                text("SELECT SUM(duration_ms) FROM duration_day_allocations")
            )
        ).scalar_one() == 60000


async def test_shared_gate_rechecks_cached_identity_without_flush_and_registration_needs_no_proof(
    runtime_http,
):
    _, engine, _, _, device = runtime_http
    factory = async_sessionmaker(engine, expire_on_commit=False)
    async with engine.begin() as connection:
        await connection.execute(text("UPDATE server_instances SET protocol_version=5"))
    async with factory() as session:
        user = await session.get(User, 1)
        identity = await session.get(ServerInstance, 1)
        assert user is not None and identity is not None
        replica = ReplicaIdentity(identity.instance_uuid, identity.sync_epoch)
        user.username = "must-not-flush"
        assert (
            await require_next_replica(
                session, ("5",), (replica.server_instance_id,), (replica.sync_epoch,)
            )
            == replica
        )
        with pytest.raises(DomainError) as rejected:
            await require_next_business(
                session,
                user,
                device,
                ("5",),
                (replica.server_instance_id,),
                (replica.sync_epoch,),
            )
        assert rejected.value.code == "CLIENT_UPGRADE_REQUIRED"
        async with engine.begin() as connection:
            await connection.execute(
                text("UPDATE server_instances SET sync_epoch=:epoch"),
                {"epoch": str(uuid4())},
            )
        # End only the read snapshot, keep the same session/ORM cache for the next one.
        await session.rollback()
        user = await session.get(User, 1)
        cached = await session.get(ServerInstance, 1)
        assert user is not None and cached is not None
        cached.sync_epoch = (
            replica.sync_epoch
        )  # Unsaved stale identity must not be admitted or flushed.
        user.username = "must-not-flush"
        statements = []

        def observe(_connection, _cursor, statement, _params, _context, _many):
            statements.append(statement)

        event.listen(engine.sync_engine, "before_cursor_execute", observe)
        try:
            with pytest.raises(DomainError) as rejected:
                await verify_replica_identity(session, replica)
            assert rejected.value.code == "SYNC_EPOCH_MISMATCH"
            assert all(statement.startswith("SELECT") for statement in statements)
        finally:
            event.remove(engine.sync_engine, "before_cursor_execute", observe)
    async with engine.connect() as connection:
        assert (
            await connection.execute(text("SELECT username FROM users WHERE id=1"))
        ).scalar_one() == "commit-owner"


@pytest.mark.parametrize(
    "case",
    [
        "version-missing",
        "version-old",
        "version-future",
        "version-duplicate",
        "context-missing",
        "context-invalid",
    ],
)
async def test_business_header_rejections_do_not_query_or_flush_pending_data(
    runtime_http, case
):
    _, engine, _, _, device = runtime_http
    factory = async_sessionmaker(engine, expire_on_commit=False)
    async with factory() as session:
        user = await session.get(User, 1)
        assert user is not None
        user.username = "never-flush"
        protocol = {
            "version-missing": (),
            "version-old": ("4",),
            "version-future": ("6",),
            "version-duplicate": ("5", "5"),
        }.get(case, ("5",))
        instance = (
            ()
            if case == "context-missing"
            else ("not-canonical",)
            if case == "context-invalid"
            else (REPLICA.server_instance_id,)
        )
        statements = []

        def observe(_connection, _cursor, statement, _params, _context, _many):
            statements.append(statement)

        event.listen(engine.sync_engine, "before_cursor_execute", observe)
        try:
            with pytest.raises(DomainError) as rejected:
                await require_next_business(
                    session, user, device, protocol, instance, (REPLICA.sync_epoch,)
                )
            assert rejected.value.code == (
                "CLIENT_UPGRADE_REQUIRED"
                if case.startswith("version")
                else "INVALID_SYNC_CONTEXT"
            )
            assert statements == []
        finally:
            event.remove(engine.sync_engine, "before_cursor_execute", observe)


async def test_orchestration_requires_original_scope_and_v4_cannot_use_new_namespace(
    runtime_http,
):
    _, engine, _, _, _ = runtime_http
    factory = async_sessionmaker(engine, expire_on_commit=False)
    before = await database_state(engine)
    async with factory() as session:
        with pytest.raises(DomainError) as rejected:
            await replay_replica(session, True, None)
        assert rejected.value.code == "INVALID_SYNC_CONTEXT"
        with pytest.raises(ValueError):
            await replay_replica(session, False, REPLICA)
        assert await replay_replica(session, False, None) is None
    assert await database_state(engine) == before
