"""Tests for user and admin token router endpoints."""
import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import select

from src.auth.models import User
from src.auth.service import get_password_hash
from src.tokens.models import ApiToken
from src.tokens.service import hash_token


# ── Fixtures ──────────────────────────────────────────────────────────────────


@pytest.fixture
async def regular_user(async_session: AsyncSession) -> User:
    user = User(
        username="tokenuser",
        email="tokenuser@example.com",
        password_hash=get_password_hash("UserPass123!"),
        is_active=True,
    )
    async_session.add(user)
    await async_session.commit()
    await async_session.refresh(user)
    return user


@pytest.fixture
async def admin_user(async_session: AsyncSession) -> User:
    user = User(
        username="tokenadmin",
        email="tokenadmin@example.com",
        password_hash=get_password_hash("AdminPass123!"),
        is_admin=True,
        is_active=True,
    )
    async_session.add(user)
    await async_session.commit()
    await async_session.refresh(user)
    return user


@pytest.fixture
async def user_token(test_client: AsyncClient, regular_user: User) -> str:
    resp = await test_client.post(
        "/api/v1/auth/login",
        json={"username": "tokenuser", "password": "UserPass123!"},
    )
    assert resp.status_code == 200
    return resp.json()["access_token"]


@pytest.fixture
async def admin_token(test_client: AsyncClient, admin_user: User) -> str:
    resp = await test_client.post(
        "/api/v1/auth/login",
        json={"username": "tokenadmin", "password": "AdminPass123!"},
    )
    assert resp.status_code == 200
    return resp.json()["access_token"]


