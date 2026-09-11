"""Authentication database models."""
from sqlmodel import Field, SQLModel
from datetime import datetime
from typing import Optional
import uuid


class User(SQLModel, table=True):
    """User database model."""
    __tablename__ = "users"

    id: Optional[int] = Field(default=None, primary_key=True)
    public_id: str = Field(
        default_factory=lambda: str(uuid.uuid4()),
        unique=True,
        index=True,
        max_length=36,
    )
    username: str = Field(unique=True, index=True)
    phone: Optional[str] = Field(default=None, unique=True, index=True)
    email: Optional[str] = Field(default=None, unique=True, index=True)
    password_hash: str = Field()
    is_active: bool = Field(default=True)
    status: str = Field(default="active", max_length=20, index=True)
    is_verified: bool = Field(default=False)
    is_admin: bool = Field(default=False)
    auth_version: int = Field(default=1)
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(
        default_factory=datetime.utcnow,
        sa_column_kwargs={'onupdate': datetime.utcnow}
    )
