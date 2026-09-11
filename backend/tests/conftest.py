"""Pytest fixtures for testing."""
import pytest
import pytest_asyncio
from httpx import AsyncClient, ASGITransport
from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine
from sqlmodel import SQLModel, select
from sqlalchemy.orm import sessionmaker
from sqlalchemy import text
import jwt
from datetime import datetime, timedelta, timezone
import asyncio

# Import models BEFORE creating engine to register them with SQLModel.metadata
from src.auth.models import User  # noqa: F401
from src.auth.service import get_password_hash

from src.main import app
from src.database import DATABASE_URL, set_engine, get_engine
from src.config import settings


# Test configuration
TEST_SECRET_KEY = "test-secret-key-for-development-only"
TEST_ALGORITHM = "HS256"
TEST_DATABASE_URL = "sqlite+aiosqlite:///./test.db"


@pytest_asyncio.fixture(scope="function")
async def async_engine():
    """Create async engine for test database."""
    engine = create_async_engine(
        TEST_DATABASE_URL,
        echo=False,  # Disable SQL logging
        future=True
    )
    # Override the app's engine with test engine
    set_engine(engine)
    yield engine
    await engine.dispose()


@pytest_asyncio.fixture(scope="function")
async def async_session(async_engine):
    """Create async session fixture for tests.

    Creates all tables before tests, drops them after.
    Yields an AsyncSession for database operations.
    """
    # Create all tables
    async with async_engine.begin() as conn:
        await conn.run_sync(SQLModel.metadata.create_all)

    # Create session factory
    async_session_maker = sessionmaker(
        async_engine,
        class_=AsyncSession,
        expire_on_commit=False
    )

    # Yield session
    async with async_session_maker() as session:
        yield session

    # Drop all tables after test
    async with async_engine.begin() as conn:
        await conn.run_sync(SQLModel.metadata.drop_all)


@pytest_asyncio.fixture(scope="function")
async def test_client(async_session, async_engine):
    """Create HTTP test client.

    Yields an httpx.AsyncClient configured to test the FastAPI app.
    The app uses the same session as the test for transaction visibility.
    """
    from src.database import get_session

    # Override the get_session dependency to use the test's session
    async def override_get_session():
        yield async_session

    app.dependency_overrides[get_session] = override_get_session

    async with AsyncClient(
        transport=ASGITransport(app=app),
        base_url="http://test"
    ) as client:
        yield client

    # Clean up the override
    app.dependency_overrides.clear()


@pytest_asyncio.fixture(scope="function")
async def auth_tokens(test_client, async_session):
    """Create a test user and login, return tokens.

    Yields a dict with access_token and refresh_token.
    """
    user = User(
        username="testuser",
        password_hash=get_password_hash("TestPassword123!"),
    )
    async_session.add(user)
    await async_session.commit()
    response = await test_client.post(
        "/api/v1/auth/login",
        json={"username": "testuser", "password": "TestPassword123!"},
    )
    assert response.status_code == 200
    tokens = response.json()

    yield tokens

    # Cleanup: delete user after test (optional, since db is dropped)
    # The async_session fixture already drops all tables after each test


@pytest_asyncio.fixture(scope="function")
async def clear_db(async_session, async_engine):
    """Truncate all tables between tests for clean state.

    Use this fixture when you need a clean database without recreating tables.
    """
    yield

    # Truncate all tables after test
    async with async_engine.begin() as conn:
        await conn.execute(
            text("DELETE FROM users")
        )


@pytest.fixture
def auth_token():
    """Generate valid JWT token for testing protected routes.

    Returns a valid access token with default expiration of 30 minutes.
    """
    return create_test_token({"sub": "test-user-id", "type": "access"})


@pytest.fixture
def auth_token_admin():
    """Generate valid JWT token for admin user testing."""
    return create_test_token({"sub": "admin-user-id", "type": "access", "role": "admin"})


def create_test_token(data: dict, token_type: str = "access", expire_minutes: int = 30) -> str:
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
