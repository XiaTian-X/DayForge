"""Tests for user login endpoint."""
import pytest
from httpx import AsyncClient
from sqlmodel import select
from datetime import datetime, timezone

from src.auth.models import User
from src.auth.service import get_password_hash, create_access_token, create_refresh_token


class TestLoginEndpoint:
    """Test POST /api/v1/auth/login endpoint."""

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
