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
            json={"username": "nonexistent-user", "password": "TestPassword123!"},
            headers={"Origin": "http://localhost:3000"},
        )
        # A valid login shape reaches authentication even when the user is absent.
        assert response.status_code == 401
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
            json={"username": "nonexistent-user", "password": "TestPassword123!"},
            headers={"Origin": "http://malicious-site.com"},
        )
        # Disallowed origin should not get CORS headers
        assert response.status_code == 401
        assert "access-control-allow-origin" not in response.headers


@pytest.mark.asyncio
@pytest.mark.parametrize("method", ["GET", "POST", "PUT", "PATCH", "DELETE"])
async def test_cors_methods_allowed(method):
    """Browser preflight must authorize the method and authentication headers."""
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        response = await client.options(
            "/api/v1/auth/login",
            headers={
                "Origin": "http://localhost:3000",
                "Access-Control-Request-Method": method,
                "Access-Control-Request-Headers": "authorization,content-type",
            },
        )
    assert response.status_code == 200, response.text
    assert response.headers["access-control-allow-origin"] == "http://localhost:3000"
    assert response.headers["access-control-allow-credentials"] == "true"
    assert method in {
        m.strip() for m in response.headers["access-control-allow-methods"].split(",")
    }
    allowed_headers = response.headers["access-control-allow-headers"].lower()
    assert "authorization" in allowed_headers
    assert "content-type" in allowed_headers


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "origin,method",
    [
        ("http://malicious-site.com", "POST"),
        ("http://localhost:3000", "TRACE"),
    ],
)
async def test_cors_rejects_disallowed_preflight(origin, method):
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        response = await client.options(
            "/api/v1/auth/login",
            headers={"Origin": origin, "Access-Control-Request-Method": method},
        )
    assert response.status_code == 400
    if origin != "http://localhost:3000":
        assert "access-control-allow-origin" not in response.headers
