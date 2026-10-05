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


def _upgrade_required() -> DomainError:
    return DomainError(
        "CLIENT_UPGRADE_REQUIRED", "This request requires coordinated protocol v5"
    )


async def require_next_server(
    session: AsyncSession, protocol_headers: tuple[str, ...]
) -> None:
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


async def require_next_protocol(
    session: AsyncSession,
    user: User,
    device_id: str,
    protocol_headers: tuple[str, ...],
) -> ClientDevice:
    await require_next_server(session, protocol_headers)
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
