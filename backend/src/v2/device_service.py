"""Server-owned device capabilities and soft primary-editor policy."""

from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import select

from src.v2.models import ClientDevice, UserSyncPolicy, utc_now
from src.v2.schemas import DeviceResponse


READ_CAPABILITY = "sync.read"
FACT_CAPABILITY = "facts.append"
TIMER_CAPABILITY = "timer.control"
STRUCTURE_CAPABILITY = "structure.write"
SELF_MANAGEMENT_CAPABILITY = "devices.manage_self"

STRUCTURAL_ENTITY_TYPES = frozenset({"plan_node", "metric", "activity_metric_link"})


async def get_or_create_policy(session: AsyncSession, user_id: int) -> UserSyncPolicy:
    policy = await session.get(UserSyncPolicy, user_id)
    if policy is not None:
        return policy
    try:
        async with session.begin_nested():
            policy = UserSyncPolicy(user_id=user_id)
            session.add(policy)
            await session.flush()
    except IntegrityError:
        policy = await session.get(UserSyncPolicy, user_id)
        if policy is None:
            raise
    return policy


async def capabilities_for_device(
    session: AsyncSession,
    device: ClientDevice,
) -> tuple[list[str], bool]:
    # Reading capabilities must not initialize policy state. In particular, an
    # administrator may inspect/provision hardware before the first interactive
    # client registers, and a revoked primary intentionally leaves an existing
    # policy with no primary until an explicit takeover.
    policy = await session.get(UserSyncPolicy, device.user_id)
    is_primary = policy is not None and policy.primary_editor_device_id == device.id
    capabilities = [READ_CAPABILITY, FACT_CAPABILITY, TIMER_CAPABILITY]
    if device.device_class == "interactive":
        capabilities.append(SELF_MANAGEMENT_CAPABILITY)
        if is_primary or device.structural_edit_enabled:
            capabilities.append(STRUCTURE_CAPABILITY)
    return capabilities, is_primary


async def to_device_response(session: AsyncSession, device: ClientDevice) -> DeviceResponse:
    capabilities, is_primary = await capabilities_for_device(session, device)
    return DeviceResponse(
        device_id=device.public_id,
        installation_id=device.installation_id,
        platform=device.platform,
        device_class=device.device_class,
        app_version=device.app_version,
        display_name=device.display_name,
        is_primary_editor=is_primary,
        structural_edit_enabled=device.structural_edit_enabled,
        capability_revision=device.capability_revision,
        capabilities=capabilities,
        last_seen_at=device.last_seen_at,
    )


async def assign_first_primary(session: AsyncSession, device: ClientDevice) -> None:
    if device.device_class != "interactive":
        return
    # Auto-assignment is a one-time account initialization rule. An existing
    # policy with a NULL primary means the previous primary was revoked and must
    # not be silently replaced by whichever device happens to sync next.
    if await session.get(UserSyncPolicy, device.user_id) is not None:
        return
    try:
        async with session.begin_nested():
            session.add(
                UserSyncPolicy(
                    user_id=device.user_id,
                    primary_editor_device_id=device.id,
                )
            )
            await session.flush()
    except IntegrityError:
        # Another concurrent registration initialized the account policy.
        return
    device.capability_revision += 1


async def make_primary(session: AsyncSession, device: ClientDevice) -> None:
    if device.device_class != "interactive" or device.revoked_at is not None:
        raise ValueError("Only active interactive devices can be primary editors")
    policy = await get_or_create_policy(session, device.user_id)
    if policy.primary_editor_device_id == device.id:
        return
    previous = (
        await session.get(ClientDevice, policy.primary_editor_device_id)
        if policy.primary_editor_device_id is not None
        else None
    )
    if previous is not None:
        previous.capability_revision += 1
    policy.primary_editor_device_id = device.id
    policy.revision += 1
    policy.updated_at = utc_now()
    device.capability_revision += 1


async def set_structural_editing(
    session: AsyncSession,
    device: ClientDevice,
    enabled: bool,
) -> None:
    if device.device_class != "interactive" or device.revoked_at is not None:
        raise ValueError("Only active interactive devices can edit structure")
    if device.structural_edit_enabled == enabled:
        return
    device.structural_edit_enabled = enabled
    device.capability_revision += 1


async def revoke(session: AsyncSession, device: ClientDevice) -> None:
    if device.revoked_at is not None:
        return
    device.revoked_at = utc_now()
    device.capability_revision += 1
    policy = await get_or_create_policy(session, device.user_id)
    if policy.primary_editor_device_id == device.id:
        policy.primary_editor_device_id = None
        policy.revision += 1
        policy.updated_at = utc_now()


__all__ = [
    "STRUCTURAL_ENTITY_TYPES",
    "STRUCTURE_CAPABILITY",
    "assign_first_primary",
    "capabilities_for_device",
    "get_or_create_policy",
    "make_primary",
    "revoke",
    "set_structural_editing",
    "to_device_response",
]
