"""Real auth transactions after remapping/rollback, with complete asset backups."""

from contextlib import asynccontextmanager, closing
from datetime import timedelta
import sqlite3
from uuid import uuid4

from httpx import ASGITransport, AsyncClient
import pytest
from sqlalchemy import text
from sqlalchemy.ext.asyncio import async_sessionmaker

from src.auth.models import User
from src.auth.service import (
    create_access_token,
    create_refresh_token,
    get_password_hash,
    verify_token,
)
from src.database import get_engine, set_engine
from src.main import app
from src.storage.asset_files import AssetFiles
from src.storage.database_adapter import build_database_adapter
from src.storage.logical_archive import export_archive, import_archive
from src.storage.sqlite_maintenance import (
    create_backup,
    inspect_database,
    restore_backup,
)
from src.tokens.models import ApiToken
from src.tokens.service import hash_token
from tests.test_logical_archive import migrate
from tests.test_physical_asset_backup import seed_ready


PASSWORD = "synthetic-restore-password-not-a-deployment-secret"


@asynccontextmanager
async def reopened_client(path):
    """Every open uses a new actual engine and production request dependencies."""
    engine = build_database_adapter("sqlite", None, str(path)).create_async_engine()
    previous = get_engine()
    set_engine(engine)
    try:
        async with AsyncClient(
            transport=ASGITransport(app=app, raise_app_exceptions=False),
            base_url="http://test",
        ) as client:
            yield client
    finally:
        set_engine(previous)
        await engine.dispose()


async def login(client, username):
    response = await client.post(
        "/api/v1/auth/login", json={"username": username, "password": PASSWORD}
    )
    assert response.status_code == 200, response.text
    return response.json()


async def rejected_pair(client, tokens):
    access = await client.get(
        "/api/v1/auth/users/me",
        headers={"Authorization": "Bearer " + tokens["access_token"]},
    )
    assert access.status_code == 401
    assert access.headers["www-authenticate"] == "Bearer"
    refresh = await client.post(
        "/api/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]}
    )
    assert refresh.status_code == 401


@pytest.fixture
async def signed_session(runtime_engine, runtime_client):
    factory = async_sessionmaker(runtime_engine, expire_on_commit=False)
    async with factory.begin() as session:
        user = User(
            id=17, username="session-owner", password_hash=get_password_hash(PASSWORD)
        )
        session.add(user)
        await session.flush()
        session.add(
            ApiToken(
                user_id=17,
                name="restoration-probe",
                token_hash=hash_token("df_synthetic_scope_probe"),
                prefix="df_syntheti",
            )
        )
    tokens = await login(runtime_client, user.username)
    return factory, runtime_client, user, tokens


@pytest.mark.parametrize("claim", ["uid", "sid", "epoch"])
@pytest.mark.parametrize("value", [None, [], {}, True, 17, "", "not-a-uuid", "x" * 37])
async def test_malformed_stable_claims_reject_both_token_kinds(
    signed_session, claim, value
):
    _, client, _, tokens = signed_session
    claims = verify_token(tokens["access_token"])
    assert claims is not None
    changed = claims | {claim: value}
    await rejected_pair(
        client,
        {
            "access_token": create_access_token(changed),
            "refresh_token": create_refresh_token(changed),
        },
    )


@pytest.mark.parametrize("claim", ["uid", "sid", "epoch", "sub", "ver"])
async def test_missing_binding_never_falls_back_to_internal_id(signed_session, claim):
    _, client, _, tokens = signed_session
    claims = verify_token(tokens["access_token"])
    assert claims is not None
    del claims[claim]
    await rejected_pair(
        client,
        {
            "access_token": create_access_token(claims),
            "refresh_token": create_refresh_token(claims),
        },
    )


@pytest.mark.parametrize("change", ["identity", "server", "epoch", "version"])
async def test_authorization_uses_current_identity_scope_and_revocation(
    signed_session, change
):
    factory, client, user, tokens = signed_session
    async with factory.begin() as writer:
        statement = {
            "identity": "UPDATE users SET public_id=:replacement WHERE id=17",
            "server": "UPDATE server_instances SET instance_uuid=:replacement",
            "epoch": "UPDATE server_instances SET sync_epoch=:replacement",
            "version": "UPDATE users SET auth_version=auth_version+1 WHERE id=17",
        }[change]
        await writer.execute(text(statement), {"replacement": str(uuid4())})
    await rejected_pair(client, tokens)
    # These tokens are separate persistent credentials, not JWT sessions.
    api = await client.get(
        "/api/v1/auth/users/me",
        headers={"Authorization": "Token df_synthetic_scope_probe"},
    )
    assert api.status_code == 200 and api.json()["id"] == user.id
    fresh = await login(client, user.username)
    me = await client.get(
        "/api/v1/auth/users/me",
        headers={"Authorization": "Bearer " + fresh["access_token"]},
    )
    assert me.status_code == 200 and me.json()["public_id"] == fresh["user_id"]
    refreshed = await client.post(
        "/api/v1/auth/refresh", json={"refresh_token": fresh["refresh_token"]}
    )
    assert (
        refreshed.status_code == 200 and refreshed.json()["user_id"] == fresh["user_id"]
    )


async def test_missing_server_identity_cannot_issue_or_accept_tokens(signed_session):
    factory, client, _, tokens = signed_session
    async with factory.begin() as session:
        await session.execute(text("DELETE FROM server_instances"))
    await rejected_pair(client, tokens)
    response = await client.post(
        "/api/v1/auth/login", json={"username": "session-owner", "password": PASSWORD}
    )
    assert response.status_code == 503
    assert response.json() == {"detail": "SERVER_IDENTITY_UNAVAILABLE"}


