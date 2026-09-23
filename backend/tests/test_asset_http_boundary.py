"""Test-only bridge to real auth/commit dependencies, not a v5 route acceptance."""

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
from src.v2.asset_api_contract import AssetDeclaration, AssetRecord
from src.v2.asset_service import declare_asset
from src.v2.errors import DomainError
from src.v2.router import _http_error
from tests.account_fixtures import TEST_ACCOUNT_PASSWORD
from tests.test_asset_declarations import asset, setup, snapshot
from tests.test_http_commit_boundary import database_state


@pytest.fixture(params=["jwt", "api-token"])
async def asset_http(runtime_engine, request):
    factory, contexts = await setup(runtime_engine)
    bridge = FastAPI()
    bridge.exception_handlers.update(app.exception_handlers)
    bridge.include_router(auth_router, prefix="/api/v1")

    @bridge.post("/__test/assets", response_model=AssetRecord)
    async def put_asset(
        body: AssetDeclaration,
        fail_response: bool = False,
        user: User = Depends(get_current_user),
        session: AsyncSession = Depends(get_session, scope="function"),
    ):
        try:
            result = await declare_asset(session, user, body)
            return {"invalid": True} if fail_response else result
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
                        name="asset-boundary-test",
                        token_hash=hash_token(key),
                        prefix=key[:11],
                    )
                )
            headers = {"Authorization": "Token " + key}
        yield client, factory, headers, contexts


@pytest.mark.parametrize("failure", ["commit", "response", "catalog"])
async def test_real_auth_and_commit_failure_never_acknowledge_partial_asset(
    runtime_engine, asset_http, failure
):
    client, factory, headers, contexts = asset_http
    async with runtime_engine.begin() as connection:
        if failure == "commit":
            await connection.execute(
                text(
                    "CREATE TABLE asset_commit_fault (bad_user INTEGER REFERENCES users(id) DEFERRABLE INITIALLY DEFERRED)"
                )
            )
            await connection.execute(
                text(
                    "CREATE TRIGGER asset_outer_failure AFTER INSERT ON account_icon_assets BEGIN INSERT INTO asset_commit_fault VALUES (-999999); END"
                )
            )
        elif failure == "catalog":
            await connection.execute(
                text(
                    "CREATE TRIGGER asset_outer_failure BEFORE INSERT ON appearance_catalog BEGIN SELECT RAISE(ABORT, 'injected outer asset failure'); END"
                )
            )
    before = await database_state(runtime_engine)
    body = asset(contexts[1]).model_dump(mode="json")
    failed = await client.post(
        "/__test/assets?fail_response=true"
        if failure == "response"
        else "/__test/assets",
        headers=headers,
        json=body,
    )
    assert failed.status_code == 500
    assert "FOREIGN KEY" not in failed.text and "ready_variants" not in failed.text
    assert await database_state(runtime_engine) == before
    if failure != "response":
        async with runtime_engine.begin() as connection:
            await connection.execute(text("DROP TRIGGER asset_outer_failure"))
    first = await client.post("/__test/assets", headers=headers, json=body)
    assert first.status_code == 200, first.text
    assert (
        first.json()["asset"] == body["asset"] and first.json()["ready_variants"] == []
    )
    stored = await snapshot(factory)
    assert len(stored["account_icon_assets"]) == len(stored["appearance_catalog"]) == 1
    assert stored["appearance_accounts"][0]["reserved_bytes"] == 96
    replay = await client.post("/__test/assets", headers=headers, json=body)
    assert replay.status_code == 200 and replay.json() == first.json()
    assert await snapshot(factory) == stored


@pytest.mark.parametrize("case", ["missing-auth", "disabled-user", "other-device"])
async def test_authentication_and_device_scope_precede_declaration(asset_http, case):
    client, factory, headers, contexts = asset_http
    if case == "disabled-user":
        async with factory.begin() as session:
            await session.execute(
                text("UPDATE users SET is_active=0,status='disabled' WHERE id=1")
            )
    before = await snapshot(factory)
    body = asset(contexts[2] if case == "other-device" else contexts[1]).model_dump(
        mode="json"
    )
    result = await client.post(
        "/__test/assets", headers={} if case == "missing-auth" else headers, json=body
    )
    assert result.status_code == (404 if case == "other-device" else 401)
    assert await snapshot(factory) == before


def test_staged_services_are_not_production_routes():
    assert all(
        not path.startswith("/api/v2/appearance") for path in app.openapi()["paths"]
    )
