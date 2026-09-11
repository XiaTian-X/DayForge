"""Tests for POST /api/v1/auth/refresh endpoint."""
import pytest
from httpx import AsyncClient
from sqlmodel import select
from datetime import datetime, timezone

from src.auth.models import User
from src.auth.service import (
    get_password_hash,
    create_access_token,
    create_refresh_token,
    verify_token
)
from src.config import settings


class TestRefreshTokenEndpoint:
    """Test POST /api/v1/auth/refresh endpoint."""

    async def test_refresh_token_valid_returns_new_tokens(
        self, async_session, test_client
    ):
        """Test 1: Valid refresh token returns new access and refresh tokens."""
        # Create user
        user = User(
            username="testuser",
            email="refresh1@example.com",
            password_hash=get_password_hash("password123")
        )
        async_session.add(user)
        await async_session.commit()
        await async_session.refresh(user)

        # Create refresh token for the user
        refresh_token = create_refresh_token({"sub": str(user.id), "ver": user.auth_version})

        # Call refresh endpoint
        response = await test_client.post(
            "/api/v1/auth/refresh",
            json={"refresh_token": refresh_token}
        )

        assert response.status_code == 200
        data = response.json()
        assert "access_token" in data
        assert "refresh_token" in data
        assert data["token_type"] == "bearer"

        # Verify new access token is valid and has correct user
        new_access_payload = verify_token(data["access_token"])
        assert new_access_payload is not None
        assert new_access_payload.get("sub") == str(user.id)
        assert new_access_payload.get("type") == "access"

        # Verify new refresh token is valid and has correct type
        new_refresh_payload = verify_token(data["refresh_token"])
        assert new_refresh_payload is not None
        assert new_refresh_payload.get("type") == "refresh"

    async def test_refresh_token_invalid_returns_401(self, test_client):
        """Test 2: Invalid refresh token returns 401."""
        response = await test_client.post(
            "/api/v1/auth/refresh",
            json={"refresh_token": "invalid-token-string"}
        )

        assert response.status_code == 401
        data = response.json()
        assert "detail" in data

    async def test_refresh_token_wrong_type_returns_401(
        self, async_session, test_client
    ):
        """Test 3: Refresh token with wrong type claim (access token) returns 401."""
        # Create user
        user = User(
            username="testuser",
            email="wrongtype@example.com",
            password_hash=get_password_hash("password123")
        )
        async_session.add(user)
        await async_session.commit()
        await async_session.refresh(user)

        # Create an ACCESS token instead of refresh token
        access_token = create_access_token({"sub": str(user.id), "ver": user.auth_version})

        # Try to use access token for refresh
        response = await test_client.post(
            "/api/v1/auth/refresh",
            json={"refresh_token": access_token}
        )

        assert response.status_code == 401
        data = response.json()
        assert "detail" in data

    async def test_refresh_token_nonexistent_user_returns_401(
        self, test_client
    ):
        """Test 4: Refresh token for non-existent user returns 401."""
        # Create a valid refresh token for a non-existent user ID
        refresh_token = create_refresh_token({"sub": "99999", "ver": 1})

        response = await test_client.post(
            "/api/v1/auth/refresh",
            json={"refresh_token": refresh_token}
        )

        assert response.status_code == 401
        data = response.json()
        assert "detail" in data
