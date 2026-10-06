"""Captured v5 replica preconditions; never discover a new scope for old work."""

from dataclasses import dataclass
from uuid import UUID

from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import col, select

from src.v2.errors import DomainError
from src.v2.models import ServerInstance


PROTOCOL_HEADER = "X-DayForge-Protocol"
INSTANCE_HEADER = "X-DayForge-Server-Instance"
EPOCH_HEADER = "X-DayForge-Sync-Epoch"


def _canonical_uuid(value: str) -> bool:
    if type(value) is not str or len(value) != 36:
        return False
    try:
        return str(UUID(value)) == value
    except ValueError:
        return False


@dataclass(frozen=True, slots=True)
class ReplicaIdentity:
    """A captured precondition, NOT authentication or a database admission receipt."""

    server_instance_id: str
    sync_epoch: str

    def __post_init__(self) -> None:
        if not _canonical_uuid(self.server_instance_id) or not _canonical_uuid(
            self.sync_epoch
        ):
            raise ValueError("Replica identity requires canonical UUIDs")


def replica_from_headers(
    instance_headers: tuple[str, ...], epoch_headers: tuple[str, ...]
) -> ReplicaIdentity:
    """Inspect all raw values; no trimming, concatenation or UUID normalization."""
    if (
        type(instance_headers) is not tuple
        or type(epoch_headers) is not tuple
        or len(instance_headers) != 1
        or len(epoch_headers) != 1
    ):
        raise DomainError("INVALID_SYNC_CONTEXT", "Replica context is required")
    try:
        return ReplicaIdentity(instance_headers[0], epoch_headers[0])
    except ValueError as error:
        raise DomainError(
            "INVALID_SYNC_CONTEXT", "Replica context requires canonical UUIDs"
        ) from error


def match_replica_identity(
    identity: ServerInstance | None, expected: ReplicaIdentity
) -> None:
    if type(expected) is not ReplicaIdentity:
        raise DomainError("INVALID_SYNC_CONTEXT", "Replica context is required")
    if identity is None or identity.instance_uuid != expected.server_instance_id:
        raise DomainError(
            "SERVER_IDENTITY_MISMATCH",
            "The server identity no longer matches this request",
            conflict=True,
        )
    if identity.sync_epoch != expected.sync_epoch:
        raise DomainError(
            "SYNC_EPOCH_MISMATCH",
            "The synchronization epoch no longer matches this request",
            conflict=True,
        )


async def verify_replica_identity(
    session: AsyncSession, expected: ReplicaIdentity
) -> None:
    if type(expected) is not ReplicaIdentity:
        raise DomainError("INVALID_SYNC_CONTEXT", "Replica context is required")
    identity = (
        await session.execute(
            select(ServerInstance)
            .where(col(ServerInstance.id) == 1)
            .execution_options(populate_existing=True, autoflush=False)
        )
    ).scalar_one_or_none()
    match_replica_identity(identity, expected)


async def replay_replica(
    session: AsyncSession, next_protocol: bool, replica: ReplicaIdentity | None
) -> ReplicaIdentity | None:
    """Internal orchestration still needs an explicit captured scope before replay.

    This does not replace the HTTP authentication/version/device gate. Staged
    domain tests can use the new rules without switching the live server version.
    """
    if not next_protocol:
        if replica is not None:
            raise ValueError("v4 cannot use a v5 replay namespace")
        return None
    if replica is None:
        raise DomainError("INVALID_SYNC_CONTEXT", "Replica context is required")
    await verify_replica_identity(session, replica)
    return replica
