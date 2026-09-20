"""Household response UTC contract across real HTTP/SQLite request boundaries."""

from datetime import UTC, datetime
from importlib import import_module
from uuid import UUID

import pytest
from pydantic import ValidationError
from sqlalchemy import text
from sqlalchemy.ext.asyncio import async_sessionmaker

from src.admin.schemas import AdminHouseholdMemberResponse
from src.auth.models import User
from tests.account_fixtures import TEST_ACCOUNT_PASSWORD, account_password_hash
from tests.test_account_time import server_timezone as server_timezone


def member_response(joined_at):
    return AdminHouseholdMemberResponse(
        membership_id=UUID(int=1),
        user_id=UUID(int=2),
        username="member",
        role="member",
        status="active",
        joined_at=joined_at,
    )


@pytest.mark.parametrize(
    "value,expected",
    [
        (None, None),
        ("2026-09-19T23:30:00.123456", "2026-09-19T23:30:00.123456Z"),
        ("2026-09-19T23:30:00.123456Z", "2026-09-19T23:30:00.123456Z"),
        ("2026-09-20T07:30:00.123456+08:00", "2026-09-19T23:30:00.123456Z"),
        ("2026-09-19T16:30:00.123456-07:00", "2026-09-19T23:30:00.123456Z"),
        ("2026-11-01T01:30:00.123456-07:00", "2026-11-01T08:30:00.123456Z"),
        ("2026-11-01T01:30:00.123456-08:00", "2026-11-01T09:30:00.123456Z"),
    ],
)
def test_member_timestamp_response(value, expected, server_timezone):
    original = datetime.fromisoformat(value) if value is not None else None
    assert member_response(original).model_dump(mode="json")["joined_at"] == expected
    assert member_response(value).model_dump(mode="json")["joined_at"] == expected


@pytest.mark.parametrize("value", ["not-a-time", "2026-99-99T00:00:00Z"])
def test_member_timestamp_rejects_invalid_values(value):
    with pytest.raises(ValidationError):
        member_response(value)


async def membership_snapshot(engine):
    async with engine.connect() as connection:
        rows = await connection.execute(
            text("SELECT * FROM household_memberships ORDER BY id")
        )
        return rows.all()


async def test_household_timestamp_survives_separate_requests(
    runtime_client, runtime_engine, server_timezone, monkeypatch
):
    # Real Alembic schema and production get_session: each HTTP request commits
    # and a later request must reload SQLite's naive UTC representation.
    async with async_sessionmaker(runtime_engine, expire_on_commit=False)() as session:
        admin = User(
            username="household_admin",
            password_hash=account_password_hash(),
            is_admin=True,
        )
        member = User(
            username="household_member", password_hash=account_password_hash()
        )
        session.add_all([admin, member])
        await session.commit()
        member_id, member_public_id = member.id, member.public_id

    login = await runtime_client.post(
        "/api/v1/auth/login",
        json={"username": admin.username, "password": TEST_ACCOUNT_PASSWORD},
    )
    assert login.status_code == 200
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    instant = datetime(2026, 9, 19, 23, 30, 0, 123456, tzinfo=UTC)
    expected = "2026-09-19T23:30:00.123456Z"
    admin_router = import_module("src.admin.router")
    monkeypatch.setattr(admin_router, "utc_now", lambda: instant)
    base = "/api/v1/admin/households"
    created = await runtime_client.post(base, headers=headers, json={"name": "Family"})
    assert created.status_code == 201
    household = created.json()
    assert household["members"][0]["joined_at"] == expected
    path = f"{base}/{household['household_id']}"

    before_read = await membership_snapshot(runtime_engine)
    listed = await runtime_client.get(base, headers=headers)
    assert listed.status_code == 200
    assert listed.json() == [household]
    assert await membership_snapshot(runtime_engine) == before_read

    renamed = await runtime_client.patch(path, headers=headers, json={"name": "Home"})
    assert renamed.status_code == 200
    assert renamed.json()["name"] == "Home"
    assert renamed.json()["members"] == household["members"]

    added = await runtime_client.put(
        f"{path}/members",
        headers=headers,
        json={"user_id": member_public_id, "role": "member"},
    )
    assert added.status_code == 200
    assert len(added.json()["members"]) == 2
    assert all(row["joined_at"] == expected for row in added.json()["members"])
    listed = await runtime_client.get(base, headers=headers)
    assert listed.status_code == 200
    assert listed.json() == [added.json()]

    # Later mutations must preserve the original joining instant, not replace
    # it with this later clock or reinterpret it using the server's TZ.
    monkeypatch.setattr(
        admin_router, "utc_now", lambda: datetime(2026, 9, 21, tzinfo=UTC)
    )
    updated = await runtime_client.put(
        f"{path}/members",
        headers=headers,
        json={"user_id": member_public_id, "role": "admin"},
    )
    assert updated.status_code == 200
    changed_member = next(
        row for row in updated.json()["members"] if row["user_id"] == member_public_id
    )
    assert changed_member["role"] == "admin"
    assert changed_member["joined_at"] == expected
    removed = await runtime_client.delete(
        f"{path}/members/{member_public_id}", headers=headers
    )
    assert removed.status_code == 200
    removed_member = next(
        row for row in removed.json()["members"] if row["user_id"] == member_public_id
    )
    assert removed_member == {**changed_member, "status": "removed"}
    listed = await runtime_client.get(base, headers=headers)
    assert listed.status_code == 200
    assert listed.json() == [removed.json()]

    async with runtime_engine.begin() as connection:
        raw = (
            (
                await connection.execute(
                    text("SELECT joined_at FROM household_memberships")
                )
            )
            .scalars()
            .all()
        )
        assert raw == ["2026-09-19 23:30:00.123456"] * 2
        await connection.execute(
            text(
                "UPDATE household_memberships SET joined_at = NULL WHERE user_id = :id"
            ),
            {"id": member_id},
        )
    before_read = await membership_snapshot(runtime_engine)
    nullable = await runtime_client.get(base, headers=headers)
    assert nullable.status_code == 200
    nullable_member = next(
        row
        for row in nullable.json()[0]["members"]
        if row["user_id"] == member_public_id
    )
    assert nullable_member == {**removed_member, "joined_at": None}
    assert await membership_snapshot(runtime_engine) == before_read
