"""Tests for the administrator-managed account model."""

import pytest
from sqlalchemy.exc import IntegrityError

from src.auth.models import User


async def test_user_defaults(async_session):
    user = User(username="member", password_hash="hashed")
    async_session.add(user)
    await async_session.commit()
    await async_session.refresh(user)

    assert user.public_id
    assert user.is_active is True
    assert user.status == "active"
    assert user.is_admin is False
    assert user.auth_version == 1


async def test_username_is_unique(async_session):
    async_session.add(User(username="member", password_hash="first"))
    await async_session.commit()
    async_session.add(User(username="member", password_hash="second"))

    with pytest.raises(IntegrityError):
        await async_session.commit()


async def test_admin_role_can_be_persisted(async_session):
    user = User(username="admin", password_hash="hashed", is_admin=True)
    async_session.add(user)
    await async_session.commit()
    await async_session.refresh(user)

    assert user.is_admin is True
