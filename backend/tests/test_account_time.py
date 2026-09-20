"""Account/token UTC behavior across response, SQLite and server timezone boundaries."""

from datetime import UTC, datetime, timedelta, timezone
from zoneinfo import ZoneInfo
import time

import pytest
from pydantic import TypeAdapter, ValidationError
from sqlalchemy import text

from src.admin.schemas import AdminUserResponse
from src.auth.models import User
from src.auth.service import get_password_hash
from src.time_utils import UTCResponseDatetime, as_utc, utc_now
from src.tokens.models import ApiToken
from src.tokens.schemas import TokenListResponse
from src.tokens.service import verify_token_expiry


@pytest.fixture(params=["UTC", "Asia/Shanghai", "America/Los_Angeles"])
def server_timezone(request, monkeypatch):
    # Restore libc's timezone as well as the environment even if an assertion fails.
    try:
        with monkeypatch.context() as env:
            env.setenv("TZ", request.param)
            time.tzset()
            yield request.param
    finally:
        time.tzset()


def test_utc_factories_ignore_server_timezone(server_timezone):
    before = datetime.now(UTC)
    user = User(username="clock-probe", password_hash="synthetic")
    token = ApiToken(user_id=1, name="probe", prefix="probe", token_hash="synthetic")
    after = datetime.now(UTC)
    for value in (user.created_at, user.updated_at, token.created_at):
        assert value.tzinfo == UTC
        assert before <= value <= after


@pytest.mark.parametrize("fold,expected_hour", [(0, 8), (1, 9)])
def test_normalization_preserves_dst_fold_and_microseconds(fold, expected_hour):
    value = datetime(
        2026, 11, 1, 1, 30, 0, 123456, tzinfo=ZoneInfo("America/Los_Angeles"), fold=fold
    )
    expected = datetime(2026, 11, 1, expected_hour, 30, 0, 123456, tzinfo=UTC)
    assert as_utc(value) == expected
    assert as_utc(value).timestamp() == value.timestamp()


@pytest.mark.parametrize("delta_us,valid", [(-1, False), (0, False), (1, True)])
@pytest.mark.parametrize("offset", [None, 0, 8, -7])
def test_expiry_exact_boundary(monkeypatch, server_timezone, delta_us, valid, offset):
    now = datetime(2026, 11, 1, 9, 0, 0, 123456, tzinfo=UTC)
    monkeypatch.setattr("src.tokens.service.utc_now", lambda: now)
    expiry = now + timedelta(microseconds=delta_us)
    if offset is None:
        expiry = expiry.replace(tzinfo=None)  # Existing SQLite UTC representation.
    else:
        expiry = expiry.astimezone(timezone(timedelta(hours=offset)))
    assert verify_token_expiry(expiry) is valid


@pytest.mark.parametrize("value", ["not-a-time", "2026-99-99T00:00:00Z", None])
def test_response_timestamp_still_rejects_invalid_values(value):
    with pytest.raises(ValidationError):
        TypeAdapter(UTCResponseDatetime).validate_python(value)


@pytest.mark.parametrize("offset", [None, 0, 8, -7])
def test_admin_schema_normalizes_instants_without_mutating_model(offset):
    value = datetime(2026, 9, 13, 23, 30, 0, 123456)
    if offset is not None:
        value = value.replace(tzinfo=timezone(timedelta(hours=offset)))
    user = User(
        id=1,
        username="probe",
        password_hash="private",
        created_at=value,
        updated_at=value,
    )
    expected = value.replace(tzinfo=UTC) if offset is None else value.astimezone(UTC)
    response = AdminUserResponse.model_validate(user)
    for field in ("created_at", "updated_at"):
        assert response.model_dump(mode="json")[field] == expected.isoformat().replace(
            "+00:00", "Z"
        )
    assert user.created_at == value
    assert user.created_at.tzinfo == value.tzinfo
    assert "password_hash" not in response.model_dump()


async def test_sqlite_round_trip_and_onupdate_preserve_utc(
    async_session, server_timezone
):
    user = User(username="utc-roundtrip", password_hash="synthetic")
    created_at, updated_at = user.created_at, user.updated_at
    async_session.add(user)
    await async_session.commit()
    await async_session.refresh(user)
    assert as_utc(user.created_at) == created_at
    assert as_utc(user.updated_at) == updated_at

    before = utc_now()
    user.status = "disabled"
    await async_session.commit()
    await async_session.refresh(user)
    after = utc_now()
    assert as_utc(user.created_at) == created_at
    assert before <= as_utc(user.updated_at) <= after
    raw = (
        await async_session.execute(
            text("SELECT created_at FROM users WHERE id=:id"), {"id": user.id}
        )
    ).scalar_one()
    assert datetime.fromisoformat(raw) == created_at.replace(tzinfo=None)

    token = ApiToken(
        user_id=user.id,
        name="utc",
        prefix="probe",
        token_hash="synthetic",
        expires_at=utc_now() + timedelta(days=1),
        last_used_at=utc_now(),
    )
    original = {
        field: getattr(token, field)
        for field in ("created_at", "expires_at", "last_used_at")
    }
    async_session.add(token)
    await async_session.commit()
    await async_session.refresh(token)
    response = TokenListResponse.model_validate(token)
    for field, value in original.items():
        assert as_utc(getattr(token, field)) == value
        assert response.model_dump(mode="json")[field] == value.isoformat().replace(
            "+00:00", "Z"
        )


