"""Authentication service layer with business logic."""

import base64
import binascii
import hashlib
import bcrypt
from datetime import datetime, timedelta, timezone
import jwt

from src.config import settings

PASSWORD_HASH_PREFIX = "$dayforge-sha256-bcrypt$v1$"
MAX_JWT_LENGTH = 16_384
MAX_JWT_JSON_DEPTH = 32
DATABASE_INTEGER_MIN = -(2**63)
DATABASE_INTEGER_MAX = 2**63 - 1


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


def _bounded_compact_json(token: str) -> bool:
    """Resource preflight only: never use unverified JSON as authorization.

    Bound header/payload nesting before a recursive decoder runs, independent
    of the host's recursion limit. Preserve the original signing input.
    """
    segments = token.split(".")
    if len(segments) != 3 or not segments[0] or not segments[1]:
        return False
    for segment in segments[:2]:
        try:
            text = base64.b64decode(
                segment + "=" * (-len(segment) % 4), altchars=b"-_", validate=True
            ).decode("utf-8")
        except (binascii.Error, ValueError):
            return False
        # JSON's byte decoder also auto-detects UTF-16/32 without a BOM. Their
        # NUL bytes can otherwise survive UTF-8 decoding and confuse the scan;
        # raw NUL is never legal UTF-8 JSON (an escaped \u0000 remains valid).
        if "\x00" in text:
            return False
        depth = 0
        quoted = escaped = False
        for character in text:
            if quoted:
                if escaped:
                    escaped = False
                elif character == "\\":
                    escaped = True
                elif character == '"':
                    quoted = False
            elif character == '"':
                quoted = True
            elif character in "[{":
                depth += 1
                if depth > MAX_JWT_JSON_DEPTH:
                    return False
            elif character in "]}":
                depth -= 1
                if depth < 0:
                    return False
    return True


def verify_token(token: str) -> dict | None:
    """Verify and decode JWT token."""
    # Compact JWTs are ASCII. Bound work before decoding/allocating JSON or
    # verifying a signature; do not log the supplied credential.
    if (
        not isinstance(token, str)
        or not token
        or len(token) > MAX_JWT_LENGTH
        or not token.isascii()
        or not _bounded_compact_json(token)
    ):
        return None
    try:
        payload = jwt.decode(
            token,
            settings.JWT_SECRET_KEY,
            algorithms=[settings.JWT_ALGORITHM],
            options={"require": ["exp"]},
        )
        return payload
    except (jwt.PyJWTError, OverflowError):
        # PyJWT wraps malformed JSON/claim types, but int(infinite NumericDate)
        # can still raise OverflowError. Unrelated programming errors propagate.
        return None


def token_account_identity(payload: dict) -> tuple[int, int] | None:
    """Validate database-bound claims only AFTER cryptographic verification.

    Retain the existing string-to-integer subject semantics, but never pass
    out-of-range integers or bool-as-int auth versions to the database driver.
    """
    subject = payload.get("sub")
    version = payload.get("ver")
    if not isinstance(subject, str) or type(version) is not int:
        return None
    try:
        user_id = int(subject)
    except ValueError:
        return None
    if not all(
        DATABASE_INTEGER_MIN <= value <= DATABASE_INTEGER_MAX
        for value in (user_id, version)
    ):
        return None
    return user_id, version
