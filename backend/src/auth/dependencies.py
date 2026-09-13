"""Authentication dependencies for protected routes."""

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import OAuth2PasswordBearer
from sqlmodel import select
from sqlalchemy.ext.asyncio import AsyncSession

from src.database import get_session
from src.auth.models import User
from src.auth.service import verify_token
from src.tokens.models import ApiToken
from src.tokens.service import hash_token, verify_token_expiry
from src.time_utils import utc_now


# OAuth2 scheme for token extraction
oauth2_scheme = OAuth2PasswordBearer(tokenUrl='/api/v1/auth/login', auto_error=False)


async def get_current_user(
    request: Request,
    token: str | None = Depends(oauth2_scheme),
    session: AsyncSession = Depends(get_session),
) -> User:
    """Get current authenticated user from JWT or API Token.

    Supports:
    - Authorization: Bearer <jwt_token>
    - Authorization: Token <api_token>

    Use as dependency in protected routes:
    ```
    @router.get("/protected")
    async def protected_route(
        current_user: User = Depends(get_current_user)
    ):
        # current_user is the authenticated user
    ```
    """
    credentials_exception = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail='Could not validate credentials',
        headers={'WWW-Authenticate': 'Bearer'},
    )

    # Get the raw Authorization header
    auth_header = request.headers.get('Authorization')
    if not auth_header:
        raise credentials_exception

    # Parse header format
    parts = auth_header.split(' ', 1)
    if len(parts) != 2:
        raise credentials_exception

    scheme, credentials = parts

    if scheme == 'Bearer':
        # JWT authentication
        payload = verify_token(credentials)
        if payload is None:
            raise credentials_exception

        token_type = payload.get('type')
        if token_type != 'access':
            raise credentials_exception

        user_id: str = payload.get('sub')
        token_version = payload.get('ver')
        if user_id is None or not isinstance(token_version, int):
            raise credentials_exception

        try:
            internal_user_id = int(user_id)
        except (TypeError, ValueError):
            raise credentials_exception from None

        result = await session.execute(select(User).where(User.id == internal_user_id))
        user = result.scalar()

        if (
            user is None
            or not user.is_active
            or user.status != "active"
            or user.auth_version != token_version
        ):
            raise credentials_exception

        return user

    elif scheme == 'Token':
        # API Token authentication
        token_hash = hash_token(credentials)

        result = await session.execute(
            select(ApiToken).where(ApiToken.token_hash == token_hash)
        )
        api_token = result.scalar()

        if api_token is None:
            raise credentials_exception

        # Check if token has expired
        if not verify_token_expiry(api_token.expires_at):
            raise credentials_exception

        # Update last_used_at
        api_token.last_used_at = utc_now()
        # Keep authentication inside the request transaction. The session
        # dependency owns the final commit/rollback boundary.
        await session.flush()

        # Get the associated user
        result = await session.execute(
            select(User).where(User.id == api_token.user_id)
        )
        user = result.scalar()

        if user is None or not user.is_active or user.status != "active":
            raise credentials_exception

        return user

    else:
        raise credentials_exception


# Type alias for dependency injection
UserDependency = User
