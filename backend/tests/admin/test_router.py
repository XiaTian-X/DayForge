"""Tests for admin router endpoints."""
import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from src.auth.models import User
from src.auth.service import get_password_hash


@pytest.fixture
async def admin_user(async_session: AsyncSession) -> User:
    """Create an admin user in the database."""
    user = User(
        username="admin",
        email="admin@example.com",
        password_hash=get_password_hash("AdminPass123!"),
        is_admin=True,
        is_active=True,
    )
    async_session.add(user)
    await async_session.commit()
    await async_session.refresh(user)
    return user


@pytest.fixture
async def regular_user(async_session: AsyncSession) -> User:
    """Create a regular user in the database."""
    user = User(
        username="regular",
        email="regular@example.com",
        password_hash=get_password_hash("UserPass123!"),
        is_admin=False,
        is_active=True,
    )
    async_session.add(user)
    await async_session.commit()
    await async_session.refresh(user)
    return user


@pytest.fixture
async def admin_token(test_client: AsyncClient, admin_user: User) -> str:
    """Get auth token for admin user."""
    response = await test_client.post(
        "/api/v1/auth/login",
        json={"username": "admin", "password": "AdminPass123!"},
    )
    assert response.status_code == 200
    return response.json()["access_token"]


@pytest.fixture
async def user_token(test_client: AsyncClient, regular_user: User) -> str:
    """Get auth token for regular user."""
    response = await test_client.post(
        "/api/v1/auth/login",
        json={"username": "regular", "password": "UserPass123!"},
    )
    assert response.status_code == 200
    return response.json()["access_token"]


class TestListUsers:
    """Test GET /admin/users endpoint."""

    async def test_list_users_requires_auth(self, test_client: AsyncClient):
        """Test that list users requires authentication."""
        response = await test_client.get("/api/v1/admin/users")
        assert response.status_code == 401

    async def test_list_users_requires_admin(
        self, test_client: AsyncClient, user_token: str
    ):
        """Test that list users requires admin role."""
        response = await test_client.get(
            "/api/v1/admin/users",
            headers={"Authorization": f"Bearer {user_token}"},
        )
        assert response.status_code == 403

    async def test_list_users_returns_all_users(
        self,
        test_client: AsyncClient,
        admin_token: str,
        admin_user: User,
        regular_user: User,
    ):
        """Test that admin can list all users."""
        response = await test_client.get(
            "/api/v1/admin/users",
            headers={"Authorization": f"Bearer {admin_token}"},
        )
        assert response.status_code == 200
        users = response.json()
        assert len(users) >= 2
        usernames = [u["username"] for u in users]
        assert "admin" in usernames
        assert "regular" in usernames


class TestCreateUser:
    async def test_admin_can_create_login_account(
        self, test_client: AsyncClient, admin_token: str
    ):
        response = await test_client.post(
            "/api/v1/admin/users",
            json={"username": "family_member", "password": "MemberPass123!"},
            headers={"Authorization": f"Bearer {admin_token}"},
        )
        assert response.status_code == 201, response.text
        body = response.json()
        assert body["username"] == "family_member"
        assert body["public_id"]
        assert body["is_admin"] is False
        assert body["status"] == "active"

        login = await test_client.post(
            "/api/v1/auth/login",
            json={"username": "family_member", "password": "MemberPass123!"},
        )
        assert login.status_code == 200
        assert login.json()["user_id"] == body["public_id"]

    async def test_regular_user_cannot_create_account(
        self, test_client: AsyncClient, user_token: str
    ):
        response = await test_client.post(
            "/api/v1/admin/users",
            json={"username": "forbidden", "password": "MemberPass123!"},
            headers={"Authorization": f"Bearer {user_token}"},
        )
        assert response.status_code == 403

    async def test_duplicate_username_returns_conflict(
        self, test_client: AsyncClient, admin_token: str, regular_user: User
    ):
        response = await test_client.post(
            "/api/v1/admin/users",
            json={"username": regular_user.username, "password": "MemberPass123!"},
            headers={"Authorization": f"Bearer {admin_token}"},
        )
        assert response.status_code == 409


