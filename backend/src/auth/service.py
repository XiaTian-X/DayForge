"""Authentication service layer with business logic."""

import base64
import hashlib
import bcrypt
from datetime import datetime, timedelta, timezone
import jwt

from src.config import settings

PASSWORD_HASH_PREFIX = "$dayforge-sha256-bcrypt$v1$"


def _password_material(password: str) -> bytes:
    """Keep every UTF-8 byte while fitting bcrypt's 72-byte input limit."""
    return base64.b64encode(hashlib.sha256(password.encode("utf-8")).digest())


def password_hash_needs_upgrade(hashed_password: str) -> bool:
    return hashed_password.startswith(("$2a$", "$2b$", "$2y$"))


def verify_password(plain_password: str, hashed_password: str) -> bool:
    """Verify versioned hashes or legacy raw bcrypt, failing closed on corruption."""
    try:
        if hashed_password.startswith(PASSWORD_HASH_PREFIX):
            material = _password_material(plain_password)
            encoded_hash = hashed_password[len(PASSWORD_HASH_PREFIX) :]
        elif password_hash_needs_upgrade(hashed_password):
            # Explicitly retain legacy semantics even after a future bcrypt upgrade.
            material = plain_password.encode("utf-8")[:72]
            encoded_hash = hashed_password
        else:
            return False
        return bcrypt.checkpw(material, encoded_hash.encode("ascii"))
    except (ValueError, UnicodeError):
        return False


def get_password_hash(password: str) -> str:
    """Create a versioned full-password hash; never store the intermediate digest."""
    salt = bcrypt.gensalt()
    return PASSWORD_HASH_PREFIX + bcrypt.hashpw(
        _password_material(password), salt
    ).decode("ascii")


def create_access_token(data: dict, expires_delta: timedelta | None = None) -> str:
    """Create JWT access token."""
    to_encode = data.copy()
    expire = datetime.now(timezone.utc) + (
        expires_delta or timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES)
    )
    to_encode.update({"exp": expire, "type": "access"})
    return jwt.encode(
        to_encode, settings.JWT_SECRET_KEY, algorithm=settings.JWT_ALGORITHM
    )


def create_refresh_token(data: dict, expires_delta: timedelta | None = None) -> str:
    """Create JWT refresh token with longer expiry."""
    to_encode = data.copy()
    expire = datetime.now(timezone.utc) + (
        expires_delta or timedelta(days=settings.REFRESH_TOKEN_EXPIRE_DAYS)
    )
    to_encode.update({"exp": expire, "type": "refresh"})
    return jwt.encode(
        to_encode, settings.JWT_SECRET_KEY, algorithm=settings.JWT_ALGORITHM
    )


def verify_token(token: str) -> dict | None:
    """Verify and decode JWT token."""
    try:
        payload = jwt.decode(
            token, settings.JWT_SECRET_KEY, algorithms=[settings.JWT_ALGORITHM]
        )
        return payload
    except jwt.PyJWTError:
        return None
