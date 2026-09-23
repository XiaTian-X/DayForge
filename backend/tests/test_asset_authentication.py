from datetime import UTC, datetime, timedelta

from fastapi import HTTPException
import pytest
from sqlalchemy import text, update
from sqlalchemy.ext.asyncio import async_sessionmaker

from src.auth.dependencies import authenticate_header
from src.auth.models import User
from src.auth.service import create_access_token
from src.tokens.models import ApiToken
from src.tokens.service import generate_token, hash_token
from tests.account_fixtures import account_password_hash


@pytest.fixture
async def credentials(runtime_engine):
    sessions = async_sessionmaker(runtime_engine, expire_on_commit=False)
    raw = generate_token()
    async with sessions.begin() as session:
        user = User(
            username="asset-authorization", password_hash=account_password_hash()
        )
        session.add(user)
        await session.flush()
        assert user.id is not None
        session.add(
            ApiToken(
                user_id=user.id,
                name="asset-probe",
                token_hash=hash_token(raw),
                prefix=raw[:11],
            )
        )
        access = create_access_token({"sub": str(user.id), "ver": user.auth_version})
    return sessions, {"jwt": "Bearer " + access, "api": "Token " + raw}, user.id


@pytest.mark.parametrize("kind", ["jwt", "api"])
async def test_initial_auth_is_read_only_and_an_independent_writer_is_not_held(
    credentials, kind
):
    sessions, headers, owner = credentials
    async with sessions() as reading:
        user = await authenticate_header(headers[kind], reading, record_token_use=False)
        assert user.id == owner
        assert not reading.dirty
        assert (
            await reading.execute(text("SELECT last_used_at FROM api_tokens"))
        ).scalar_one() is None
        # Real concurrent SQLite connection: auth must not hold a writer lock.
        async with sessions.begin() as writer:
            await writer.execute(
                text("UPDATE users SET auth_version=auth_version+1 WHERE id=:owner"),
                {"owner": owner},
            )
    async with sessions() as fresh:
        assert (
            await fresh.execute(text("SELECT last_used_at FROM api_tokens"))
        ).scalar_one() is None


@pytest.mark.parametrize("kind", ["jwt", "api"])
@pytest.mark.parametrize("change", ["inactive", "status", "credential"])
async def test_fresh_authorization_rejects_changes_during_file_stage(
    credentials, kind, change
):
    sessions, headers, owner = credentials
    async with sessions() as initial:
        old_user = await authenticate_header(
            headers[kind], initial, record_token_use=False
        )
        assert old_user.id == owner
    async with sessions.begin() as writer:
        statement = {
            "inactive": "UPDATE users SET is_active=0 WHERE id=:owner",
            "status": "UPDATE users SET status='disabled' WHERE id=:owner",
            "credential": "UPDATE users SET auth_version=auth_version+1 WHERE id=:owner"
            if kind == "jwt"
            else "DELETE FROM api_tokens WHERE user_id=:owner",
        }[change]
        await writer.execute(text(statement), {"owner": owner})
    # The old ORM object is intentionally still active: it cannot authorize.
    assert old_user.is_active and old_user.status == "active"
    async with sessions.begin() as final:
        with pytest.raises(HTTPException) as caught:
            await authenticate_header(headers[kind], final, record_token_use=False)
        assert caught.value.status_code == 401


async def test_api_token_expiration_is_rechecked_in_final_snapshot(credentials):
    sessions, headers, _ = credentials
    async with sessions() as initial:
        await authenticate_header(headers["api"], initial, record_token_use=False)
    async with sessions.begin() as writer:
        await writer.execute(
            update(ApiToken).values(expires_at=datetime.now(UTC) - timedelta(days=1))
        )
    async with sessions() as final:
        with pytest.raises(HTTPException) as caught:
            await authenticate_header(headers["api"], final)
        assert caught.value.status_code == 401


async def test_final_token_use_only_persists_if_caller_transaction_commits(credentials):
    sessions, headers, _ = credentials
    async with sessions() as first:
        await authenticate_header(headers["api"], first)
        assert (
            await first.execute(text("SELECT last_used_at FROM api_tokens"))
        ).scalar_one() is not None
        await first.rollback()
    async with sessions() as check:
        assert (
            await check.execute(text("SELECT last_used_at FROM api_tokens"))
        ).scalar_one() is None
    async with sessions.begin() as final:
        await authenticate_header(headers["api"], final)
    async with sessions() as check:
        assert (
            await check.execute(text("SELECT last_used_at FROM api_tokens"))
        ).scalar_one() is not None
