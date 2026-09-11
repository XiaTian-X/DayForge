"""Tests for API token service layer."""
import hashlib
from datetime import datetime, timedelta, timezone

from src.tokens.service import (
    generate_token,
    hash_token,
    get_token_prefix,
    verify_token_expiry,
    TOKEN_PREFIX,
    TOKEN_BYTES,
    PREFIX_LENGTH,
)


class TestGenerateToken:
    """Test generate_token function."""

    def test_returns_string(self):
        token = generate_token()
        assert isinstance(token, str)

    def test_starts_with_prefix(self):
        token = generate_token()
        assert token.startswith(TOKEN_PREFIX)

    def test_correct_length(self):
        token = generate_token()
        # df_ (3 chars) + 32 bytes as hex (64 chars) = 67
        expected_len = len(TOKEN_PREFIX) + TOKEN_BYTES * 2
        assert len(token) == expected_len

    def test_uniqueness(self):
        tokens = {generate_token() for _ in range(100)}
        assert len(tokens) == 100


class TestHashToken:
    """Test hash_token function."""

    def test_returns_sha256_hex(self):
        token = generate_token()
        hashed = hash_token(token)
        expected = hashlib.sha256(token.encode()).hexdigest()
        assert hashed == expected

    def test_deterministic(self):
        sample_value = "df_abcdef1234567890"
        assert hash_token(sample_value) == hash_token(sample_value)

    def test_different_tokens_different_hashes(self):
        t1 = generate_token()
        t2 = generate_token()
        assert hash_token(t1) != hash_token(t2)

    def test_hash_length_is_64_chars(self):
        hashed = hash_token(generate_token())
        assert len(hashed) == 64


class TestGetTokenPrefix:
    """Test get_token_prefix function."""

    def test_returns_first_n_characters(self):
        sample_value = "df_abcdef1234567890"
        assert get_token_prefix(sample_value) == sample_value[:PREFIX_LENGTH]

    def test_prefix_length(self):
        prefix = get_token_prefix(generate_token())
        assert len(prefix) == PREFIX_LENGTH

    def test_starts_with_df(self):
        prefix = get_token_prefix(generate_token())
        assert prefix.startswith(TOKEN_PREFIX)


class TestVerifyTokenExpiry:
    """Test verify_token_expiry function."""

    def test_none_expires_means_valid(self):
        assert verify_token_expiry(None) is True

    def test_future_expiry_is_valid(self):
        future = datetime.now(timezone.utc) + timedelta(days=1)
        assert verify_token_expiry(future) is True

    def test_past_expiry_is_expired(self):
        past = datetime.now(timezone.utc) - timedelta(days=1)
        assert verify_token_expiry(past) is False

    def test_naive_sqlite_future_expiry_is_treated_as_utc(self):
        future = datetime.utcnow() + timedelta(days=1)
        assert verify_token_expiry(future) is True

    def test_naive_sqlite_past_expiry_is_expired(self):
        past = datetime.utcnow() - timedelta(days=1)
        assert verify_token_expiry(past) is False
