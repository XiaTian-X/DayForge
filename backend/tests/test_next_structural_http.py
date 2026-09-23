"""Test-only v5 bridge: real auth, migrations and production HTTP commit boundary."""

from fastapi import Depends, FastAPI
from httpx import ASGITransport, AsyncClient
import pytest
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from src.auth.dependencies import get_current_user
from src.auth.models import User
from src.auth.router import router as auth_router
from src.database import get_session
from src.main import app
from src.tokens.models import ApiToken
from src.tokens.service import generate_token, hash_token
from src.v2.errors import DomainError
from src.v2 import read_service, service
from src.v2.next_sync_contract import (
    NextSyncBootstrapResponse,
    NextSyncPushRequest,
    NextSyncPushResponse,
)
from src.v2.read_service import bootstrap
from src.v2.router import _http_error
from src.v2.service import process_push
from tests.account_fixtures import TEST_ACCOUNT_PASSWORD
from tests.test_asset_declarations import setup
from tests.test_http_commit_boundary import database_state
from tests.test_next_structural_sync import METRIC, metric, node, operation, request
from tests.test_one_time_mutations import operation as fact_operation


@pytest.fixture(params=["jwt", "api-token"])
async def structural_http(runtime_engine, request):
    factory, contexts = await setup(runtime_engine)
    bridge = FastAPI()
    bridge.exception_handlers.update(app.exception_handlers)
    bridge.include_router(auth_router, prefix="/api/v1")
    bridge.state.bad_response = False

    @bridge.post("/__test/next-push", response_model=NextSyncPushResponse)
    async def push(
        body: NextSyncPushRequest,
        user: User = Depends(get_current_user),
        session: AsyncSession = Depends(get_session, scope="function"),
    ):
        try:
            response = await process_push(user, body, session, next_protocol=True)
            value = response.model_dump(mode="json")
            if bridge.state.bad_response:
                value["unknown_response_field"] = True
            return value
        except DomainError as error:
            raise _http_error(error) from error

    @bridge.get("/__test/next-bootstrap", response_model=NextSyncBootstrapResponse)
    async def recover(
        device_id: str,
        user: User = Depends(get_current_user),
        session: AsyncSession = Depends(get_session, scope="function"),
    ):
        try:
            return await bootstrap(user, device_id, session, next_protocol=True)
        except DomainError as error:
            raise _http_error(error) from error

    async with AsyncClient(
        transport=ASGITransport(app=bridge, raise_app_exceptions=False),
        base_url="http://test",
    ) as client:
        login = await client.post(
            "/api/v1/auth/login",
            json={"username": "asset-user-1", "password": TEST_ACCOUNT_PASSWORD},
        )
        assert login.status_code == 200
        headers = {"Authorization": "Bearer " + login.json()["access_token"]}
        if request.param == "api-token":
            key = generate_token()
            async with factory.begin() as session:
                session.add(
                    ApiToken(
                        user_id=1,
                        name="structural-commit-test",
                        token_hash=hash_token(key),
                        prefix=key[:11],
                    )
                )
            headers = {"Authorization": "Token " + key}
        yield client, bridge, contexts[1], headers


@pytest.mark.parametrize(
    "failure", ["commit", "response", "operation-result", "cursor"]
)
async def test_full_http_failure_rolls_back_structure_appearance_fact_and_auth_then_replays(
    runtime_engine, structural_http, failure
):
    client, bridge, context, headers = structural_http
    if failure == "response":
        bridge.state.bad_response = True
    else:
        async with runtime_engine.begin() as connection:
            if failure == "commit":
                await connection.execute(
                    text(
                        "CREATE TABLE commit_fault (bad_user INTEGER REFERENCES users(id) DEFERRABLE INITIALLY DEFERRED)"
                    )
                )
                trigger = "AFTER INSERT ON metric_appearances BEGIN INSERT INTO commit_fault VALUES (-999999); END"
            else:
                target = (
                    "UPDATE ON sync_operations"
                    if failure == "operation-result"
                    else "INSERT ON sync_cursors"
                )
                trigger = f"BEFORE {target} BEGIN SELECT RAISE(ABORT, 'injected outer failure'); END"
            await connection.execute(text(f"CREATE TRIGGER injected {trigger}"))
    before = await database_state(runtime_engine)
    body = request(
        context,
        [
            operation(node(once=True)),
            fact_operation(),
            operation(metric(), identity=METRIC, kind="metric"),
        ],
    ).model_dump(mode="json")
    failed = await client.post("/__test/next-push", headers=headers, json=body)
    assert failed.status_code == 500
    assert "applied" not in failed.text and "FOREIGN KEY" not in failed.text
    assert await database_state(runtime_engine) == before
    bridge.state.bad_response = False
    if failure != "response":
        async with runtime_engine.begin() as connection:
            await connection.execute(text("DROP TRIGGER injected"))
    applied = await client.post("/__test/next-push", headers=headers, json=body)
    assert applied.status_code == 200, applied.text
    assert [item["status"] for item in applied.json()["results"]] == ["applied"] * 3
    replay = await client.post("/__test/next-push", headers=headers, json=body)
    assert replay.status_code == 200
    assert replay.json()["results"] == [
        {**item, "status": "already_applied"} for item in applied.json()["results"]
    ]
    recovered = await client.get(
        "/__test/next-bootstrap",
        headers=headers,
        params={"device_id": context.device_id},
    )
    assert recovered.status_code == 200, recovered.text
    data = recovered.json()
    assert len(data["changes"]) == 3 and data["next_cursor"] == 3
    assert data["one_time_checkpoints"][0]["state"]["version"] == 1
    for change in data["changes"]:
        if change["entity_type"] in {"plan_node", "metric"}:
            assert "appearance" in change["payload"] and "icon" not in change["payload"]


