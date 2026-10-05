"""Real production registration and inactive v5 gate use migrated snapshots."""

from fastapi import Depends, FastAPI, Request
from contextlib import closing
import sqlite3
from httpx import ASGITransport, AsyncClient
import pytest
from sqlalchemy import event, text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from src.auth.dependencies import get_current_user
from src.auth.models import User
from src.database import get_session
from src.main import app
from src.v2.errors import DomainError
from src.v2.models import ClientDevice, ServerInstance
from src.v2.protocol_admission import require_next_protocol
from src.v2.router import _http_error
from tests.test_http_commit_boundary import database_state, runtime_http as runtime_http
from tests.test_jwt_runtime_server import isolated_server


@pytest.fixture(params=["jwt", "api"])
async def protocol_http(runtime_http, request):
    client, engine, bearer, api, device = runtime_http
    return client, engine, bearer if request.param == "jwt" else api, device


def registration(version=5, **changes):
    return (
        dict(
            installation_id="commit-primary-device",
            platform="android",
            protocol_version=version,
        )
        | changes
    )


async def device_row(engine):
    async with engine.connect() as connection:
        return dict(
            (await connection.execute(text("SELECT * FROM client_devices")))
            .mappings()
            .one()
        )


@pytest.mark.parametrize("version", [4, 5, 6, 2_147_483_647, 2_147_483_648, 10**100])
async def test_explicit_reregistration_commits_representable_proof_without_changing_identity_caps_or_v4_wire(
    protocol_http, version
):
    client, engine, headers, device = protocol_http
    before = await device_row(engine)
    first = await client.get("/api/v2/devices", headers=headers)
    current = await client.post(
        "/api/v2/devices/register", headers=headers, json=registration(version)
    )
    assert current.status_code == 200, current.text
    expected = version if version <= 2_147_483_647 else None
    persisted = await device_row(engine)
    assert persisted["registered_protocol_version"] == expected
    assert {
        key: value
        for key, value in persisted.items()
        if key not in {"registered_protocol_version", "last_seen_at"}
    } == {
        key: value
        for key, value in before.items()
        if key not in {"registered_protocol_version", "last_seen_at"}
    }
    assert str(current.json()["device_id"]) == device
    assert {
        key: value for key, value in current.json().items() if key != "last_seen_at"
    } == {key: value for key, value in first.json()[0].items() if key != "last_seen_at"}
    assert "registered_protocol_version" not in current.json()
    identity = await client.get("/api/v2/system/identity")
    assert identity.status_code == 200 and identity.json()["protocol_version"] == 4


@pytest.mark.parametrize(
    "case", ["missing", "old", "boolean", "revoked", "platform", "class"]
)
async def test_rejected_registration_never_overwrites_proof_or_device_policy(
    protocol_http, case
):
    client, engine, headers, _ = protocol_http
    assert (
        await client.post(
            "/api/v2/devices/register", headers=headers, json=registration()
        )
    ).status_code == 200
    body = registration(6)
    if case == "missing":
        del body["protocol_version"]
    if case == "old":
        body["protocol_version"] = 3
    if case == "boolean":
        body["protocol_version"] = True
    if case == "platform":
        body["platform"] = "desktop"
    if case == "class":
        body.update(platform="desktop", device_class="automation")
    if case == "revoked":
        async with engine.begin() as connection:
            await connection.execute(
                text("UPDATE client_devices SET revoked_at='2026-10-05 00:00:00'")
            )
    before = await database_state(engine)
    result = await client.post("/api/v2/devices/register", headers=headers, json=body)
    assert result.status_code == (
        426
        if case in {"missing", "old", "boolean"}
        else 403
        if case == "revoked"
        else 400
    )
    assert await database_state(engine) == before


async def test_real_deferred_commit_failure_does_not_publish_proof_and_original_registration_can_retry(
    protocol_http,
):
    client, engine, headers, device = protocol_http
    async with engine.begin() as connection:
        await connection.execute(
            text(
                "CREATE TABLE commit_fault (bad_user INTEGER REFERENCES users(id) DEFERRABLE INITIALLY DEFERRED)"
            )
        )
        await connection.execute(
            text(
                "CREATE TRIGGER proof_commit_fault AFTER UPDATE ON client_devices BEGIN INSERT INTO commit_fault VALUES (-999999); END"
            )
        )
    before = await database_state(engine)
    failed = await client.post(
        "/api/v2/devices/register", headers=headers, json=registration()
    )
    assert failed.status_code == 500 and "FOREIGN KEY" not in failed.text
    assert (
        await database_state(engine) == before
        and (await device_row(engine))["registered_protocol_version"] == 4
    )
    async with engine.begin() as connection:
        await connection.execute(text("DROP TRIGGER proof_commit_fault"))
    retry = await client.post(
        "/api/v2/devices/register", headers=headers, json=registration()
    )
    assert retry.status_code == 200 and retry.json()["device_id"] == device
    assert (await device_row(engine))["registered_protocol_version"] == 5


