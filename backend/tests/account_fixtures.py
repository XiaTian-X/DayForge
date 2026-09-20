"""Seed-only password material; accounts and real HTTP logins stay isolated."""

from functools import lru_cache

from src.auth import service


TEST_ACCOUNT_PASSWORD = "TestPassword123!"


@lru_cache(maxsize=1)
def account_password_hash() -> str:
    """Reuse one production-strength hash for non-authentication seed fixtures.

    Do not use this helper to test password creation, changes, or hash upgrades.
    It caches only an immutable hash, never a user, session, token or login result.
    """
    return service.get_password_hash(TEST_ACCOUNT_PASSWORD)
