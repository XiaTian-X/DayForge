"""Tests for user login endpoint."""
import pytest
import bcrypt
from unittest.mock import AsyncMock, Mock
from fastapi import HTTPException
from httpx import AsyncClient
from sqlmodel import select
from datetime import datetime, timezone

from src.auth.models import User
from src.auth.service import get_password_hash, create_access_token, create_refresh_token
from src.auth.service import PASSWORD_HASH_PREFIX, verify_password
from src.auth.router import login
from src.auth.schemas import UserLogin


class TestLoginEndpoint:
    """Test POST /api/v1/auth/login endpoint."""

    async def test_legacy_login_upgrades_hash_without_changing_session_version(self, async_session, test_client):
        legacy = bcrypt.hashpw(b"legacy password", bcrypt.gensalt()).decode()
        user = User(username="legacy", password_hash=legacy, auth_version=7)
        async_session.add(user)
        await async_session.commit()
        response = await test_client.post("/api/v1/auth/login", json={"username": "legacy", "password": "legacy password"})
        assert response.status_code == 200
        await async_session.commit()
        await async_session.refresh(user)
        assert user.password_hash.startswith(PASSWORD_HASH_PREFIX)
        assert verify_password("legacy password", user.password_hash)
        assert user.auth_version == 7

    @pytest.mark.parametrize("active,password,expected", [(True, "wrong", 401), (False, "legacy password", 403)])
    async def test_unsuccessful_login_does_not_upgrade_legacy_hash(self, async_session, test_client, active, password, expected):
        legacy = bcrypt.hashpw(b"legacy password", bcrypt.gensalt()).decode()
        user = User(username="legacy", password_hash=legacy, is_active=active)
        async_session.add(user)
        await async_session.commit()
        response = await test_client.post("/api/v1/auth/login", json={"username": "legacy", "password": password})
        assert response.status_code == expected
        await async_session.refresh(user)
        assert user.password_hash == legacy

    async def test_full_long_password_is_required_by_login(self, async_session, test_client):
        password = "x" * 72 + "original"
        async_session.add(User(username="long", password_hash=get_password_hash(password)))
        await async_session.commit()
        for supplied, expected in [(password, 200), ("x" * 72 + "different", 401)]:
            response = await test_client.post("/api/v1/auth/login", json={"username": "long", "password": supplied})
            assert response.status_code == expected

    async def test_legacy_upgrade_cannot_overwrite_concurrent_reset(self):
        legacy = bcrypt.hashpw(b"legacy password", bcrypt.gensalt()).decode()
        user = User(id=1, username="legacy", password_hash=legacy, auth_version=4)
        session = AsyncMock()
        session.execute.side_effect = [Mock(scalar=Mock(return_value=user)), Mock(rowcount=0)]
        with pytest.raises(HTTPException) as exc:
            await login(UserLogin(username="legacy", password="legacy password"), session)
        assert exc.value.status_code == 401
        statement = session.execute.call_args.args[0]
        # The conditional write must guard all authentication state read earlier.
        criteria = str(statement.whereclause)
        assert "users.password_hash" in criteria
        assert "users.auth_version" in criteria
        assert "users.is_active" in criteria
        assert "users.status" in criteria

    async def test_login_with_username(self, async_session, test_client):
        """Test login with username returns tokens."""
        # Create user
        user = User(
            username="loginuser",
            email="login@example.com",
            password_hash=get_password_hash("password123")
        )
        async_session.add(user)
        await async_session.commit()

        response = await test_client.post(
            "/api/v1/auth/login",
            json={
                "username": "loginuser",
                "password": "password123"
            }
        )
        assert response.status_code == 200
        data = response.json()
        assert "access_token" in data
        assert "refresh_token" in data
        assert data["token_type"] == "bearer"
        assert data["user_id"]
        assert data["username"] == "loginuser"
        assert data["is_admin"] is False

    async def test_login_invalid_password(self, async_session, test_client):
        """Test login with wrong password returns 401."""
        # Create user
        user = User(
            username="wrongpassuser",
            password_hash=get_password_hash("correctpassword")
        )
        async_session.add(user)
        await async_session.commit()

        response = await test_client.post(
            "/api/v1/auth/login",
            json={
                "username": "wrongpassuser",
                "password": "wrongpassword"
            }
        )
        assert response.status_code == 401

    async def test_login_nonexistent_user(self, test_client):
        """Test login with non-existent username returns 401."""
        response = await test_client.post(
            "/api/v1/auth/login",
            json={
                "username": "nonexistent",
                "password": "anypassword"
            }
        )
        assert response.status_code == 401

    async def test_token_structure(self, async_session, test_client):
        """Test that login response has correct token structure."""
        # Create user
        user = User(
            username="tokenuser",
            password_hash=get_password_hash("password123")
        )
        async_session.add(user)
        await async_session.commit()

        response = await test_client.post(
            "/api/v1/auth/login",
            json={
                "username": "tokenuser",
                "password": "password123"
            }
        )
        assert response.status_code == 200
        data = response.json()

        # Verify token structure
        assert isinstance(data["access_token"], str)
        assert isinstance(data["refresh_token"], str)
        assert data["token_type"] == "bearer"
        assert len(data["access_token"]) > 0
        assert len(data["refresh_token"]) > 0

    async def test_inactive_user_login(self, async_session, test_client):
        """Test login with inactive user returns 403."""
        # Create inactive user
        user = User(
            username="inactiveuser",
            password_hash=get_password_hash("password123"),
            is_active=False
        )
        async_session.add(user)
        await async_session.commit()

        response = await test_client.post(
            "/api/v1/auth/login",
            json={
                "username": "inactiveuser",
                "password": "password123"
            }
        )
        assert response.status_code == 403
