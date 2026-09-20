"""Authentication endpoints for administrator-managed local accounts."""

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import update
from sqlmodel import col, select

from src.auth.dependencies import get_current_user
from src.auth.models import User
from src.auth.schemas import Token, TokenRefresh, UserLogin, UserResponse
from src.auth.service import (
    create_access_token,
    create_refresh_token,
    get_password_hash,
    password_hash_needs_upgrade,
    verify_password,
    verify_token,
)
from src.database import get_session

router = APIRouter(prefix="/auth", tags=["Authentication"])


def _token_claims(user: User) -> dict[str, str | int]:
    return {"sub": str(user.id), "ver": user.auth_version}


def _token_response(user: User) -> Token:
    claims = _token_claims(user)
    # Stored UUIDs are strings; validate at the response boundary as before.
    return Token.model_validate(
        {
            "access_token": create_access_token(claims),
            "refresh_token": create_refresh_token(claims),
            "user_id": user.public_id,
            "username": user.username,
            "is_admin": user.is_admin,
        }
    )


@router.post("/login", response_model=Token)
async def login(
    credentials: UserLogin,
    session: AsyncSession = Depends(get_session, scope="function"),
):
    """Log in to an account created by the local server administrator."""
    result = await session.execute(
        select(User).where(User.username == credentials.username)
    )
    user = result.scalar()

    if not user or not verify_password(credentials.password, user.password_hash):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect credentials",
        )
    if not user.is_active or user.status != "active":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Account disabled",
        )
    if password_hash_needs_upgrade(user.password_hash):
        # Do not overwrite a password reset that won the race after the read.
        upgrade_result = await session.execute(
            update(User)
            .where(
                col(User.id) == user.id,
                col(User.password_hash) == user.password_hash,
                col(User.auth_version) == user.auth_version,
                col(User.is_active).is_(True),
                col(User.status) == "active",
            )
            .values(password_hash=get_password_hash(credentials.password))
            .execution_options(synchronize_session=False)
        )
        if upgrade_result.rowcount != 1:
            raise HTTPException(status_code=401, detail="Incorrect credentials")
    return _token_response(user)


@router.post("/refresh", response_model=Token)
async def refresh_token(
    token_data: TokenRefresh,
    session: AsyncSession = Depends(get_session, scope="function"),
):
    """Rotate a valid refresh token."""
    payload = verify_token(token_data.refresh_token)
    if not payload or payload.get("type") != "refresh":
        raise HTTPException(status_code=401, detail="Invalid refresh token")

    user_id = payload.get("sub")
    token_version = payload.get("ver")
    if user_id is None or not isinstance(token_version, int):
        raise HTTPException(status_code=401, detail="Invalid refresh token")

    try:
        internal_user_id = int(user_id)
    except (TypeError, ValueError):
        raise HTTPException(status_code=401, detail="Invalid refresh token") from None

    result = await session.execute(select(User).where(User.id == internal_user_id))
    user = result.scalar()
    if (
        not user
        or not user.is_active
        or user.status != "active"
        or user.auth_version != token_version
    ):
        raise HTTPException(
            status_code=401, detail="User not found, inactive, or session revoked"
        )

    return _token_response(user)


@router.get("/users/me", response_model=UserResponse)
async def get_current_user_info(current_user: User = Depends(get_current_user)):
    """Return the authenticated account."""
    return current_user
