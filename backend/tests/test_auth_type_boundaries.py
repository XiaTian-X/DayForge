"""Behavior contracts for authentication and database-to-response type boundaries."""

from datetime import timedelta
from unittest.mock import AsyncMock
from uuid import UUID

import pytest
from pydantic import ValidationError

from src.auth.models import User
from src.auth.router import _token_response
from src.auth.service import create_access_token, create_refresh_token, verify_token
from src.time_utils import utc_now
from src.tokens.models import ApiToken
from src.tokens.router import create_token
from src.tokens.schemas import TokenCreate
from src.tokens.service import hash_token
from tests.account_fixtures import account_password_hash


@pytest.mark.parametrize(
    "public_id",
    [
        "abcdef12-3456-4789-abcd-123456789012",
        "ABCDEF12-3456-4789-ABCD-123456789012",
        "abcdef1234564789abcd123456789012",
    ],
)
def test_auth_response_validates_uuid_without_changing_internal_jwt_subject(public_id):
    user = User(
        id=17,
        public_id=public_id,
        username="boundary",
        password_hash="unused",
        auth_version=4,
    )
    response = _token_response(user)
    assert response.user_id == UUID(public_id)
    assert response.model_dump(mode="json")["user_id"] == str(UUID(public_id))
    for token, kind in [
        (response.access_token, "access"),
        (response.refresh_token, "refresh"),
    ]:
        payload = verify_token(token)
        assert payload["sub"] == "17"
        assert payload["ver"] == 4
        assert payload["type"] == kind


@pytest.mark.parametrize("public_id", ["not-a-uuid", ""])
def test_auth_response_rejects_invalid_stored_uuid(public_id):
    user = User(id=17, public_id=public_id, username="boundary", password_hash="unused")
    with pytest.raises(ValidationError) as error:
        _token_response(user)
    assert error.value.errors()[0]["loc"] == ("user_id",)
    assert error.value.errors()[0]["type"] == "uuid_parsing"


@pytest.mark.parametrize("assigned_id", [None, 91])
async def test_user_token_response_requires_generated_id_after_commit_and_refresh(
    assigned_id,
):
    user = User(id=17, username="boundary", password_hash="unused")
    session = AsyncMock()
    timeline = []

    async def commit():
        timeline.append("commit")

    async def refresh(token):
        timeline.append("refresh")
        token.id = assigned_id

    # AsyncSession.add is synchronous; this test isolates response validation,
    # while the HTTP/SQLite suites cover genuine persistence and commit failures.
    session.add = lambda token: timeline.append("add")
    session.commit.side_effect = commit
    session.refresh.side_effect = refresh
    if assigned_id is None:
        with pytest.raises(ValidationError) as error:
            await create_token(TokenCreate(name="boundary"), user, session)
        assert error.value.errors()[0]["loc"] == ("id",)
        assert error.value.errors()[0]["type"] == "int_type"
    else:
        response = await create_token(TokenCreate(name="boundary"), user, session)
        assert response.id == assigned_id
        assert response.name == "boundary"
        assert response.prefix == response.token[:11]
        assert response.expires_at is None
        assert response.model_dump(mode="json")["created_at"].endswith("Z")
    assert timeline == ["add", "commit", "refresh"]


@pytest.mark.parametrize("state", ["active", "inactive", "disabled"])
async def test_jwt_refresh_and_api_token_resolve_the_same_account_status(
    test_client, async_session, state
):
    user = User(
        id=17,
        username="boundary",
        password_hash=account_password_hash(),
        is_active=state != "inactive",
        status="disabled" if state == "disabled" else "active",
        auth_version=4,
    )
    async_session.add(user)
    await async_session.flush()
    raw_token = "df_synthetic_auth_boundary_only"
    token = ApiToken(
        id=91,
        user_id=user.id,
        name="boundary",
        token_hash=hash_token(raw_token),
        prefix=raw_token[:11],
    )
    async_session.add(token)
    await async_session.commit()
    claims = {"sub": str(user.id), "ver": user.auth_version}
    expected = 200 if state == "active" else 401
    for credentials in [f"Bearer {create_access_token(claims)}", f"Token {raw_token}"]:
        response = await test_client.get(
            "/api/v1/auth/users/me", headers={"Authorization": credentials}
        )
        assert response.status_code == expected
        if expected == 200:
            assert response.json()["id"] == 17
            assert response.json()["public_id"] == user.public_id
            assert "password_hash" not in response.json()
        else:
            assert response.headers["www-authenticate"] == "Bearer"
    refreshed = await test_client.post(
        "/api/v1/auth/refresh", json={"refresh_token": create_refresh_token(claims)}
    )
    assert refreshed.status_code == expected
    if expected == 200:
        assert refreshed.json()["user_id"] == user.public_id


async def test_expired_api_token_is_rejected_without_updating_last_used(
    test_client, async_session
):
    user = User(username="expired-boundary", password_hash=account_password_hash())
    async_session.add(user)
    await async_session.flush()
    raw_token = "df_synthetic_expired_boundary_only"
    token = ApiToken(
        user_id=user.id,
        name="expired",
        token_hash=hash_token(raw_token),
        prefix=raw_token[:11],
        expires_at=utc_now() - timedelta(days=1),
    )
    async_session.add(token)
    await async_session.commit()
    response = await test_client.get(
        "/api/v1/auth/users/me", headers={"Authorization": f"Token {raw_token}"}
    )
    assert response.status_code == 401
    await async_session.refresh(token)
    assert token.last_used_at is None
