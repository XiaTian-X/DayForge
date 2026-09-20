"""Configuration management for the backend application."""

import warnings
from typing import Optional
from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict
from functools import lru_cache

from src.storage.database_adapter import DatabaseAdapter, build_database_adapter


# Default JWT secret for development - should be overridden in production
DEFAULT_JWT_SECRET = "your-secret-key-change-in-production"


class Settings(BaseSettings):
    """Application settings loaded from environment variables.

    Attributes:
        DATABASE_TYPE: Supported database type (currently only 'sqlite')
        DATABASE_URL: Full SQLite async URL (optional, overrides SQLITE_DB_PATH)
        SQLITE_DB_PATH: Path to SQLite database file
        JWT_SECRET_KEY: Secret key for JWT encoding/decoding
        JWT_ALGORITHM: Algorithm for JWT encoding (default: HS256)
        ACCESS_TOKEN_EXPIRE_MINUTES: Access token expiration in minutes
        REFRESH_TOKEN_EXPIRE_DAYS: Refresh token expiration in days
        CORS_ORIGINS: List of allowed CORS origins
    """

    model_config = SettingsConfigDict(
        env_file=".env", env_file_encoding="utf-8", case_sensitive=False, extra="ignore"
    )

    # Database settings
    DATABASE_TYPE: str = "sqlite"
    DATABASE_URL: Optional[str] = None
    SQLITE_DB_PATH: str = "./dev.db"

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
    CORS_ORIGINS: list[str] = [
        "http://localhost:3000",
        "http://localhost:5173",
        "http://localhost:8080",
        "http://localhost:4200",
    ]

    @model_validator(mode="after")
    def validate_runtime_settings(self):
        """Fail closed for unsupported storage and unsafe production secrets."""
        build_database_adapter(
            self.DATABASE_TYPE, self.DATABASE_URL, self.SQLITE_DB_PATH
        )
        is_production = self.ENVIRONMENT.lower() == "production"
        if bool(self.ADMIN_USERNAME) != bool(self.ADMIN_PASSWORD):
            raise ValueError(
                "ADMIN_USERNAME and ADMIN_PASSWORD must be configured together"
            )
        if self.ADMIN_PASSWORD and len(self.ADMIN_PASSWORD) < 12:
            raise ValueError("ADMIN_PASSWORD must contain at least 12 characters")
        if is_production and self.JWT_SECRET_KEY == DEFAULT_JWT_SECRET:
            raise ValueError(
                "JWT_SECRET_KEY must be explicitly configured in production"
            )
        if self.JWT_SECRET_KEY == DEFAULT_JWT_SECRET and not is_production:
            warnings.warn(
                "WARNING: Using default JWT_SECRET_KEY. "
                "This is allowed for local development only.",
                UserWarning,
            )
        return self


@lru_cache()
def get_settings() -> Settings:
    """Get cached settings instance (singleton pattern).

    Returns:
        Settings instance with application configuration.
    """
    return Settings()


def get_database_adapter() -> DatabaseAdapter:
    """Return a validated adapter for the configured supported backend."""
    settings = get_settings()
    return build_database_adapter(
        settings.DATABASE_TYPE,
        settings.DATABASE_URL,
        settings.SQLITE_DB_PATH,
    )


def get_database_url() -> str:
    """Return the validated async application URL."""
    return get_database_adapter().async_url


def get_migration_database_url() -> str:
    """Return the validated synchronous URL used by Alembic and maintenance tools."""
    return get_database_adapter().migration_url


# Convenience singleton instance
settings = get_settings()


__all__ = [
    "Settings",
    "settings",
    "get_settings",
    "get_database_adapter",
    "get_database_url",
    "get_migration_database_url",
    "DEFAULT_JWT_SECRET",
]
