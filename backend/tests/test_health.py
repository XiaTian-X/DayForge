"""Health check endpoint tests."""

import pytest
from httpx import AsyncClient


@pytest.mark.asyncio
async def test_health_check_returns_200(test_client: AsyncClient):
    """Test health check endpoint returns 200 status."""
    response = await test_client.get("/health")
    assert response.status_code == 200


@pytest.mark.asyncio
async def test_health_check_returns_ok_status(test_client: AsyncClient):
    """Test health check endpoint returns expected JSON structure."""
    response = await test_client.get("/health")
    data = response.json()
    assert data["status"] == "ok"


@pytest.mark.asyncio
async def test_root_endpoint_returns_200(test_client: AsyncClient):
    """Test root endpoint returns 200 status."""
    response = await test_client.get("/")
    assert response.status_code == 200


@pytest.mark.asyncio
async def test_root_endpoint_returns_message(test_client: AsyncClient):
    """Test root endpoint returns expected message."""
    response = await test_client.get("/")
    data = response.json()
    assert data["message"] == "DayForge API"
    assert data["status"] == "running"
