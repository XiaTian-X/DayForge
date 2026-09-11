"""API token database models."""

from datetime import datetime
from typing import Optional
from sqlmodel import Field, SQLModel


class ApiToken(SQLModel, table=True):
    """API token for programmatic access."""

    __tablename__ = "api_tokens"

    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(foreign_key="users.id", nullable=False, index=True)
    name: str = Field(max_length=100)
    token_hash: str = Field(unique=True, index=True)
    prefix: str = Field(max_length=11)
    last_used_at: Optional[datetime] = Field(default=None)
    expires_at: Optional[datetime] = Field(default=None)
    created_at: datetime = Field(default_factory=datetime.utcnow)
