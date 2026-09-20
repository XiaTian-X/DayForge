"""Service-instance identity used to validate local/proxy endpoint failover."""

from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import select

from src.v2.models import ServerInstance, utc_now
from src.v2.schemas import ServerIdentityResponse


CAPABILITIES = [
    "sync_v2",
    "timer_commands",
    "timer_takeover",
    "duration_day_allocations",
    "iana_timezones",
    "device_capabilities",
    "household_admin",
]


async def get_or_create_server_identity(session: AsyncSession) -> ServerInstance:
    identity = await session.get(ServerInstance, 1)
    if identity is not None:
        return identity

    try:
        async with session.begin_nested():
            identity = ServerInstance(id=1)
            session.add(identity)
            await session.flush()
    except IntegrityError:
        result = await session.execute(
            select(ServerInstance).where(ServerInstance.id == 1)
        )
        identity = result.scalar_one()
    return identity


async def server_identity_response(session: AsyncSession) -> ServerIdentityResponse:
    identity = await get_or_create_server_identity(session)
    return ServerIdentityResponse(
        server_instance_id=identity.instance_uuid,
        sync_epoch=identity.sync_epoch,
        protocol_version=identity.protocol_version,
        capabilities=CAPABILITIES,
        server_time=utc_now(),
    )


__all__ = ["CAPABILITIES", "get_or_create_server_identity", "server_identity_response"]
