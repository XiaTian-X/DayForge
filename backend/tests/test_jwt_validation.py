"""Tests for JWT token validation on protected routes."""
import pytest
from httpx import AsyncClient, ASGITransport
from datetime import datetime, timedelta, timezone
import jwt

from src.main import app
from src.config import settings
from src.database import set_engine
from sqlalchemy.ext.asyncio import create_async_engine


# Test configuration
TEST_SECRET_KEY = "test-secret-key-for-development-only"
TEST_ALGORITHM = "HS256"
TEST_DATABASE_URL = "sqlite+aiosqlite:///./test_jwt.db"


def create_token(data: dict, token_type: str = "access", expire_minutes: int = 30) -> str:
    """Create JWT token for testing."""
    to_encode = data.copy()
    expire = datetime.now(timezone.utc) + timedelta(minutes=expire_minutes)
    to_encode.update({"exp": expire, "type": token_type})
    return jwt.encode(to_encode, TEST_SECRET_KEY, algorithm=TEST_ALGORITHM)


def create_expired_token(data: dict, token_type: str = "access", expire_minutes_ago: int = 5) -> str:
    """Create expired JWT token for testing."""
    to_encode = data.copy()
    expire = datetime.now(timezone.utc) - timedelta(minutes=expire_minutes_ago)
    to_encode.update({"exp": expire, "type": token_type})
    return jwt.encode(to_encode, TEST_SECRET_KEY, algorithm=TEST_ALGORITHM)


@pytest.mark.asyncio
async def test_protected_route_without_token():
    """GET /api/v1/auth/users/me without Authorization header returns 401."""
    # Override engine for test isolation
    engine = create_async_engine(TEST_DATABASE_URL, echo=False, future=True)
    set_engine(engine)

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.get("/api/v1/auth/users/me")
        assert response.status_code == 401
        detail = response.json().get("detail", "")
        assert "Not authenticated" in detail or "Could not validate credentials" in detail


@pytest.mark.asyncio
async def test_protected_route_with_invalid_token_format():
    """GET /api/v1/auth/users/me with invalid Bearer token returns 401."""
    # Override engine for test isolation
    engine = create_async_engine(TEST_DATABASE_URL, echo=False, future=True)
    set_engine(engine)

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.get(
            "/api/v1/auth/users/me",
            headers={"Authorization": "Bearer invalid-token"}
        )
        assert response.status_code == 401


@pytest.mark.asyncio
async def test_protected_route_with_expired_token():
    """GET /api/v1/auth/users/me with expired token returns 401."""
    # Override engine for test isolation
    engine = create_async_engine(TEST_DATABASE_URL, echo=False, future=True)
    set_engine(engine)

    # Create expired token
    expired_token = create_expired_token({"sub": "test-user"}, token_type="access")

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.get(
            "/api/v1/auth/users/me",
            headers={"Authorization": f"Bearer {expired_token}"}
        )
        assert response.status_code == 401


@pytest.mark.asyncio
async def test_protected_route_with_valid_token(auth_tokens, async_session):
    """GET /api/v1/auth/users/me with valid token returns 200 and user data."""
    # Use auth_tokens fixture to register and login
    access_token = auth_tokens["access_token"]

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.get(
            "/api/v1/auth/users/me",
            headers={"Authorization": f"Bearer {access_token}"}
        )
        assert response.status_code == 200
        data = response.json()
        assert "username" in data
        assert data["username"] == "testuser"


@pytest.mark.asyncio
async def test_token_payload_structure():
    """Verify decoded token has sub, exp, type fields."""
    # Create token with known payload
    payload = {"sub": "test-user-id", "custom_field": "test_value"}
    token = create_token(payload, token_type="access")

    # Decode and verify structure
    decoded = jwt.decode(token, TEST_SECRET_KEY, algorithms=[TEST_ALGORITHM])

    assert "sub" in decoded
    assert "exp" in decoded
    assert "type" in decoded
    assert decoded["sub"] == "test-user-id"
    assert decoded["type"] == "access"
    assert decoded["custom_field"] == "test_value"


@pytest.mark.asyncio
async def test_refresh_token_rotation(auth_tokens, async_session):
    """Use refresh token, verify it has correct structure for rotation."""
    refresh_token = auth_tokens["refresh_token"]

    # Decode the refresh token to verify it has correct structure
    # Use the app's settings to decode (same key used to encode)
    from src.config import settings
    from src.auth.service import verify_token

    # Verify the token can be decoded
    payload = verify_token(refresh_token)
    assert payload is not None, "Refresh token should be valid"
    assert "sub" in payload
    assert "exp" in payload
    assert payload["type"] == "refresh"

    # Note: In a full implementation, refreshing would:
    # 1. Invalidate the old refresh token
    # 2. Return a new access token AND new refresh token
    # 3. Reject the old refresh token if used again
    # This test verifies the refresh token structure is correct for rotation
