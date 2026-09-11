"""Configuration management for the backend application."""
import os
import warnings
from typing import Optional
from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict
from functools import lru_cache


# Default JWT secret for development - should be overridden in production
DEFAULT_JWT_SECRET = "your-secret-key-change-in-production"


class Settings(BaseSettings):
    """Application settings loaded from environment variables.

    Attributes:
        DATABASE_TYPE: Database type ('sqlite' or 'postgresql')
        DATABASE_URL: Full database URL (optional, overrides other DB settings)
        SQLITE_DB_PATH: Path to SQLite database file
        POSTGRES_HOST: PostgreSQL host
        POSTGRES_USER: PostgreSQL username
        POSTGRES_PASSWORD: PostgreSQL password
        POSTGRES_DB: PostgreSQL database name
        POSTGRES_PORT: PostgreSQL port
        JWT_SECRET_KEY: Secret key for JWT encoding/decoding
        JWT_ALGORITHM: Algorithm for JWT encoding (default: HS256)
        ACCESS_TOKEN_EXPIRE_MINUTES: Access token expiration in minutes
        REFRESH_TOKEN_EXPIRE_DAYS: Refresh token expiration in days
        CORS_ORIGINS: List of allowed CORS origins
    """

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore"
    )

    # Database settings
    DATABASE_TYPE: str = "sqlite"
    DATABASE_URL: Optional[str] = None
    SQLITE_DB_PATH: str = "./dev.db"

    # PostgreSQL settings
    POSTGRES_HOST: str = "localhost"
    POSTGRES_USER: str = "postgres"
    POSTGRES_PASSWORD: str = "postgres"
    POSTGRES_DB: str = "dayforge"
    POSTGRES_PORT: int = 5432

    # JWT settings
    JWT_SECRET_KEY: str = DEFAULT_JWT_SECRET
    JWT_ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 30
    REFRESH_TOKEN_EXPIRE_DAYS: int = 30

    # Runtime environment and optional explicit admin bootstrap.
    ENVIRONMENT: str = "development"
    ADMIN_USERNAME: Optional[str] = None
    ADMIN_PASSWORD: Optional[str] = None

    # CORS settings
    CORS_ORIGINS: list[str] = ["http://localhost:3000", "http://localhost:5173", "http://localhost:8080", "http://localhost:4200"]

    @model_validator(mode="after")
    def validate_security_settings(self):
        """Allow convenient local defaults but fail closed in production."""
        is_production = self.ENVIRONMENT.lower() == "production"
        if bool(self.ADMIN_USERNAME) != bool(self.ADMIN_PASSWORD):
            raise ValueError("ADMIN_USERNAME and ADMIN_PASSWORD must be configured together")
        if self.ADMIN_PASSWORD and len(self.ADMIN_PASSWORD) < 12:
            raise ValueError("ADMIN_PASSWORD must contain at least 12 characters")
        if is_production and self.JWT_SECRET_KEY == DEFAULT_JWT_SECRET:
            raise ValueError("JWT_SECRET_KEY must be explicitly configured in production")
        if self.JWT_SECRET_KEY == DEFAULT_JWT_SECRET and not is_production:
            warnings.warn(
                "WARNING: Using default JWT_SECRET_KEY. "
                "This is allowed for local development only.",
                UserWarning
            )
        return self


@lru_cache()
def get_settings() -> Settings:
    """Get cached settings instance (singleton pattern).

    Returns:
        Settings instance with application configuration.
    """
    return Settings()


def get_database_url() -> str:
    """Get database URL based on DATABASE_TYPE setting.

    Returns SQLite URL when DATABASE_TYPE is 'sqlite'.
    Returns PostgreSQL URL when DATABASE_TYPE is 'postgresql'.

    Returns:
        Database URL string for async SQLAlchemy connection.
    """
    settings = get_settings()

    if settings.DATABASE_URL:
        return settings.DATABASE_URL

    if settings.DATABASE_TYPE == "postgresql":
        return (
            f"postgresql+asyncpg://{settings.POSTGRES_USER}:{settings.POSTGRES_PASSWORD}"
            f"@{settings.POSTGRES_HOST}:{settings.POSTGRES_PORT}/{settings.POSTGRES_DB}"
        )
    else:
        # Default to SQLite
        return f"sqlite+aiosqlite:///{settings.SQLITE_DB_PATH}"


# Convenience singleton instance
settings = get_settings()


__all__ = ["Settings", "settings", "get_settings", "get_database_url", "DEFAULT_JWT_SECRET"]
