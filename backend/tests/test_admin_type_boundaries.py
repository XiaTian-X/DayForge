"""Preserve management query and response boundaries during type-gate expansion."""

from unittest.mock import AsyncMock
from uuid import UUID

import pytest
from pydantic import ValidationError
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from src.admin.router import list_households
from src.auth.models import User
from src.v2.device_service import to_device_response
from src.v2.models import ClientDevice, Household, HouseholdMembership
from tests.account_fixtures import account_password_hash
from tests.test_http_commit_boundary import database_state
from tests.test_http_commit_boundary import runtime_http as runtime_http


@pytest.mark.parametrize("operation", ["list", "update"])
async def test_missing_household_creator_remains_generic_500_and_rolls_back(
    runtime_http, monkeypatch, operation
):
    client, engine, bearer, api_headers, _ = runtime_http
    created = await client.post(
        "/api/v1/admin/households", headers=bearer, json={"name": "Original"}
    )
    assert created.status_code == 201
    household_id = created.json()["household_id"]
    baseline = await client.get("/api/v1/admin/households", headers=bearer)
    assert baseline.status_code == 200
    async with engine.connect() as connection:
        creator_id = (
            await connection.execute(text("SELECT created_by_user_id FROM households"))
        ).scalar_one()
    original_get = AsyncSession.get

    async def missing_creator(session, model, identity, **kwargs):
        # Inject a failed reference read; do not disable SQLite foreign keys or
        # replace the runtime request transaction with a test-only dependency.
        if model is User and identity == creator_id:
            return None
        return await original_get(session, model, identity, **kwargs)

    before = await database_state(engine)
    with monkeypatch.context() as patch:
        patch.setattr(AsyncSession, "get", missing_creator)
        if operation == "list":
            response = await client.get("/api/v1/admin/households", headers=api_headers)
        else:
            response = await client.patch(
                f"/api/v1/admin/households/{household_id}",
                headers=api_headers,
                json={"name": "Must roll back"},
            )
    assert response.status_code == 500
    assert response.text == "Internal Server Error"
    assert await database_state(engine) == before
    recovered = await client.get("/api/v1/admin/households", headers=bearer)
    assert recovered.status_code == 200
    assert recovered.json() == baseline.json()


async def test_household_sort_tiebreaker_and_member_join_remain_scoped(async_session):
    owner = User(
        username="z_owner", password_hash=account_password_hash(), is_admin=True
    )
    other = User(username="a_member", password_hash=account_password_hash())
    async_session.add_all([owner, other])
    await async_session.flush()
    households = [
        Household(id=30, name="Z", created_by_user_id=owner.id),
        Household(id=20, name="A", created_by_user_id=owner.id),
        Household(id=10, name="A", created_by_user_id=owner.id),
    ]
    async_session.add_all(households)
    await async_session.flush()
    async_session.add_all(
        [
            HouseholdMembership(household_id=10, user_id=owner.id, role="owner"),
            HouseholdMembership(household_id=10, user_id=other.id),
            HouseholdMembership(household_id=20, user_id=owner.id, role="owner"),
        ]
    )
    await async_session.commit()
    responses = await list_households(admin=owner, session=async_session)
    assert [str(row.household_id) for row in responses] == [
        households[2].public_id,
        households[1].public_id,
        households[0].public_id,
    ]
    assert [[member.username for member in row.members] for row in responses] == [
        ["a_member", "z_owner"],
        ["z_owner"],
        [],
    ]
    assert all(row.created_by_user_id == UUID(owner.public_id) for row in responses)


@pytest.mark.parametrize(
    "public_id", ["ABCDEF12-3456-4789-ABCD-123456789012", "not-a-uuid"]
)
async def test_device_response_validates_uuid_without_creating_policy(public_id):
    session = AsyncMock()
    session.get.return_value = None
    device = ClientDevice(
        id=31,
        user_id=17,
        public_id=public_id,
        installation_id="typed-device",
        platform="hardware",
        device_class="hardware",
    )
    if public_id == "not-a-uuid":
        with pytest.raises(ValidationError) as error:
            await to_device_response(session, device)
        assert error.value.errors()[0]["loc"] == ("device_id",)
        assert error.value.errors()[0]["type"] == "uuid_parsing"
    else:
        response = await to_device_response(session, device)
        assert response.device_id == UUID(public_id)
        assert response.capabilities == ["sync.read", "facts.append", "timer.control"]
        assert response.is_primary_editor is False
    session.add.assert_not_called()
    session.flush.assert_not_awaited()
    session.commit.assert_not_awaited()
