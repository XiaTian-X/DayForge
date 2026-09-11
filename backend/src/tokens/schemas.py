"""Pydantic schemas for API token endpoints."""

from datetime import datetime
from typing import Optional

from pydantic import BaseModel, Field


class TokenCreate(BaseModel):
    """Request schema for creating a new API token."""

    name: str = Field(min_length=1, max_length=100)
    expires_in_days: Optional[int] = Field(default=None, ge=1, le=365)


class TokenResponse(BaseModel):
    """Response schema for token creation (includes the raw token)."""

    id: int
    name: str
    prefix: str
    token: str
    last_used_at: Optional[datetime] = None
    created_at: datetime
    expires_at: Optional[datetime] = None

    class Config:
        from_attributes = True


class TokenListResponse(BaseModel):
    """Response schema for listing tokens (excludes the raw token)."""

    id: int
    name: str
    prefix: str
    last_used_at: Optional[datetime] = None
    created_at: datetime
    expires_at: Optional[datetime] = None

    class Config:
        from_attributes = True


__all__ = [
    "TokenCreate",
    "TokenResponse",
    "TokenListResponse",
]
