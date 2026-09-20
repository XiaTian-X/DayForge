"""Tests for admin auto-creation on first run."""

import pytest
from sqlmodel import select

from src.auth.models import User
from src.auth.service import verify_password
from src.main import _create_admin_if_missing
from src.config import get_settings


pytestmark = pytest.mark.usefixtures("isolated_settings_env")


@pytest.mark.asyncio
async def test_does_not_create_admin_without_explicit_credentials(
    async_session, async_engine
):
    """A fresh deployment must never receive a default privileged account."""
    from src.database import set_engine

    set_engine(async_engine)

    await _create_admin_if_missing()

    result = await async_session.execute(select(User).where(User.is_admin == True))
    admin = result.scalars().first()

    assert admin is None


@pytest.mark.asyncio
async def test_does_not_create_duplicate_admin(
    monkeypatch, async_session, async_engine
):
    """Test that admin is not duplicated when one already exists."""
    from src.database import set_engine

    set_engine(async_engine)

    monkeypatch.setenv("ADMIN_USERNAME", "testadmin")
    monkeypatch.setenv("ADMIN_PASSWORD", "securepass123")
    get_settings.cache_clear()

    # Create first admin
    await _create_admin_if_missing()

    # Try again
    await _create_admin_if_missing()

    result = await async_session.execute(select(User).where(User.is_admin == True))
    admins = result.scalars().all()

    assert len(admins) == 1


@pytest.mark.asyncio
async def test_uses_custom_admin_credentials(monkeypatch, async_session, async_engine):
    """Test that custom ADMIN_USERNAME and ADMIN_PASSWORD are used."""
    from src.database import set_engine
    from src.config import get_settings

    set_engine(async_engine)

    monkeypatch.setenv("ADMIN_USERNAME", "superadmin")
    monkeypatch.setenv("ADMIN_PASSWORD", "securepass123")

    # Clear cache to pick up new env vars
    get_settings.cache_clear()

    await _create_admin_if_missing()

    result = await async_session.execute(select(User).where(User.is_admin == True))
    admin = result.scalars().first()

    assert admin is not None
    assert admin.username == "superadmin"
    assert verify_password("securepass123", admin.password_hash)
