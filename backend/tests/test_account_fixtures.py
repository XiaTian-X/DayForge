"""Guard the boundary between faster seed setup and genuine authentication."""

from unittest.mock import patch

from src.auth import service
from tests.account_fixtures import TEST_ACCOUNT_PASSWORD, account_password_hash


def test_seed_hash_is_generated_once_with_real_password_security():
    account_password_hash.cache_clear()
    try:
        with patch.object(service, "get_password_hash", wraps=service.get_password_hash) as generate:
            first = account_password_hash()
            assert account_password_hash() == first
            generate.assert_called_once_with(TEST_ACCOUNT_PASSWORD)
        assert first.startswith(service.PASSWORD_HASH_PREFIX + "$2b$12$")
        assert service.verify_password(TEST_ACCOUNT_PASSWORD, first)
        assert not service.verify_password("wrong-password", first)
    finally:
        account_password_hash.cache_clear()


def test_production_password_hashes_still_have_independent_salts():
    first = service.get_password_hash(TEST_ACCOUNT_PASSWORD)
    second = service.get_password_hash(TEST_ACCOUNT_PASSWORD)
    assert first != second
    assert service.verify_password(TEST_ACCOUNT_PASSWORD, first)
    assert service.verify_password(TEST_ACCOUNT_PASSWORD, second)
