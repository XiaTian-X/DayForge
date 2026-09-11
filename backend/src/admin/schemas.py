"""Pydantic schemas for admin endpoints."""
from pydantic import BaseModel, ConfigDict, Field
from typing import Literal, Optional
from datetime import datetime
from uuid import UUID


class AdminUserCreate(BaseModel):
    """Create a local account managed by an administrator."""
    username: str = Field(min_length=3, max_length=32, pattern=r"^[a-zA-Z0-9_]+$")
    password: str = Field(min_length=8, max_length=128)
    is_admin: bool = False


class UserStatusUpdate(BaseModel):
    """Request schema for updating user status."""
    is_active: bool


class AdminPasswordReset(BaseModel):
    """Request schema for admin password reset."""
    new_password: str = Field(min_length=8, max_length=128)


class AdminUserResponse(BaseModel):
    """Admin user response schema with all fields."""
    id: int
    public_id: UUID
    username: str
    email: Optional[str] = None
    phone: Optional[str] = None
    is_active: bool
    is_verified: bool
    is_admin: bool
    status: str
    created_at: datetime
    updated_at: datetime

    model_config = ConfigDict(from_attributes=True)


class AdminHouseholdCreate(BaseModel):
    name: str = Field(min_length=1, max_length=100)


class AdminHouseholdUpdate(BaseModel):
    name: Optional[str] = Field(default=None, min_length=1, max_length=100)
    is_active: Optional[bool] = None


class AdminHouseholdMemberUpsert(BaseModel):
    user_id: UUID
    role: Literal["owner", "admin", "member"] = "member"


class AdminHouseholdMemberResponse(BaseModel):
    membership_id: UUID
    user_id: UUID
    username: str
    role: str
    status: str
    joined_at: Optional[datetime] = None


class AdminHouseholdResponse(BaseModel):
    household_id: UUID
    name: str
    is_active: bool
    revision: int
    created_by_user_id: UUID
    members: list[AdminHouseholdMemberResponse] = Field(default_factory=list)


class AdminDeviceProvision(BaseModel):
    installation_id: str = Field(min_length=8, max_length=100)
    platform: Literal["desktop", "hardware", "service"]
    device_class: Literal["hardware", "automation"]
    display_name: Optional[str] = Field(default=None, max_length=100)


class AdminDeviceEditingUpdate(BaseModel):
    structural_edit_enabled: bool


__all__ = [
    "UserStatusUpdate",
    "AdminUserCreate",
    "AdminPasswordReset",
    "AdminUserResponse",
    "AdminHouseholdCreate",
    "AdminHouseholdUpdate",
    "AdminHouseholdMemberUpsert",
    "AdminHouseholdMemberResponse",
    "AdminHouseholdResponse",
    "AdminDeviceProvision",
    "AdminDeviceEditingUpdate",
]
