"""Admin token management router."""

from datetime import datetime, timedelta, timezone
from typing import List

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import select

from src.admin.dependencies import get_admin_user
from src.auth.models import User
from src.database import get_session
from src.tokens.models import ApiToken
from src.tokens.schemas import TokenCreate, TokenListResponse, TokenResponse
from src.tokens.service import generate_token, get_token_prefix, hash_token

router = APIRouter(prefix="/admin/users/{user_id}/tokens", tags=["Admin Tokens"])


@router.get("", response_model=List[TokenListResponse])
async def list_user_tokens(
    user_id: int,
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_session),
):
    """List all API tokens for a specific user. Requires admin role."""
    result = await session.execute(
        select(ApiToken).where(ApiToken.user_id == user_id)
    )
    tokens = result.scalars().all()
    return tokens


@router.post("", response_model=TokenResponse, status_code=status.HTTP_201_CREATED)
async def create_user_token(
    user_id: int,
    token_data: TokenCreate,
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_session),
):
    """Create a new API token for a specific user. Requires admin role."""
    # Verify target user exists
    result = await session.execute(select(User).where(User.id == user_id))
    user = result.scalar()
    if not user:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="User not found",
        )

    raw_token = generate_token()
    token_hash = hash_token(raw_token)
    prefix = get_token_prefix(raw_token)

    expires_at = None
    if token_data.expires_in_days is not None:
        expires_at = datetime.now(timezone.utc) + timedelta(days=token_data.expires_in_days)

    api_token = ApiToken(
        user_id=user_id,
        name=token_data.name,
        token_hash=token_hash,
        prefix=prefix,
        expires_at=expires_at,
    )
    session.add(api_token)
    await session.commit()
    await session.refresh(api_token)

    return TokenResponse(
        id=api_token.id,
        name=api_token.name,
        prefix=api_token.prefix,
        token=raw_token,
        last_used_at=api_token.last_used_at,
        created_at=api_token.created_at,
        expires_at=api_token.expires_at,
    )


@router.delete("/{token_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_user_token(
    user_id: int,
    token_id: int,
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_session),
):
    """Delete an API token for a specific user. Requires admin role."""
    result = await session.execute(
        select(ApiToken).where(
            ApiToken.id == token_id,
            ApiToken.user_id == user_id,
        )
    )
    token = result.scalar()

    if not token:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Token not found",
        )

    await session.delete(token)
    await session.commit()