@pytest.mark.parametrize(
    "headers",
    [
        (),
        ("4",),
        ("6",),
        ("5", "5"),
        ("5", "4"),
        (" 5",),
        ("5 ",),
        ("05",),
        ("5.0",),
        ("5,5",),
    ],
)
async def test_header_rejection_does_not_query_or_initialize_database(
    runtime_http, headers
):
    _, engine, _, _, device = runtime_http
    factory = async_sessionmaker(engine, expire_on_commit=False)
    statements = []

    def observe(_connection, _cursor, statement, _params, _context, _many):
        statements.append(statement)

    async with factory() as session:
        user = await session.get(User, 1)
        assert user is not None
        event.listen(engine.sync_engine, "before_cursor_execute", observe)
        try:
            with pytest.raises(DomainError) as rejected:
                await require_next_protocol(session, user, device, headers)
            assert rejected.value.code == "CLIENT_UPGRADE_REQUIRED" and statements == []
        finally:
            event.remove(engine.sync_engine, "before_cursor_execute", observe)


@pytest.mark.parametrize(
    "server,proof", [(4, 5), (6, 5), (5, None), (5, 4), (5, 6), (5, 5.5), (5.5, 5)]
)
async def test_exact_server_and_registered_device_versions_are_required_without_mutation(
    runtime_http, server, proof
):
    _, engine, _, _, device = runtime_http
    async with engine.begin() as connection:
        await connection.execute(
            text("UPDATE server_instances SET protocol_version=:version"),
            {"version": server},
        )
        await connection.execute(
            text(
                "UPDATE client_devices SET registered_protocol_version=:version,app_version='5'"
            ),
            {"version": proof},
        )
    before = await database_state(engine)
    factory = async_sessionmaker(engine, expire_on_commit=False)
    async with factory() as session:
        user = await session.get(User, 1)
        assert user is not None
        with pytest.raises(DomainError) as rejected:
            await require_next_protocol(session, user, device, ("5",))
        assert rejected.value.code == "CLIENT_UPGRADE_REQUIRED"
    assert await database_state(engine) == before


@pytest.mark.parametrize(
    "case", ["valid", "unknown-device", "revoked", "other-account", "missing-server"]
)
async def test_shared_gate_uses_owned_fresh_device_and_is_read_only(runtime_http, case):
    _, engine, _, _, device = runtime_http
    factory = async_sessionmaker(engine, expire_on_commit=False)
    if case == "other-account":
        async with factory.begin() as session:
            session.add(User(id=999, username="other", password_hash="synthetic"))
    async with engine.begin() as connection:
        await connection.execute(text("UPDATE server_instances SET protocol_version=5"))
        await connection.execute(
            text("UPDATE client_devices SET registered_protocol_version=5")
        )
        if case == "revoked":
            await connection.execute(
                text("UPDATE client_devices SET revoked_at='2026-10-05 00:00:00'")
            )
        if case == "missing-server":
            await connection.execute(text("DELETE FROM server_instances"))
    before = await database_state(engine)
    async with factory() as session:
        user = await session.get(User, 1)
        assert user is not None
        if case == "other-account":
            user = await session.get(User, 999)
            assert user is not None
        identifier = (
            "b9700000-0000-4000-8000-000000000001"
            if case == "unknown-device"
            else device
        )
        if case == "valid":
            result = await require_next_protocol(session, user, identifier, ("5",))
            assert result.public_id == device and result.user_id == 1
        else:
            with pytest.raises(DomainError) as rejected:
                await require_next_protocol(session, user, identifier, ("5",))
            assert rejected.value.code == (
                "CLIENT_UPGRADE_REQUIRED"
                if case == "missing-server"
                else "DEVICE_NOT_FOUND"
            )
    assert await database_state(engine) == before


@pytest.mark.parametrize("change", ["server", "proof", "revoked"])
async def test_new_snapshot_does_not_admit_using_cached_server_or_device(
    runtime_http, change
):
    _, engine, _, _, device = runtime_http
    async with engine.begin() as connection:
        await connection.execute(text("UPDATE server_instances SET protocol_version=5"))
        await connection.execute(
            text("UPDATE client_devices SET registered_protocol_version=5")
        )
    factory = async_sessionmaker(engine, expire_on_commit=False)
    async with factory() as session:
        user = await session.get(User, 1)
        assert user is not None
        cached_server = await session.get(ServerInstance, 1)
        cached_device = await session.get(ClientDevice, 1)
        assert cached_server is not None and cached_device is not None
        assert (
            cached_server.protocol_version
            == cached_device.registered_protocol_version
            == 5
        )
        await session.commit()
        async with engine.begin() as connection:
            statement = {
                "server": "UPDATE server_instances SET protocol_version=4",
                "proof": "UPDATE client_devices SET registered_protocol_version=4",
                "revoked": "UPDATE client_devices SET revoked_at='2026-10-05 00:00:00'",
            }[change]
            await connection.execute(text(statement))
        assert (
            cached_server.protocol_version
            == cached_device.registered_protocol_version
            == 5
        )
        before = await database_state(engine)
        with pytest.raises(DomainError) as rejected:
            await require_next_protocol(session, user, device, ("5",))
        assert rejected.value.code == (
            "DEVICE_NOT_FOUND" if change == "revoked" else "CLIENT_UPGRADE_REQUIRED"
        )
        assert await database_state(engine) == before


