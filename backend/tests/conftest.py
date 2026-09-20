"""Pytest fixtures for testing."""

import pytest
import pytest_asyncio
from httpx import AsyncClient, ASGITransport
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from sqlmodel import SQLModel
from sqlalchemy import text
import jwt
from datetime import datetime, timedelta, timezone

# Import models BEFORE creating engine to register them with SQLModel.metadata
from src.auth.models import User  # noqa: F401
from tests.account_fixtures import TEST_ACCOUNT_PASSWORD, account_password_hash

from src.main import app
from src.database import get_engine, set_engine
from src.storage.database_adapter import build_database_adapter


# Test configuration
TEST_SECRET_KEY = "test-secret-key-for-development-only"
TEST_ALGORITHM = "HS256"


@pytest.fixture
def isolated_settings_env(monkeypatch):
    """Isolate opt-in configuration tests without reloading application modules."""
    import os
    from src.config import Settings, get_settings

    field_names = {name.lower() for name in Settings.model_fields}
    for name in tuple(os.environ):
        if name.lower() in field_names:
            monkeypatch.delenv(name)
    monkeypatch.setitem(Settings.model_config, "env_file", None)
    monkeypatch.setenv("JWT_SECRET_KEY", TEST_SECRET_KEY)
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


@pytest_asyncio.fixture(scope="function")
async def async_engine(tmp_path):
    """Isolated service database with the production SQLite FK/WAL configuration."""
    engine = build_database_adapter(
        "sqlite", None, str(tmp_path / "service.sqlite")
    ).create_async_engine()
    previous = get_engine()
    set_engine(engine)
    try:
        yield engine
    finally:
        set_engine(previous)
        await engine.dispose()


@pytest_asyncio.fixture(scope="function")
async def async_session(async_engine):
    """Create async session fixture for tests.

    Creates model tables for service tests. Use runtime_client for migrated HTTP tests.
    Yields an AsyncSession for database operations.
    """
    # Create all tables
    async with async_engine.begin() as conn:
        await conn.run_sync(SQLModel.metadata.create_all)

    # Create session factory
    async_session_maker = async_sessionmaker(
        async_engine, class_=AsyncSession, expire_on_commit=False
    )

    # Yield session
    async with async_session_maker() as session:
        yield session

    # tmp_path and async_engine own cleanup; no FK-unsafe DROP ordering.


@pytest_asyncio.fixture(scope="function")
async def test_client(async_session, async_engine):
    """Shared-session service/router integration client, NOT a commit boundary test.

    Tests can inspect uncommitted state using async_session. HTTP durability,
    rollback and migration claims must use runtime_client without this override.
    """
    from src.database import get_session

    # Override the get_session dependency to use the test's session
    async def override_get_session():
        yield async_session

    previous_overrides = app.dependency_overrides.copy()
    app.dependency_overrides[get_session] = override_get_session
    try:
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test"
        ) as client:
            yield client
    finally:
        app.dependency_overrides.clear()
        app.dependency_overrides.update(previous_overrides)


@pytest_asyncio.fixture(scope="function")
async def auth_tokens(test_client, async_session):
    """Create a test user and login, return tokens.

    Yields a dict with access_token and refresh_token.
    """
    user = User(
        username="testuser",
        password_hash=account_password_hash(),
    )
    async_session.add(user)
    await async_session.commit()
    response = await test_client.post(
        "/api/v1/auth/login",
        json={"username": "testuser", "password": TEST_ACCOUNT_PASSWORD},
    )
    assert response.status_code == 200
    tokens = response.json()

    yield tokens

    # The isolated database is owned by tmp_path; no shared account state survives.


@pytest_asyncio.fixture(scope="function")
async def clear_db(async_session, async_engine):
    """Truncate all tables between tests for clean state.

    Use this fixture when you need a clean database without recreating tables.
    """
    yield

    # Truncate all tables after test
    async with async_engine.begin() as conn:
        await conn.execute(text("DELETE FROM users"))


@pytest.fixture
def auth_token():
    """Generate valid JWT token for testing protected routes.

    Returns a valid access token with default expiration of 30 minutes.
    """
    return create_test_token({"sub": "test-user-id", "type": "access"})


@pytest.fixture
def auth_token_admin():
    """Generate valid JWT token for admin user testing."""
    return create_test_token(
        {"sub": "admin-user-id", "type": "access", "role": "admin"}
    )


def create_test_token(
    data: dict, token_type: str = "access", expire_minutes: int = 30
) -> str:
    """Create JWT token for testing.

    Args:
        data: Token payload data
        token_type: Type of token (access or refresh)
        expire_minutes: Token expiration time in minutes

    Returns:
        Encoded JWT token string
    """
    to_encode = data.copy()
    expire = datetime.now(timezone.utc) + timedelta(minutes=expire_minutes)
    to_encode.update({"exp": expire, "type": token_type})
    return jwt.encode(to_encode, TEST_SECRET_KEY, algorithm=TEST_ALGORITHM)


@pytest_asyncio.fixture
async def runtime_engine(tmp_path):
    """Production adapter and Alembic schema; restore application globals on failure."""
    from alembic import command
    from tests.test_alembic_migration import alembic_config
    from src.database import get_session

    assert get_session not in app.dependency_overrides
    database = tmp_path / "runtime.sqlite"
    command.upgrade(alembic_config(str(database)), "head")
    engine = build_database_adapter("sqlite", None, str(database)).create_async_engine()
    previous = get_engine()
    set_engine(engine)
    try:
        yield engine
    finally:
        set_engine(previous)
        await engine.dispose()


@pytest_asyncio.fixture
async def runtime_client(runtime_engine):
    """Real HTTP transaction dependencies; every request opens its own session."""
    async with AsyncClient(
        transport=ASGITransport(app=app, raise_app_exceptions=False),
        base_url="http://test",
    ) as client:
        yield client
