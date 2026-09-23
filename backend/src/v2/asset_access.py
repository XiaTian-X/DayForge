"""Read-only device/instance/epoch authorization for staged appearance services."""

from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import col, select

from src.auth.models import User
from src.v2.asset_api_contract import AssetSyncContext
from src.v2.device_service import (
    READ_CAPABILITY,
    STRUCTURE_CAPABILITY,
    capabilities_for_device,
)
from src.v2.errors import DomainError
from src.v2.invariants import require_internal
from src.v2.models import ClientDevice, ServerInstance


async def require_asset_access(
    session: AsyncSession, user: User, context: AssetSyncContext, *, write: bool = False
) -> int:
    """The User must come from authentication; no client-supplied owner is used.

    Do not touch last_seen, create a policy or initialize a server identity while
    checking read access. The future byte transfer must not hold a write lock.
    """
    owner = require_internal(user.id, "User.id")
    device = (
        await session.execute(
            select(ClientDevice).where(
                col(ClientDevice.user_id) == owner,
                col(ClientDevice.public_id) == context.device_id,
                col(ClientDevice.revoked_at).is_(None),
            )
        )
    ).scalar_one_or_none()
    if device is None:
        raise DomainError(
            "DEVICE_NOT_FOUND", "Device is not registered or has been revoked"
        )
    capabilities, _ = await capabilities_for_device(session, device)
    if (STRUCTURE_CAPABILITY if write else READ_CAPABILITY) not in capabilities:
        raise DomainError(
            "DEVICE_CAPABILITY_DENIED",
            "This device cannot access the requested appearance operation",
        )
    identity = await session.get(ServerInstance, 1)
    if identity is None or identity.instance_uuid != context.server_instance_id:
        raise DomainError(
            "SERVER_IDENTITY_MISMATCH",
            "The server identity no longer matches this request",
        )
    if identity.sync_epoch != context.sync_epoch:
        raise DomainError(
            "SYNC_EPOCH_MISMATCH",
            "The synchronization epoch no longer matches this request",
        )
    return owner
