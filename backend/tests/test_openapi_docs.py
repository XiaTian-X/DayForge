"""Tests for OpenAPI documentation endpoints."""
import pytest
from httpx import AsyncClient, ASGITransport
from src.main import app


@pytest.mark.asyncio
async def test_get_docs_returns_200():
    """GET /docs returns 200 status code."""
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        response = await ac.get("/docs")
        assert response.status_code == 200


@pytest.mark.asyncio
async def test_docs_contains_swagger():
    """GET /docs response contains 'Swagger UI' in text."""
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        response = await ac.get("/docs")
        assert response.status_code == 200
        assert "Swagger UI" in response.text


@pytest.mark.asyncio
async def test_get_openapi_json():
    """GET /openapi.json returns valid JSON with openapi version."""
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        response = await ac.get("/openapi.json")
        assert response.status_code == 200
        data = response.json()
        assert "openapi" in data
        assert data["openapi"].startswith("3.")


@pytest.mark.asyncio
async def test_openapi_has_auth_paths():
    """OpenAPI exposes admin creation and does not expose self-registration."""
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        response = await ac.get("/openapi.json")
        assert response.status_code == 200
        data = response.json()
        paths = data.get("paths", {})
        assert "/api/v1/auth/login" in paths, "Missing /api/v1/auth/login path"
        assert "/api/v1/admin/users" in paths, "Missing admin user management path"
        assert "/api/v1/auth/register" not in paths
        assert "/api/v1/auth/password-reset/request" not in paths


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "path",
    [
        "/api/v1/auth/register",
        "/api/v1/auth/password-reset/request",
        "/api/v1/auth/password-reset/confirm",
    ],
)
async def test_public_account_mutation_routes_are_removed(path):
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        response = await ac.post(path, json={})
        assert response.status_code == 404