class TestResetUserPassword:
    """Test POST /admin/users/{user_id}/reset-password endpoint."""

    async def test_reset_password_requires_auth(
        self, test_client: AsyncClient, regular_user: User
    ):
        """Test that reset password requires authentication."""
        response = await test_client.post(
            f"/api/v1/admin/users/{regular_user.id}/reset-password",
            json={"new_password": "NewPass123!"},
        )
        assert response.status_code == 401

    async def test_reset_password_requires_admin(
        self, test_client: AsyncClient, user_token: str, regular_user: User
    ):
        """Test that reset password requires admin role."""
        response = await test_client.post(
            f"/api/v1/admin/users/{regular_user.id}/reset-password",
            json={"new_password": "NewPass123!"},
            headers={"Authorization": f"Bearer {user_token}"},
        )
        assert response.status_code == 403

    async def test_reset_password_user_not_found(
        self, test_client: AsyncClient, admin_token: str
    ):
        """Test reset password for non-existent user returns 404."""
        response = await test_client.post(
            "/api/v1/admin/users/99999/reset-password",
            json={"new_password": "NewPass123!"},
            headers={"Authorization": f"Bearer {admin_token}"},
        )
        assert response.status_code == 404

    async def test_reset_password_success(
        self,
        test_client: AsyncClient,
        admin_token: str,
        regular_user: User,
    ):
        """Test admin can reset user password."""
        old_login = await test_client.post(
            "/api/v1/auth/login",
            json={"username": "regular", "password": "UserPass123!"},
        )
        old_access_token = old_login.json()["access_token"]

        response = await test_client.post(
            f"/api/v1/admin/users/{regular_user.id}/reset-password",
            json={"new_password": "NewPass123!"},
            headers={"Authorization": f"Bearer {admin_token}"},
        )
        assert response.status_code == 200
        assert response.json()["message"] == "Password reset successfully"

        # Verify new password works
        login_response = await test_client.post(
            "/api/v1/auth/login",
            json={"username": "regular", "password": "NewPass123!"},
        )
        assert login_response.status_code == 200

        revoked = await test_client.get(
            "/api/v1/auth/users/me",
            headers={"Authorization": f"Bearer {old_access_token}"},
        )
        assert revoked.status_code == 401


class TestUpdateUserStatus:
    """Test PUT /admin/users/{user_id}/status endpoint."""

    async def test_update_status_requires_auth(
        self, test_client: AsyncClient, regular_user: User
    ):
        """Test that update status requires authentication."""
        response = await test_client.put(
            f"/api/v1/admin/users/{regular_user.id}/status",
            json={"is_active": False},
        )
        assert response.status_code == 401

    async def test_update_status_requires_admin(
        self, test_client: AsyncClient, user_token: str, regular_user: User
    ):
        """Test that update status requires admin role."""
        response = await test_client.put(
            f"/api/v1/admin/users/{regular_user.id}/status",
            json={"is_active": False},
            headers={"Authorization": f"Bearer {user_token}"},
        )
        assert response.status_code == 403

    async def test_update_status_user_not_found(
        self, test_client: AsyncClient, admin_token: str
    ):
        """Test update status for non-existent user returns 404."""
        response = await test_client.put(
            "/api/v1/admin/users/99999/status",
            json={"is_active": False},
            headers={"Authorization": f"Bearer {admin_token}"},
        )
        assert response.status_code == 404

    async def test_disable_user(
        self,
        test_client: AsyncClient,
        admin_token: str,
        regular_user: User,
    ):
        """Test admin can disable a user."""
        response = await test_client.put(
            f"/api/v1/admin/users/{regular_user.id}/status",
            json={"is_active": False},
            headers={"Authorization": f"Bearer {admin_token}"},
        )
        assert response.status_code == 200
        data = response.json()
        assert data["is_active"] is False
        assert data["id"] == regular_user.id

    async def test_enable_user(
        self,
        test_client: AsyncClient,
        admin_token: str,
        regular_user: User,
        async_session: AsyncSession,
    ):
        """Test admin can enable a disabled user."""
        # First disable the user
        regular_user.is_active = False
        await async_session.commit()

        response = await test_client.put(
            f"/api/v1/admin/users/{regular_user.id}/status",
            json={"is_active": True},
            headers={"Authorization": f"Bearer {admin_token}"},
        )
        assert response.status_code == 200
        data = response.json()
        assert data["is_active"] is True

    async def test_disabled_user_cannot_login(
        self,
        test_client: AsyncClient,
        admin_token: str,
        regular_user: User,
    ):
        """Test that disabled user cannot login."""
        # Disable the user
        await test_client.put(
            f"/api/v1/admin/users/{regular_user.id}/status",
            json={"is_active": False},
            headers={"Authorization": f"Bearer {admin_token}"},
        )

        # Try to login
        login_response = await test_client.post(
            "/api/v1/auth/login",
            json={"username": "regular", "password": "UserPass123!"},
        )
        assert login_response.status_code == 403

    async def test_admin_cannot_disable_self(
        self, test_client: AsyncClient, admin_token: str, admin_user: User
    ):
        response = await test_client.put(
            f"/api/v1/admin/users/{admin_user.id}/status",
            json={"is_active": False},
            headers={"Authorization": f"Bearer {admin_token}"},
        )
        assert response.status_code == 400
