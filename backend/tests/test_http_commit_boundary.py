"""HTTP acknowledgement uses the real runtime session, not a yield-only override."""

from httpx import ASGITransport, AsyncClient
import pytest
from sqlalchemy import event, text
from sqlalchemy.ext.asyncio import async_sessionmaker

from src.auth.models import User
from tests.account_fixtures import TEST_ACCOUNT_PASSWORD, account_password_hash
from src.database import get_session
from src.main import app
from src.tokens.models import ApiToken
from src.tokens.service import generate_token, hash_token
from tests.test_sync_contract_matrix import fixture, with_device
from tests.test_sync_v2 import goal_operation


@pytest.fixture
async def runtime_http(runtime_engine):
    engine = runtime_engine
    api_key = generate_token()
    sessions = async_sessionmaker(engine, expire_on_commit=False)
    async with sessions.begin() as session:
        user = User(
            username="commit-owner",
            password_hash=account_password_hash(),
            is_admin=True,
        )
        session.add(user)
        await session.flush()
        session.add(
            ApiToken(
                user_id=user.id,
                name="commit-probe",
                token_hash=hash_token(api_key),
                prefix=api_key[:11],
            )
        )
    async with AsyncClient(
        transport=ASGITransport(app=app, raise_app_exceptions=False),
        base_url="http://test",
    ) as client:
        login = await client.post(
            "/api/v1/auth/login",
            json={"username": "commit-owner", "password": TEST_ACCOUNT_PASSWORD},
        )
        assert login.status_code == 200
        bearer = {"Authorization": "Bearer " + login.json()["access_token"]}
        device = await client.post(
            "/api/v2/devices/register",
            headers=bearer,
            json={
                "installation_id": "commit-primary-device",
                "platform": "android",
                "protocol_version": 4,
            },
        )
        assert device.status_code == 200
        yield (
            client,
            engine,
            bearer,
            {"Authorization": "Token " + api_key},
            device.json()["device_id"],
        )


async def database_state(engine):
    async with engine.connect() as connection:
        names = (
            (
                await connection.execute(
                    text(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != 'commit_fault' ORDER BY name"
                    )
                )
            )
            .scalars()
            .all()
        )
        return {
            name: (
                await connection.execute(text(f'SELECT * FROM "{name}" ORDER BY rowid'))
            ).all()
            for name in names
        }


@pytest.mark.parametrize("kind", ["push", "timer", "register"])
@pytest.mark.parametrize("authentication", ["jwt", "api-token"])
async def test_commit_failure_never_acknowledges_and_same_request_can_retry(
    runtime_http, kind, authentication
):
    client, engine, bearer, api_token, device = runtime_http
    headers = bearer if authentication == "jwt" else api_token
    if kind == "push":
        path, table = "/api/v2/sync/push", "plan_nodes"
        body = {"device_id": device, "operations": [goal_operation()]}
    elif kind == "timer":
        initial = await client.post(
            "/api/v2/sync/push",
            headers=bearer,
            json=with_device(fixture("client/push-all-entities.json"), device),
        )
        assert all(r["status"] == "applied" for r in initial.json()["results"])
        path, table = "/api/v2/timers/commands", "activity_events"
        body = with_device(fixture("client/timer-commands.json"), device)
    else:
        path, table = "/api/v2/devices/register", "client_devices"
        body = {
            "installation_id": "commit-secondary-device",
            "platform": "android",
            "protocol_version": 4,
        }

    async with engine.begin() as connection:
        # All handler flushes succeed; the genuine deferred FK error occurs only
        # at the outer COMMIT. No production schema or dependencies are replaced.
        await connection.execute(
            text(
                "CREATE TABLE commit_fault (bad_user INTEGER REFERENCES users(id) DEFERRABLE INITIALLY DEFERRED)"
            )
        )
        await connection.execute(
            text(
                f"CREATE TRIGGER inject_commit_failure AFTER INSERT ON {table} BEGIN INSERT INTO commit_fault VALUES (-999999); END"
            )
        )
    before = await database_state(engine)
    failed = await client.post(path, headers=headers, json=body)
    assert await database_state(engine) == before
    assert failed.status_code == 500, failed.text
    assert "applied" not in failed.text and "FOREIGN KEY" not in failed.text

    async with engine.begin() as connection:
        await connection.execute(text("DROP TRIGGER inject_commit_failure"))
    recovered = await client.post(path, headers=headers, json=body)
    assert recovered.status_code == 200, recovered.text
    if kind == "register":
        replay = await client.post(path, headers=headers, json=body)
        assert replay.json()["device_id"] == recovered.json()["device_id"]
    else:
        assert all(r["status"] == "applied" for r in recovered.json()["results"])
        replay = await client.post(path, headers=headers, json=body)
        assert replay.json()["results"] == [
            {**r, "status": "already_applied"} for r in recovered.json()["results"]
        ]


async def test_authentication_and_handler_share_one_committed_session_before_response(
    runtime_http,
):
    client, engine, _, headers, device = runtime_http
    timeline = []
    operation = goal_operation()

    def committed(connection):
        timeline.append("commit")

    event.listen(engine.sync_engine, "commit", committed)

    async def observed_app(scope, receive, send):
        async def observed_send(message):
            if message["type"] == "http.response.start":
                timeline.append("response")
                async with engine.connect() as connection:
                    assert (
                        await connection.execute(
                            text(
                                "SELECT COUNT(*) FROM plan_nodes WHERE public_id = :id"
                            ),
                            {"id": operation["entity_uuid"]},
                        )
                    ).scalar_one() == 1
                    assert (
                        await connection.execute(
                            text("SELECT last_used_at FROM api_tokens")
                        )
                    ).scalar_one() is not None
            await send(message)

        await app(scope, receive, observed_send)

    try:
        async with AsyncClient(
            transport=ASGITransport(app=observed_app), base_url="http://test"
        ) as observer:
            response = await observer.post(
                "/api/v2/sync/push",
                headers=headers,
                json={"device_id": device, "operations": [operation]},
            )
        assert response.status_code == 200
        assert timeline == ["commit", "response"]
    finally:
        event.remove(engine.sync_engine, "commit", committed)


@pytest.mark.parametrize("failure", ["request-validation", "device-not-found"])
async def test_http_errors_roll_back_api_token_activity_without_committing(
    runtime_http, failure
):
    client, engine, _, headers, device = runtime_http
    from uuid import uuid4

    body = (
        {"device_id": device, "operations": []}
        if failure == "request-validation"
        else {
            "device_id": str(uuid4()),
            "operations": [goal_operation()],
        }
    )
    before = await database_state(engine)
    response = await client.post("/api/v2/sync/push", headers=headers, json=body)
    assert response.status_code == (422 if failure == "request-validation" else 404)
    assert await database_state(engine) == before


def test_all_runtime_session_dependencies_use_the_same_pre_response_scope():
    # FastAPI may wrap included routers, so inspect the source routers as well.
    from fastapi.routing import APIRoute
    from src.main import (
        admin_router,
        admin_tokens_router,
        auth_router,
        tokens_router,
        v2_router,
    )

    found = []

    def visit(dependency):
        if dependency.call is get_session:
            found.append(dependency)
            assert dependency.scope == "function"
        for child in dependency.dependencies:
            visit(child)

    for router in (
        app,
        admin_router,
        admin_tokens_router,
        auth_router,
        tokens_router,
        v2_router,
    ):
        for route in router.routes:
            if isinstance(route, APIRoute):
                visit(route.dependant)
    assert len(found) >= 40
