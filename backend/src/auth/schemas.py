"""Pydantic schemas for authentication endpoints."""

from pydantic import BaseModel, ConfigDict
from typing import Optional
from datetime import datetime
from uuid import UUID


class UserLogin(BaseModel):
    """Login request schema."""

    username: str
    password: str


class Token(BaseModel):
    """Authentication token response."""

    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    user_id: UUID
    username: str
    is_admin: bool


class TokenPayload(BaseModel):
    """JWT token payload schema."""

    sub: str  # Subject (user ID)
    exp: datetime  # Expiration time
    type: str  # Token type (access or refresh)
    ver: int  # Account auth version used to revoke existing sessions


class TokenRefresh(BaseModel):
    """Refresh token request."""

    refresh_token: str


class UserResponse(BaseModel):
    """User response schema (excludes password)."""

    id: int
    public_id: UUID
    username: str
    email: Optional[str] = None
    phone: Optional[str] = None
    is_active: bool
    is_verified: bool
    status: str = "active"
    is_admin: bool

    model_config = ConfigDict(from_attributes=True)


__all__ = ["UserLogin", "Token", "TokenPayload", "TokenRefresh", "UserResponse"]
