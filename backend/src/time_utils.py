"""UTC primitives and response normalization for server-owned timestamps."""

from datetime import UTC, datetime
from typing import Annotated

from pydantic import AfterValidator, Field


def utc_now() -> datetime:
    """Return an aware UTC instant, independent of the server's local timezone."""
    return datetime.now(UTC)


def as_utc(value: datetime) -> datetime:
    """Normalize an instant; naive values must be known stored UTC, never user input."""
    if value.tzinfo is None or value.utcoffset() is None:
        return value.replace(tzinfo=UTC)
    return value.astimezone(UTC)


# Response-only: SQLite's timezone-less reads retain their existing UTC meaning.
# Do not use this alias to relax the timezone requirement on incoming sync facts.
UTCResponseDatetime = Annotated[
    datetime,
    AfterValidator(as_utc),
    Field(description="UTC instant serialized with Z; fractional seconds are preserved."),
]
