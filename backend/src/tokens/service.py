"""API token service layer with generation, hashing, and validation."""

import hashlib
import secrets
from datetime import datetime

from src.time_utils import as_utc, utc_now

TOKEN_PREFIX = "df_"
TOKEN_BYTES = 32
PREFIX_LENGTH = 11


def generate_token() -> str:
    """Generate a random API token with df_ prefix.

    Returns:
        Token string in format: df_<32 random bytes hex>
    """
    random_part = secrets.token_hex(TOKEN_BYTES)
    return f"{TOKEN_PREFIX}{random_part}"


def hash_token(token: str) -> str:
    """Hash token with SHA256 for secure storage.

    Args:
        token: The raw token string.

    Returns:
        Hex-encoded SHA256 hash of the token.
    """
    return hashlib.sha256(token.encode()).hexdigest()


def get_token_prefix(token: str) -> str:
    """Get the first 11 characters of a token for identification.

    Args:
        token: The raw token string.

    Returns:
        First 11 characters of the token.
    """
    return token[:PREFIX_LENGTH]


def verify_token_expiry(expires_at: datetime | None) -> bool:
    """Check if a token has expired.

    Args:
        expires_at: Token expiration datetime, or None if no expiry.

    Returns:
        True if token is still valid (not expired), False if expired.
    """
    if expires_at is None:
        return True
    return utc_now() < as_utc(expires_at)