async def test_gate_http_requires_unambiguous_header_and_real_auth_without_replacing_v4_routes(
    protocol_http,
):
    _, engine, headers, device = protocol_http
    bridge = FastAPI()
    bridge.exception_handlers.update(app.exception_handlers)

    @bridge.get("/__test/next-gate")
    async def gate(
        request: Request,
        user: User = Depends(get_current_user),
        session: AsyncSession = Depends(get_session, scope="function"),
    ):
        try:
            result = await require_next_protocol(
                session,
                user,
                device,
                tuple(request.headers.getlist("X-DayForge-Protocol")),
            )
            return {"device_id": result.public_id}
        except DomainError as error:
            raise _http_error(error) from error

    async with engine.begin() as connection:
        await connection.execute(text("UPDATE server_instances SET protocol_version=5"))
        await connection.execute(
            text("UPDATE client_devices SET registered_protocol_version=5")
        )
    async with AsyncClient(
        transport=ASGITransport(app=bridge), base_url="http://test"
    ) as client:
        assert (await client.get("/__test/next-gate")).status_code == 401
        accepted = await client.get(
            "/__test/next-gate", headers=headers | {"X-DayForge-Protocol": "5"}
        )
        assert accepted.status_code == 200 and accepted.json() == {"device_id": device}
        before = await database_state(engine)
        duplicate = await client.get(
            "/__test/next-gate",
            headers=[
                *headers.items(),
                ("X-DayForge-Protocol", "5"),
                ("X-DayForge-Protocol", "5"),
            ],
        )
        assert (
            duplicate.status_code == 426
            and duplicate.json()["detail"]["code"] == "CLIENT_UPGRADE_REQUIRED"
        )
        assert await database_state(engine) == before
    assert not any(
        path.startswith("/api/v2/appearance") for path in app.openapi()["paths"]
    )


def test_real_tcp_registration_commits_proof_and_rejects_missing_version_without_upgrade(
    tmp_path,
):
    with isolated_server(tmp_path) as (client, _, password):
        token = client.login("jwt_acceptance_admin", password)["access_token"]
        device = client.register_device(token, "protocol-tcp-device")

        def persisted():
            with closing(
                sqlite3.connect(tmp_path / "jwt-acceptance.sqlite")
            ) as connection:
                return connection.execute(
                    "SELECT public_id,registered_protocol_version FROM client_devices"
                ).fetchall()

        assert persisted() == [(device, 4)]
        body = registration(5, installation_id="protocol-tcp-device")
        result = client.request(
            "POST", "/api/v2/devices/register", token=token, payload=body
        )
        assert result["device_id"] == device and persisted() == [(device, 5)]
        del body["protocol_version"]
        rejected = client.request(
            "POST",
            "/api/v2/devices/register",
            token=token,
            payload=body,
            expected=(426,),
        )
        assert rejected["detail"]["code"] == "CLIENT_UPGRADE_REQUIRED"
        assert persisted() == [(device, 5)]
        assert client.request("GET", "/api/v2/system/identity")["protocol_version"] == 4


@pytest.mark.parametrize("proof", [4, 5])
async def test_gate_does_not_autoflush_callers_pending_writes(runtime_http, proof):
    _, engine, _, _, device = runtime_http
    async with engine.begin() as connection:
        await connection.execute(text("UPDATE server_instances SET protocol_version=5"))
        await connection.execute(
            text("UPDATE client_devices SET registered_protocol_version=:proof"),
            {"proof": proof},
        )
    before = await database_state(engine)
    writes = []

    def observe(_connection, _cursor, statement, _params, _context, _many):
        if statement.lstrip().upper().startswith(("INSERT", "UPDATE", "DELETE")):
            writes.append(statement)

    factory = async_sessionmaker(engine, expire_on_commit=False)
    async with factory() as session:
        user = await session.get(User, 1)
        assert user is not None
        pending = User(username="pending-proof-test", password_hash="synthetic")
        session.add(pending)
        user.username = "not-persisted"
        event.listen(engine.sync_engine, "before_cursor_execute", observe)
        try:
            if proof == 5:
                assert (
                    await require_next_protocol(session, user, device, ("5",))
                ).public_id == device
            else:
                with pytest.raises(DomainError) as rejected:
                    await require_next_protocol(session, user, device, ("5",))
                assert rejected.value.code == "CLIENT_UPGRADE_REQUIRED"
            assert writes == [] and pending in session.new and user in session.dirty
        finally:
            event.remove(engine.sync_engine, "before_cursor_execute", observe)
    assert await database_state(engine) == before