@pytest.mark.parametrize("admin_issues_token", [False, True])
async def test_account_and_token_http_round_trip(
    test_client, async_session, server_timezone, admin_issues_token, monkeypatch
):
    admin = User(
        username="utc-admin",
        password_hash=get_password_hash("test-password"),
        is_admin=True,
    )
    async_session.add(admin)
    await async_session.commit()
    login = await test_client.post(
        "/api/v1/auth/login",
        json={"username": "utc-admin", "password": "test-password"},
    )
    assert login.status_code == 200
    admin_headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    before = utc_now()
    created = await test_client.post(
        "/api/v1/admin/users",
        headers=admin_headers,
        json={"username": "utc_member", "password": "test-password"},
    )
    assert created.status_code == 201
    user = created.json()
    for field in ("created_at", "updated_at"):
        assert user[field].endswith("Z")
        assert before <= datetime.fromisoformat(user[field]) <= utc_now()
    login = await test_client.post(
        "/api/v1/auth/login",
        json={"username": "utc_member", "password": "test-password"},
    )
    assert login.status_code == 200
    member_headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    admin_path = f"/api/v1/admin/users/{user['id']}/tokens"
    create_path, headers = (
        (admin_path, admin_headers)
        if admin_issues_token
        else ("/api/v1/auth/tokens", member_headers)
    )
    before = utc_now()
    created = await test_client.post(
        create_path, headers=headers, json={"name": "clock", "expires_in_days": 7}
    )
    assert created.status_code == 201
    token = created.json()
    assert token["last_used_at"] is None
    assert token["created_at"].endswith("Z") and token["expires_at"].endswith("Z")
    assert before <= datetime.fromisoformat(token["created_at"]) <= utc_now()
    assert (
        before + timedelta(days=7)
        <= datetime.fromisoformat(token["expires_at"])
        <= utc_now() + timedelta(days=7)
    )
    for path, headers in (
        ("/api/v1/auth/tokens", member_headers),
        (admin_path, admin_headers),
    ):
        listed = await test_client.get(path, headers=headers)
        assert listed.status_code == 200
        assert listed.json() == [
            {key: value for key, value in token.items() if key != "token"}
        ]

    before = utc_now()
    used = await test_client.get(
        "/api/v1/auth/tokens", headers={"Authorization": f"Token {token['token']}"}
    )
    assert used.status_code == 200
    last_used = used.json()[0]["last_used_at"]
    assert last_used.endswith("Z")
    assert before <= datetime.fromisoformat(last_used) <= utc_now()

    with monkeypatch.context() as clock:
        clock.setattr(
            "src.tokens.service.utc_now",
            lambda: datetime.fromisoformat(token["expires_at"]),
        )
        expired = await test_client.get(
            "/api/v1/auth/tokens", headers={"Authorization": f"Token {token['token']}"}
        )
        assert expired.status_code == 401

    changed = await test_client.put(
        f"/api/v1/admin/users/{user['id']}/status",
        headers=admin_headers,
        json={"is_active": False},
    )
    assert changed.status_code == 200
    assert changed.json()["created_at"] == user["created_at"]
    assert changed.json()["updated_at"].endswith("Z")
    listed = await test_client.get("/api/v1/admin/users", headers=admin_headers)
    assert listed.status_code == 200
    assert (
        next(item for item in listed.json() if item["id"] == user["id"])
        == changed.json()
    )


async def test_legacy_naive_rows_are_rendered_without_rewrite(
    test_client, async_session, server_timezone
):
    old = datetime(2020, 1, 1, 23, 59, 59, 123456)
    user = User(
        username="legacy-utc",
        password_hash=get_password_hash("test-password"),
        is_admin=True,
        created_at=old,
        updated_at=old,
    )
    async_session.add(user)
    await async_session.flush()
    token = ApiToken(
        user_id=user.id,
        name="legacy",
        prefix="probe",
        token_hash="synthetic-legacy",
        created_at=old,
        last_used_at=old,
    )
    async_session.add(token)
    await async_session.commit()
    raw_query = text(
        "SELECT created_at, last_used_at, expires_at FROM api_tokens WHERE id=:id"
    )
    before = (await async_session.execute(raw_query, {"id": token.id})).one()
    login = await test_client.post(
        "/api/v1/auth/login",
        json={"username": "legacy-utc", "password": "test-password"},
    )
    assert login.status_code == 200
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    for path in ("/api/v1/auth/tokens", f"/api/v1/admin/users/{user.id}/tokens"):
        response = await test_client.get(path, headers=headers)
        assert response.status_code == 200
        assert response.json()[0]["created_at"] == "2020-01-01T23:59:59.123456Z"
        assert response.json()[0]["last_used_at"] == "2020-01-01T23:59:59.123456Z"
        assert response.json()[0]["expires_at"] is None
    response = await test_client.get("/api/v1/admin/users", headers=headers)
    assert response.status_code == 200
    assert response.json()[0]["created_at"] == "2020-01-01T23:59:59.123456Z"
    assert response.json()[0]["updated_at"] == "2020-01-01T23:59:59.123456Z"
    assert (await async_session.execute(raw_query, {"id": token.id})).one() == before
