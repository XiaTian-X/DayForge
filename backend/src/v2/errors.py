"""Shared stable domain errors for sync and timer operations."""

from typing import Any, Optional


class DomainError(Exception):
    """Stable application error that can be returned per sync operation."""

    def __init__(
        self,
        code: str,
        message: str,
        *,
        conflict: bool = False,
        entity: Optional[dict[str, Any]] = None,
        revision: Optional[int] = None,
        base_entity: Optional[dict[str, Any]] = None,
        local_entity: Optional[dict[str, Any]] = None,
        conflicting_fields: Optional[list[str]] = None,
        conflict_kind: Optional[str] = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.conflict = conflict
        self.entity = entity
        self.revision = revision
        self.base_entity = base_entity
        self.local_entity = local_entity
        self.conflicting_fields = conflicting_fields or []
        self.conflict_kind = conflict_kind
