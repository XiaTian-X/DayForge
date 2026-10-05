"""Read-only shared v5 gate; legacy online v4 routes do not invoke it yet.

The caller authenticates User and passes every raw protocol header value. A
registration marker is compatibility evidence, not a trusted client attestation.
Every future sync/timer/material entry must use the same gate in its snapshot;
byte work must reauthenticate/recheck in a fresh snapshot after actual I/O.
"""

from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import col, select

from src.auth.models import User
from src.v2.errors import DomainError
from src.v2.invariants import require_internal
from src.v2.models import ClientDevice, ServerInstance
from src.v2.replica_context import (
    ReplicaIdentity,
    match_replica_identity,
    replica_from_headers,
)


def _upgrade_required() -> DomainError:
    return DomainError(
        "CLIENT_UPGRADE_REQUIRED", "This request requires coordinated protocol v5"
    )


async def require_next_server(
    session: AsyncSession, protocol_headers: tuple[str, ...]
) -> ServerInstance:
    """Pre-body gate; the device proof is checked once its context is known."""
    if type(protocol_headers) is not tuple or protocol_headers != ("5",):
        raise _upgrade_required()
    identity = (
        await session.execute(
            select(ServerInstance)
            .where(col(ServerInstance.id) == 1)
            .execution_options(populate_existing=True, autoflush=False)
        )
    ).scalar_one_or_none()
    if (
        identity is None
        or type(identity.protocol_version) is not int
        or identity.protocol_version != 5
    ):
        raise _upgrade_required()
    return identity


async def require_next_protocol(
    session: AsyncSession,
    user: User,
    device_id: str,
    protocol_headers: tuple[str, ...],
) -> ClientDevice:
    await require_next_server(session, protocol_headers)
    return await _require_next_device(session, user, device_id)


async def _require_next_device(
    session: AsyncSession, user: User, device_id: str
) -> ClientDevice:
    device = (
        await session.execute(
            select(ClientDevice)
            .where(
                col(ClientDevice.user_id) == require_internal(user.id, "User.id"),
                col(ClientDevice.public_id) == device_id,
                col(ClientDevice.revoked_at).is_(None),
            )
            .execution_options(populate_existing=True, autoflush=False)
        )
    ).scalar_one_or_none()
    if device is None:
        raise DomainError(
            "DEVICE_NOT_FOUND", "Device is not registered or has been revoked"
        )
    if (
        type(device.registered_protocol_version) is not int
        or device.registered_protocol_version != 5
    ):
        raise _upgrade_required()
    return device


async def require_next_replica(
    session: AsyncSession,
    protocol_headers: tuple[str, ...],
    instance_headers: tuple[str, ...],
    epoch_headers: tuple[str, ...],
) -> ReplicaIdentity:
    """Registration/pre-body gate, before there can be an owned device proof."""
    if type(protocol_headers) is not tuple or protocol_headers != ("5",):
        raise _upgrade_required()
    expected = replica_from_headers(instance_headers, epoch_headers)
    identity = await require_next_server(session, protocol_headers)
    match_replica_identity(identity, expected)
    return expected


async def require_next_business(
    session: AsyncSession,
    user: User,
    device_id: str,
    protocol_headers: tuple[str, ...],
    instance_headers: tuple[str, ...],
    epoch_headers: tuple[str, ...],
) -> tuple[ClientDevice, ReplicaIdentity]:
    """Future sync/timer admission; every captured raw context value is mandatory.

    Appearance retains its existing body/query context, authenticated separately.
    This gate does not mutate last_seen or flush pending caller changes.
    """
    expected = await require_next_replica(
        session, protocol_headers, instance_headers, epoch_headers
    )
    return await _require_next_device(session, user, device_id), expected