async def test_http_recovery_rejects_incomplete_history_and_preserves_entire_database(
    runtime_engine, structural_http
):
    client, _, context, headers = structural_http
    body = request(context, [operation(node(once=True)), fact_operation()]).model_dump(
        mode="json"
    )
    assert (
        await client.post("/__test/next-push", headers=headers, json=body)
    ).status_code == 200
    async with runtime_engine.begin() as connection:
        await connection.execute(text("DELETE FROM activity_events"))
    before = await database_state(runtime_engine)
    failed = await client.get(
        "/__test/next-bootstrap",
        headers=headers,
        params={"device_id": context.device_id},
    )
    assert failed.status_code == 400
    assert failed.json()["detail"]["code"] == "TASK_HISTORY_INCOMPLETE"
    assert await database_state(runtime_engine) == before


@pytest.mark.parametrize("structural_http", ["jwt"], indirect=True)
@pytest.mark.parametrize("kind", ["push", "bootstrap"])
async def test_real_snapshot_race_retries_original_identity_without_partial_appearance(
    runtime_engine, structural_http, monkeypatch, kind
):
    client, _, context, headers = structural_http
    initial = request(context, [operation(node(once=True))]).model_dump(mode="json")
    assert (
        await client.post("/__test/next-push", headers=headers, json=initial)
    ).status_code == 200
    module = service if kind == "push" else read_service
    original_require = module.require_device
    winner_state = None
    triggered = False

    async def concurrent_fact(user_id, public_id, session):
        nonlocal winner_state, triggered
        device = await original_require(user_id, public_id, session)
        if not triggered:
            triggered = True
            winner = await client.post(
                "/__test/next-push",
                headers=headers,
                json=request(context, [fact_operation()]).model_dump(mode="json"),
            )
            assert (
                winner.status_code == 200
                and winner.json()["results"][0]["status"] == "applied"
            )
            winner_state = await database_state(runtime_engine)
        return device

    monkeypatch.setattr(module, "require_device", concurrent_fact)
    body = request(
        context, [operation(node(once=True, title="after race"), revision=1)]
    ).model_dump(mode="json")

    async def send():
        if kind == "push":
            return await client.post("/__test/next-push", headers=headers, json=body)
        return await client.get(
            "/__test/next-bootstrap",
            headers=headers,
            params={"device_id": context.device_id},
        )

    failed = await send()
    assert failed.status_code == 503, failed.text
    assert failed.json()["detail"]["code"] == "DATABASE_BUSY"
    assert failed.headers["Retry-After"] == "1"
    assert (
        winner_state is not None
        and await database_state(runtime_engine) == winner_state
    )
    retried = await send()
    assert retried.status_code == 200, retried.text
    if kind == "push":
        result = retried.json()["results"][0]
        assert result["status"] == "applied" and result["revision"] == 2
        replay = await send()
        assert replay.json()["results"] == [{**result, "status": "already_applied"}]
    recovered = await client.get(
        "/__test/next-bootstrap",
        headers=headers,
        params={"device_id": context.device_id},
    )
    assert recovered.status_code == 200, recovered.text
    assert recovered.json()["one_time_checkpoints"][0]["state"]["version"] == 1
