"""Actual re-login for the isolated runtime_http owner; never re-sign old JWTs."""

from httpx import AsyncClient

from src.auth.service import verify_token
from tests.account_fixtures import TEST_ACCOUNT_PASSWORD


async def relogin_runtime_headers(
    client: AsyncClient, headers: dict[str, str]
) -> dict[str, str]:
    if not headers["Authorization"].startswith("Bearer "):
        return dict(headers)
    original = verify_token(headers["Authorization"][7:])
    assert original is not None
    response = await client.post(
        "/api/v1/auth/login",
        json={"username": "commit-owner", "password": TEST_ACCOUNT_PASSWORD},
    )
    assert response.status_code == 200, response.text
    assert response.json()["user_id"] == original["uid"]
    return headers | {"Authorization": "Bearer " + response.json()["access_token"]}