def auth_header(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


# ── User Token Tests (POST/GET/DELETE /auth/tokens) ───────────────────────────


class TestCreateUserToken:
    """Test POST /api/v1/auth/tokens"""

    async def test_create_token_returns_201(self, test_client, user_token):
        resp = await test_client.post(
            "/api/v1/auth/tokens",
            json={"name": "my-token"},
            headers=auth_header(user_token),
        )
        assert resp.status_code == 201
        data = resp.json()
        assert data["name"] == "my-token"
        assert data["token"].startswith("df_")
        assert data["prefix"] == data["token"][:11]
        assert "id" in data
        assert "created_at" in data

    async def test_create_token_with_expiry(self, test_client, user_token):
        resp = await test_client.post(
            "/api/v1/auth/tokens",
            json={"name": "expiring", "expires_in_days": 30},
            headers=auth_header(user_token),
        )
        assert resp.status_code == 201
        data = resp.json()
        assert data["expires_at"] is not None

    async def test_expiring_token_authenticates_after_sqlite_round_trip(
        self, test_client, user_token
    ):
        create_resp = await test_client.post(
            "/api/v1/auth/tokens",
            json={"name": "e2e-expiring", "expires_in_days": 30},
            headers=auth_header(user_token),
        )
        assert create_resp.status_code == 201

        raw_token = create_resp.json()["token"]
        authenticated_resp = await test_client.get(
            "/api/v1/auth/tokens",
            headers={"Authorization": f"Token {raw_token}"},
        )

        assert authenticated_resp.status_code == 200
        assert [token["name"] for token in authenticated_resp.json()] == ["e2e-expiring"]

    async def test_create_token_without_expiry(self, test_client, user_token):
        resp = await test_client.post(
            "/api/v1/auth/tokens",
            json={"name": "no-expiry"},
            headers=auth_header(user_token),
        )
        assert resp.status_code == 201
        assert resp.json()["expires_at"] is None

    async def test_create_token_requires_auth(self, test_client):
        resp = await test_client.post(
            "/api/v1/auth/tokens",
            json={"name": "unauthorized"},
        )
        assert resp.status_code == 401

    async def test_create_token_stores_hash_not_raw(self, test_client, user_token, async_session):
        resp = await test_client.post(
            "/api/v1/auth/tokens",
            json={"name": "hashed"},
            headers=auth_header(user_token),
        )
        raw_token = resp.json()["token"]
        token_id = resp.json()["id"]

        result = await async_session.execute(select(ApiToken).where(ApiToken.id == token_id))
        stored = result.scalar()
        assert stored.token_hash == hash_token(raw_token)

    async def test_create_token_name_too_long(self, test_client, user_token):
        resp = await test_client.post(
            "/api/v1/auth/tokens",
            json={"name": "x" * 101},
            headers=auth_header(user_token),
        )
        assert resp.status_code == 422

    async def test_create_token_empty_name(self, test_client, user_token):
        resp = await test_client.post(
            "/api/v1/auth/tokens",
            json={"name": ""},
            headers=auth_header(user_token),
        )
        assert resp.status_code == 422

    async def test_create_token_invalid_expiry_zero(self, test_client, user_token):
        resp = await test_client.post(
            "/api/v1/auth/tokens",
            json={"name": "bad", "expires_in_days": 0},
            headers=auth_header(user_token),
        )
        assert resp.status_code == 422

    async def test_create_token_invalid_expiry_too_large(self, test_client, user_token):
        resp = await test_client.post(
            "/api/v1/auth/tokens",
            json={"name": "bad", "expires_in_days": 366},
            headers=auth_header(user_token),
        )
        assert resp.status_code == 422


class TestListUserTokens:
    """Test GET /api/v1/auth/tokens"""

    async def test_list_returns_empty_initially(self, test_client, user_token):
        resp = await test_client.get(
            "/api/v1/auth/tokens",
            headers=auth_header(user_token),
        )
        assert resp.status_code == 200
        assert resp.json() == []

    async def test_list_returns_created_tokens(self, test_client, user_token):
        await test_client.post(
            "/api/v1/auth/tokens",
            json={"name": "t1"},
            headers=auth_header(user_token),
        )
        await test_client.post(
            "/api/v1/auth/tokens",
            json={"name": "t2"},
            headers=auth_header(user_token),
        )
        resp = await test_client.get(
            "/api/v1/auth/tokens",
            headers=auth_header(user_token),
        )
        assert resp.status_code == 200
        tokens = resp.json()
        assert len(tokens) == 2
        names = {t["name"] for t in tokens}
        assert names == {"t1", "t2"}

    async def test_list_does_not_expose_raw_token(self, test_client, user_token):
        await test_client.post(
            "/api/v1/auth/tokens",
            json={"name": "secret"},
            headers=auth_header(user_token),
        )
        resp = await test_client.get(
            "/api/v1/auth/tokens",
            headers=auth_header(user_token),
        )
        for tok in resp.json():
            assert "token" not in tok

    async def test_list_requires_auth(self, test_client):
        resp = await test_client.get("/api/v1/auth/tokens")
        assert resp.status_code == 401

    async def test_list_only_shows_own_tokens(self, test_client, user_token, admin_token):
        await test_client.post(
            "/api/v1/auth/tokens",
            json={"name": "user-tok"},
            headers=auth_header(user_token),
        )
        await test_client.post(
            "/api/v1/auth/tokens",
            json={"name": "admin-tok"},
            headers=auth_header(admin_token),
        )
        resp = await test_client.get(
            "/api/v1/auth/tokens",
            headers=auth_header(user_token),
        )
        tokens = resp.json()
        assert len(tokens) == 1
        assert tokens[0]["name"] == "user-tok"


class TestDeleteUserToken:
    """Test DELETE /api/v1/auth/tokens/{token_id}"""

    async def test_delete_own_token(self, test_client, user_token):
        create_resp = await test_client.post(
            "/api/v1/auth/tokens",
            json={"name": "to-delete"},
            headers=auth_header(user_token),
        )
        token_id = create_resp.json()["id"]

        del_resp = await test_client.delete(
            f"/api/v1/auth/tokens/{token_id}",
            headers=auth_header(user_token),
        )
        assert del_resp.status_code == 204

        # Verify gone
        list_resp = await test_client.get(
            "/api/v1/auth/tokens",
            headers=auth_header(user_token),
        )
        assert len(list_resp.json()) == 0

    async def test_delete_nonexistent_returns_404(self, test_client, user_token):
        resp = await test_client.delete(
            "/api/v1/auth/tokens/99999",
            headers=auth_header(user_token),
        )
        assert resp.status_code == 404

    async def test_delete_other_users_token_returns_404(self, test_client, user_token, admin_token):
        create_resp = await test_client.post(
            "/api/v1/auth/tokens",
            json={"name": "admin-owned"},
            headers=auth_header(admin_token),
        )
        token_id = create_resp.json()["id"]

        resp = await test_client.delete(
            f"/api/v1/auth/tokens/{token_id}",
            headers=auth_header(user_token),
        )
        assert resp.status_code == 404

    async def test_delete_requires_auth(self, test_client):
        resp = await test_client.delete("/api/v1/auth/tokens/1")
        assert resp.status_code == 401


# ── Admin Token Tests (POST/GET/DELETE /admin/users/{uid}/tokens) ─────────────


class TestAdminCreateToken:
    """Test POST /api/v1/admin/users/{user_id}/tokens"""

    async def test_admin_creates_token_for_user(self, test_client, admin_token, regular_user):
        resp = await test_client.post(
            f"/api/v1/admin/users/{regular_user.id}/tokens",
            json={"name": "admin-created"},
            headers=auth_header(admin_token),
        )
        assert resp.status_code == 201
        data = resp.json()
        assert data["name"] == "admin-created"
        assert data["token"].startswith("df_")

    async def test_admin_create_token_with_expiry(self, test_client, admin_token, regular_user):
        resp = await test_client.post(
            f"/api/v1/admin/users/{regular_user.id}/tokens",
            json={"name": "expiring", "expires_in_days": 7},
            headers=auth_header(admin_token),
        )
        assert resp.status_code == 201
        assert resp.json()["expires_at"] is not None

    async def test_admin_create_token_nonexistent_user(self, test_client, admin_token):
        resp = await test_client.post(
            "/api/v1/admin/users/99999/tokens",
            json={"name": "ghost"},
            headers=auth_header(admin_token),
        )
        assert resp.status_code == 404

    async def test_admin_create_requires_admin(self, test_client, user_token, regular_user):
        resp = await test_client.post(
            f"/api/v1/admin/users/{regular_user.id}/tokens",
            json={"name": "nope"},
            headers=auth_header(user_token),
        )
        assert resp.status_code == 403

    async def test_admin_create_requires_auth(self, test_client, regular_user):
        resp = await test_client.post(
            f"/api/v1/admin/users/{regular_user.id}/tokens",
            json={"name": "nope"},
        )
        assert resp.status_code == 401


class TestAdminListTokens:
    """Test GET /api/v1/admin/users/{user_id}/tokens"""

    async def test_admin_lists_user_tokens(self, test_client, admin_token, user_token, regular_user):
        # User creates a token
        await test_client.post(
            "/api/v1/auth/tokens",
            json={"name": "my-api-key"},
            headers=auth_header(user_token),
        )
        # Admin lists that user's tokens
        resp = await test_client.get(
            f"/api/v1/admin/users/{regular_user.id}/tokens",
            headers=auth_header(admin_token),
        )
        assert resp.status_code == 200
        tokens = resp.json()
        assert len(tokens) == 1
        assert tokens[0]["name"] == "my-api-key"

    async def test_admin_list_does_not_expose_raw_token(self, test_client, admin_token, user_token, regular_user):
        await test_client.post(
            "/api/v1/auth/tokens",
            json={"name": "key"},
            headers=auth_header(user_token),
        )
        resp = await test_client.get(
            f"/api/v1/admin/users/{regular_user.id}/tokens",
            headers=auth_header(admin_token),
        )
        for tok in resp.json():
            assert "token" not in tok

    async def test_admin_list_requires_admin(self, test_client, user_token, regular_user):
        resp = await test_client.get(
            f"/api/v1/admin/users/{regular_user.id}/tokens",
            headers=auth_header(user_token),
        )
        assert resp.status_code == 403

    async def test_admin_list_requires_auth(self, test_client, regular_user):
        resp = await test_client.get(
            f"/api/v1/admin/users/{regular_user.id}/tokens",
        )
        assert resp.status_code == 401


class TestAdminDeleteToken:
    """Test DELETE /api/v1/admin/users/{user_id}/tokens/{token_id}"""

    async def test_admin_deletes_user_token(self, test_client, admin_token, user_token, regular_user):
        create_resp = await test_client.post(
            "/api/v1/auth/tokens",
            json={"name": "delete-me"},
            headers=auth_header(user_token),
        )
        token_id = create_resp.json()["id"]

        del_resp = await test_client.delete(
            f"/api/v1/admin/users/{regular_user.id}/tokens/{token_id}",
            headers=auth_header(admin_token),
        )
        assert del_resp.status_code == 204

    async def test_admin_delete_nonexistent_returns_404(self, test_client, admin_token, regular_user):
        resp = await test_client.delete(
            f"/api/v1/admin/users/{regular_user.id}/tokens/99999",
            headers=auth_header(admin_token),
        )
        assert resp.status_code == 404

    async def test_admin_delete_requires_admin(self, test_client, user_token, regular_user):
        resp = await test_client.delete(
            f"/api/v1/admin/users/{regular_user.id}/tokens/1",
            headers=auth_header(user_token),
        )
        assert resp.status_code == 403

    async def test_admin_delete_requires_auth(self, test_client, regular_user):
        resp = await test_client.delete(
            f"/api/v1/admin/users/{regular_user.id}/tokens/1",
        )
        assert resp.status_code == 401
