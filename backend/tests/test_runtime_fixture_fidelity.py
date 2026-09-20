"""Independent connections verify that HTTP success means durable database state."""

import pytest
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from src.auth.models import User
from src.database import get_session
from src.main import app
from tests.account_fixtures import TEST_ACCOUNT_PASSWORD, account_password_hash


async def assert_sqlite_constraints(engine):
    async with engine.connect() as connection:
        assert (await connection.execute(text("PRAGMA foreign_keys"))).scalar_one() == 1
        assert (
            await connection.execute(text("PRAGMA journal_mode"))
        ).scalar_one() == "wal"
        assert (
            await connection.execute(text("PRAGMA busy_timeout"))
        ).scalar_one() == 5000
        await connection.execute(
            text("CREATE TABLE parent_probe (id INTEGER PRIMARY KEY)")
        )
        await connection.execute(
            text(
                "CREATE TABLE child_probe (parent_id INTEGER REFERENCES parent_probe(id))"
            )
        )
        with pytest.raises(IntegrityError, match="FOREIGN KEY"):
            await connection.execute(text("INSERT INTO child_probe VALUES (-1)"))


async def test_service_fixture_enforces_foreign_keys(async_engine):
    await assert_sqlite_constraints(async_engine)


async def test_service_fixture_retains_async_session_configuration(
    async_session, async_engine
):
    assert isinstance(async_session, AsyncSession)
    assert async_session.bind is async_engine
    assert async_session.autoflush is True
    assert async_session.sync_session.expire_on_commit is False


async def test_runtime_fixture_is_migrated_and_has_no_session_override(runtime_engine):
    assert get_session not in app.dependency_overrides
    async with runtime_engine.connect() as connection:
        assert (
            await connection.execute(text("SELECT version_num FROM alembic_version"))
        ).scalar_one()
    await assert_sqlite_constraints(runtime_engine)


async def test_admin_creation_login_refresh_token_isolation_and_disable_are_durable(
    runtime_client, runtime_engine
):
    client = runtime_client
    sessions = async_sessionmaker(runtime_engine, expire_on_commit=False)
    async with sessions.begin() as session:
        session.add(
            User(
                username="runtime_admin",
                password_hash=account_password_hash(),
                is_admin=True,
            )
        )

    async def login(username):
        response = await client.post(
            "/api/v1/auth/login",
            json={
                "username": username,
                "password": TEST_ACCOUNT_PASSWORD,
            },
        )
        assert response.status_code == 200, response.text
        return response.json()

    admin_tokens = await login("runtime_admin")
    admin = {"Authorization": "Bearer " + admin_tokens["access_token"]}
    accounts = []
    for username in ("runtime_alice", "runtime_bob"):
        response = await client.post(
            "/api/v1/admin/users",
            headers=admin,
            json={
                "username": username,
                "password": TEST_ACCOUNT_PASSWORD,
                "is_admin": False,
            },
        )
        assert response.status_code == 201, response.text
        async with runtime_engine.connect() as connection:
            assert (
                await connection.execute(
                    text("SELECT COUNT(*) FROM users WHERE username=:name"),
                    {"name": username},
                )
            ).scalar_one() == 1
        accounts.append((response.json(), await login(username)))

    alice, tokens = accounts[0]
    refreshed = await client.post(
        "/api/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]}
    )
    assert refreshed.status_code == 200
    assert refreshed.json()["user_id"] == tokens["user_id"]
    alice_auth = {"Authorization": "Bearer " + refreshed.json()["access_token"]}
    bob_auth = {"Authorization": "Bearer " + accounts[1][1]["access_token"]}
    created = await client.post(
        "/api/v1/auth/tokens", headers=alice_auth, json={"name": "runtime-probe"}
    )
    assert created.status_code == 201, created.text
    token = created.json()
    api_auth = {"Authorization": "Token " + token["token"]}
    async with runtime_engine.connect() as connection:
        stored = (
            await connection.execute(
                text("SELECT token_hash FROM api_tokens WHERE id=:id"),
                {"id": token["id"]},
            )
        ).scalar_one()
        assert token["token"] not in stored
    me = await client.get("/api/v1/auth/users/me", headers=api_auth)
    assert me.status_code == 200
    assert me.json()["username"] == "runtime_alice"
    async with runtime_engine.connect() as connection:
        assert (
            await connection.execute(
                text("SELECT last_used_at FROM api_tokens WHERE id=:id"),
                {"id": token["id"]},
            )
        ).scalar_one() is not None
    listed = await client.get("/api/v1/auth/tokens", headers=bob_auth)
    assert listed.status_code == 200
    assert listed.json() == []
    denied = await client.delete(f"/api/v1/auth/tokens/{token['id']}", headers=bob_auth)
    assert denied.status_code == 404
    assert (
        await client.get("/api/v1/auth/users/me", headers=api_auth)
    ).status_code == 200
    disabled = await client.put(
        f"/api/v1/admin/users/{alice['id']}/status",
        headers=admin,
        json={"is_active": False},
    )
    assert disabled.status_code == 200, disabled.text
    for headers in (alice_auth, api_auth):
        assert (
            await client.get("/api/v1/auth/users/me", headers=headers)
        ).status_code == 401
    assert (
        await client.post(
            "/api/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]}
        )
    ).status_code == 401
