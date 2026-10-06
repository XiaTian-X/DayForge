"""Capture actual migrated replica identities for staged-domain tests only."""

from sqlalchemy.ext.asyncio import AsyncSession

from src.v2.models import ServerInstance
from src.v2.replica_context import ReplicaIdentity


async def replica_identity(session: AsyncSession) -> ReplicaIdentity:
    identity = await session.get(ServerInstance, 1)
    assert identity is not None
    return ReplicaIdentity(identity.instance_uuid, identity.sync_epoch)