@pytest.mark.parametrize("kind", ["physical", "logical"])
async def test_restored_family_rejects_old_jwts_preserves_api_owners_and_real_bytes(
    tmp_path, kind
):
    source, source_root = tmp_path / "source.db", tmp_path / "source-assets"
    entries = seed_ready(source, source_root, second="png")
    password_hash = get_password_hash(PASSWORD)
    # UUID order yields new IDs 1/2/3: original ID3's subject collides with
    # another account's new ID3, at the same auth_version. Not just nonexistent IDs.
    with closing(sqlite3.connect(source)) as connection:
        connection.execute("PRAGMA foreign_keys=ON")
        connection.execute("UPDATE users SET password_hash=?", (password_hash,))
        connection.execute(
            "INSERT INTO users(id,public_id,username,password_hash,is_active,is_verified,is_admin,status,auth_version,created_at,updated_at) "
            "VALUES(3,?,'restore-probe',?,1,0,0,'active',1,'2026-10-11','2026-10-11')",
            ("00000000-0000-4000-8000-000000000000", password_hash),
        )
        owners = connection.execute(
            "SELECT id,username,public_id FROM users ORDER BY id"
        ).fetchall()
        for owner, _, _ in owners:
            raw = f"df_synthetic_restore_owner_{owner}"
            connection.execute("DELETE FROM api_tokens WHERE user_id=?", (owner,))
            connection.execute(
                "INSERT INTO api_tokens(user_id,name,token_hash,prefix,created_at) VALUES(?,'restore-probe',?,?,'2026-10-11')",
                (owner, hash_token(raw), raw[:11]),
            )
        connection.commit()
    before_tokens = {}
    async with reopened_client(source) as client:
        for owner, username, public in owners:
            before_tokens[owner] = await login(client, username)
            assert before_tokens[owner]["user_id"] == public
    target, root = tmp_path / "restored.db", tmp_path / "restored-assets"
    root.mkdir()
    if kind == "physical":
        archive, _ = create_backup(
            source, tmp_path / "backups", asset_root=source_root, kind="manual"
        )
    else:
        archive = export_archive(
            f"sqlite:///{source}", tmp_path / "archive.zip", asset_root=source_root
        )
    # Model a password reset after backup. Neither revoked v1 nor current v2 JWTs
    # may revive on restoring that backup, even with unchanged signing secret.
    with closing(sqlite3.connect(source)) as connection:
        connection.execute("UPDATE users SET auth_version=2")
        connection.commit()
    after_tokens = {}
    async with reopened_client(source) as client:
        for owner, username, _ in owners:
            after_tokens[owner] = await login(client, username)
            await rejected_pair(client, before_tokens[owner])
    if kind == "physical":
        _, epoch = restore_backup(archive, target, asset_root=root)
    else:
        epoch = import_archive(migrate(target), archive, asset_root=root)
    inspection = inspect_database(target, asset_root=root)
    assert inspection.valid and inspection.sync_epoch == epoch
    for entry, content in entries:
        assert (
            AssetFiles(root).read(entry.owner_public_id, entry.blob, entry.profile)
            == content
        )
    with closing(sqlite3.connect(target)) as connection:
        restored_ids = dict(connection.execute("SELECT public_id,id FROM users"))
        if kind == "logical":
            assert restored_ids[owners[-1][2]] == 3
            assert restored_ids[owners[0][2]] == 1
        else:
            assert restored_ids == {public: owner for owner, _, public in owners}
    async with reopened_client(target) as client:
        legacy = {"sub": "3", "ver": 1}
        await rejected_pair(
            client,
            {
                "access_token": create_access_token(legacy, timedelta(hours=1)),
                "refresh_token": create_refresh_token(legacy),
            },
        )
        for owner, username, public in owners:
            await rejected_pair(client, before_tokens[owner])
            await rejected_pair(client, after_tokens[owner])
            old_claims = verify_token(before_tokens[owner]["access_token"])
            assert old_claims is not None
            assert old_claims["epoch"] != epoch
            # Even a correctly signed current epoch cannot make an integer
            # collision authorize the wrong stable account.
            if kind == "logical" and owner == 3:
                wrong_owner = old_claims | {"epoch": epoch}
                await rejected_pair(
                    client,
                    {
                        "access_token": create_access_token(wrong_owner),
                        "refresh_token": create_refresh_token(wrong_owner),
                    },
                )
            api = await client.get(
                "/api/v1/auth/users/me",
                headers={"Authorization": f"Token df_synthetic_restore_owner_{owner}"},
            )
            assert api.status_code == 200 and api.json()["public_id"] == public
            assert api.json()["id"] == restored_ids[public]
            fresh = await login(client, username)
            fresh_claims = verify_token(fresh["access_token"])
            assert fresh_claims is not None
            assert fresh_claims["uid"] == public and fresh_claims["epoch"] == epoch
            me = await client.get(
                "/api/v1/auth/users/me",
                headers={"Authorization": "Bearer " + fresh["access_token"]},
            )
            assert me.status_code == 200 and me.json()["public_id"] == public
            refreshed = await client.post(
                "/api/v1/auth/refresh", json={"refresh_token": fresh["refresh_token"]}
            )
            assert (
                refreshed.status_code == 200 and refreshed.json()["user_id"] == public
            )
