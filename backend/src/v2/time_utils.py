"""Canonical UTC and IANA-timezone helpers for the V2 domain."""

from __future__ import annotations

from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from src.time_utils import as_utc


def require_iana_timezone(value: str) -> str:
    """Return a validated IANA zone name without silently accepting offsets."""
    if not value or value != value.strip():
        raise ValueError("timezone must be a non-empty IANA zone name")
    try:
        ZoneInfo(value)
    except (ZoneInfoNotFoundError, ValueError) as exc:
        raise ValueError("timezone must be a valid IANA zone name") from exc
    return value


def local_date_at(value: datetime, timezone_name: str) -> date:
    """Project an absolute instant into the supplied IANA timezone."""
    return (
        as_utc(value).astimezone(ZoneInfo(require_iana_timezone(timezone_name))).date()
    )


def elapsed_milliseconds(start: datetime, end: datetime) -> int:
    """Floor an absolute interval to milliseconds without floating-point loss."""
    return (as_utc(end) - as_utc(start)) // timedelta(milliseconds=1)


__all__ = ["as_utc", "elapsed_milliseconds", "local_date_at", "require_iana_timezone"]
