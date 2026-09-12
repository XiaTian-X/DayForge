"""Tests for authentication service layer."""
import pytest
from datetime import datetime, timedelta, timezone
import jwt
import bcrypt

from src.auth.service import (
    verify_password,
    get_password_hash,
    create_access_token,
    create_refresh_token,
    verify_token,
    PASSWORD_HASH_PREFIX,
)
from src.config import settings


class TestPasswordHashing:
    """Test password hashing functionality."""

    def test_verify_password_returns_true_for_correct_password(self):
        """Test verify_password returns True for correct password."""
        password = "test_password_123"
        hashed = get_password_hash(password)

        assert verify_password(password, hashed) is True

    def test_verify_password_returns_false_for_wrong_password(self):
        """Test verify_password returns False for wrong password."""
        password = "test_password_123"
        hashed = get_password_hash(password)

        assert verify_password("wrong_password", hashed) is False

    def test_get_password_hash_returns_versioned_bcrypt_hash(self):
        """The explicit marker prevents confusing prehashed and raw credentials."""
        hashed = get_password_hash("test_password")
        assert hashed.startswith(PASSWORD_HASH_PREFIX + "$2b$")

    @pytest.mark.parametrize("prefix", ["x" * 72, "习惯" * 12, "😀" * 18])
    def test_password_suffix_beyond_72_bytes_is_significant(self, prefix):
        hashed = get_password_hash(prefix + "original")
        assert verify_password(prefix + "original", hashed)
        assert not verify_password(prefix + "different", hashed)
        assert not verify_password(prefix, hashed)

    def test_legacy_hash_remains_verifiable(self):
        hashed = bcrypt.hashpw(b"legacy password", bcrypt.gensalt()).decode()
        assert verify_password("legacy password", hashed)
        assert not verify_password("wrong password", hashed)

    @pytest.mark.parametrize("hashed", ["", "$2b$broken", "$unknown$v2$abc", PASSWORD_HASH_PREFIX + "broken", "$2b$中文"])
    def test_invalid_hash_fails_closed(self, hashed):
        assert not verify_password("password", hashed)

    def test_password_hash_is_different_for_same_password(self):
        """Test that hashing same password produces different hashes (salt)."""
        password = "same_password"
        hash1 = get_password_hash(password)
        hash2 = get_password_hash(password)

        # bcrypt uses salt, so hashes should be different
        assert hash1 != hash2
        assert verify_password(password, hash1)
        assert verify_password(password, hash2)


class TestCreateAccessToken:
    """Test create_access_token function."""

    def test_create_access_token_returns_valid_jwt(self):
        """Test create_access_token returns valid JWT with exp claim."""
        data = {"sub": "user-123", "type": "access"}
        token = create_access_token(data)

        # Decode and verify
        payload = jwt.decode(
            token,
            settings.JWT_SECRET_KEY,
            algorithms=[settings.JWT_ALGORITHM]
        )

        assert payload["sub"] == "user-123"
        assert payload["type"] == "access"
        assert "exp" in payload

    def test_create_access_token_has_correct_expiry(self):
        """Test create_access_token has correct expiration time."""
        data = {"sub": "user-123"}
        custom_expiry = timedelta(hours=1)
        token = create_access_token(data, expires_delta=custom_expiry)

        payload = jwt.decode(
            token,
            settings.JWT_SECRET_KEY,
            algorithms=[settings.JWT_ALGORITHM]
        )

        # Check expiration is approximately 1 hour from now
        exp_time = datetime.fromtimestamp(payload["exp"], tz=timezone.utc)
        expected_time = datetime.now(timezone.utc) + custom_expiry

        # Allow 1 minute tolerance
        assert abs((exp_time - expected_time).total_seconds()) < 60

    def test_create_access_token_uses_default_expiry(self):
        """Test create_access_token uses default expiry when not specified."""
        data = {"sub": "user-123"}
        token = create_access_token(data)

        payload = jwt.decode(
            token,
            settings.JWT_SECRET_KEY,
            algorithms=[settings.JWT_ALGORITHM]
        )

        exp_time = datetime.fromtimestamp(payload["exp"], tz=timezone.utc)
        expected_time = datetime.now(timezone.utc) + timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES)

        # Allow 1 minute tolerance
        assert abs((exp_time - expected_time).total_seconds()) < 60


class TestCreateRefreshToken:
    """Test create_refresh_token function."""

    def test_create_refresh_token_returns_valid_jwt(self):
        """Test create_refresh_token returns valid JWT with longer expiry."""
        data = {"sub": "user-123"}
        token = create_refresh_token(data)

        payload = jwt.decode(
            token,
            settings.JWT_SECRET_KEY,
            algorithms=[settings.JWT_ALGORITHM]
        )

        assert payload["sub"] == "user-123"
        assert payload["type"] == "refresh"
        assert "exp" in payload

    def test_create_refresh_token_has_longer_expiry(self):
        """Test create_refresh_token has 7-day default expiry."""
        data = {"sub": "user-123"}
        token = create_refresh_token(data)

        payload = jwt.decode(
            token,
            settings.JWT_SECRET_KEY,
            algorithms=[settings.JWT_ALGORITHM]
        )

        exp_time = datetime.fromtimestamp(payload["exp"], tz=timezone.utc)
        expected_time = datetime.now(timezone.utc) + timedelta(days=settings.REFRESH_TOKEN_EXPIRE_DAYS)

        # Allow 1 minute tolerance
        assert abs((exp_time - expected_time).total_seconds()) < 60

    def test_create_refresh_token_type_is_refresh(self):
        """Test create_refresh_token sets type to 'refresh'."""
        data = {"sub": "user-123"}
        token = create_refresh_token(data)

        payload = jwt.decode(
            token,
            settings.JWT_SECRET_KEY,
            algorithms=[settings.JWT_ALGORITHM]
        )

        assert payload["type"] == "refresh"


class TestVerifyToken:
    """Test verify_token function."""

    def test_verify_token_decodes_valid_token(self):
        """Test verify_token decodes valid token."""
        data = {"sub": "user-123", "type": "access"}
        token = create_access_token(data)

        payload = verify_token(token)

        assert payload is not None
        assert payload["sub"] == "user-123"
        assert payload["type"] == "access"

    def test_verify_token_returns_none_for_invalid_token(self):
        """Test verify_token returns None for invalid token."""
        result = verify_token("invalid_token_12345")

        assert result is None

    def test_verify_token_returns_none_for_expired_token(self):
        """Test verify_token returns None for expired token."""
        data = {"sub": "user-123"}
        # Create token that expired 1 hour ago
        expired_delta = timedelta(hours=-1)
        token = create_access_token(data, expires_delta=expired_delta)

        result = verify_token(token)

        assert result is None

    def test_verify_token_returns_none_for_wrong_secret(self):
        """Test verify_token returns None when decoded with wrong secret."""
        data = {"sub": "user-123"}
        token = jwt.encode(data, "wrong-secret-key", algorithm=settings.JWT_ALGORITHM)

        result = verify_token(token)

        assert result is None
