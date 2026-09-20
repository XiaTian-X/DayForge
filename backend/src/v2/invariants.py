"""Narrow required internal values without disguising corruption as domain success."""

from typing import TypeVar


T = TypeVar("T")


def require_internal(value: T | None, reference: str) -> T:
    """Require an already validated/persisted value, never validate client input.

    A broken reference is an internal failure and must propagate to the request
    transaction rollback, not be cached as an ordinary per-operation rejection.
    Optional domain relationships must keep their existing explicit handling.
    """
    if value is None:
        raise RuntimeError(f"Required internal value is missing: {reference}")
    return value
