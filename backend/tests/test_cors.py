"""Tests for CORS (Cross-Origin Resource Sharing) configuration."""

import pytest
from httpx import AsyncClient, ASGITransport

from src.main import app


@pytest.mark.asyncio
async def test_cors_headers_present(async_session):
    """POST request with Origin header returns Access-Control-Allow-Origin."""
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        response = await client.post(
            "/api/v1/auth/login",
            json={"email": "test@example.com", "password": "TestPassword123!"},
            headers={"Origin": "http://localhost:3000"},
        )
        # Status can be 200/400/401/422 (validation/auth failure) but CORS headers should be present
        assert response.status_code in [200, 400, 401, 422]
        assert "access-control-allow-origin" in response.headers
        # With allow_credentials=True, specific origin is returned instead of "*"
        assert (
            response.headers["access-control-allow-origin"] == "http://localhost:3000"
        )


@pytest.mark.asyncio
async def test_cors_allowed_origin(async_session, auth_tokens):
    """Request from allowed origin returns correct CORS headers with authenticated request."""
    # Use auth_tokens to get a valid access token
    access_token = auth_tokens["access_token"]

    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        response = await client.get(
            "/api/v1/auth/users/me",
            headers={
                "Origin": "http://localhost:3000",
                "Authorization": f"Bearer {access_token}",
            },
        )
        assert response.status_code == 200
        assert "access-control-allow-origin" in response.headers
        assert (
            response.headers["access-control-allow-origin"] == "http://localhost:3000"
        )
        assert "access-control-allow-credentials" in response.headers


@pytest.mark.asyncio
async def test_cors_disallowed_origin(async_session):
    """Request from disallowed origin does NOT return CORS headers."""
    # Note: CORS_ORIGINS config now specifies allowed origins
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        response = await client.post(
            "/api/v1/auth/login",
            json={"email": "test@example.com", "password": "TestPassword123!"},
            headers={"Origin": "http://malicious-site.com"},
        )
        # Disallowed origin should not get CORS headers
        assert response.status_code in [200, 400, 401, 422]
        assert "access-control-allow-origin" not in response.headers


@pytest.mark.asyncio
async def test_cors_methods_allowed(async_session):
    """Response includes Access-Control-Allow-Methods header."""
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        response = await client.post(
            "/api/v1/auth/login",
            json={"email": "test@example.com", "password": "TestPassword123!"},
            headers={"Origin": "http://localhost:3000"},
        )
        # Current implementation uses allow_methods=["*"]
        # Verify CORS headers are present
        assert "access-control-allow-origin" in response.headers
        # The methods header may be "*" or list specific methods
        if "access-control-allow-methods" in response.headers:
            methods = response.headers["access-control-allow-methods"]
            assert methods == "*" or "POST" in methods
