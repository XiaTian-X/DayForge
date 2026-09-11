"""Admin router for user management endpoints."""
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, status
from sqlmodel import select
from sqlalchemy.ext.asyncio import AsyncSession

from src.database import get_session
from src.auth.models import User
from src.auth.service import get_password_hash
from src.admin.dependencies import get_admin_user
from src.admin.schemas import (
    AdminDeviceEditingUpdate,
    AdminDeviceProvision,
    AdminHouseholdCreate,
    AdminHouseholdMemberResponse,
    AdminHouseholdMemberUpsert,
    AdminHouseholdResponse,
    AdminHouseholdUpdate,
    UserStatusUpdate,
    AdminUserCreate,
    AdminPasswordReset,
    AdminUserResponse,
)
from src.v2.device_service import make_primary, revoke, set_structural_editing, to_device_response
from src.v2.models import ClientDevice, Household, HouseholdMembership, utc_now
from src.v2.schemas import DeviceResponse

router = APIRouter(prefix="/admin", tags=["Admin"])


@router.post("/users", response_model=AdminUserResponse, status_code=status.HTTP_201_CREATED)
async def create_user(
    user_data: AdminUserCreate,
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_session),
):
    """Create a local account. Public self-registration is intentionally disabled."""
    result = await session.execute(select(User).where(User.username == user_data.username))
    if result.scalar() is not None:
        raise HTTPException(status_code=409, detail="Username already exists")

    user = User(
        username=user_data.username,
        password_hash=get_password_hash(user_data.password),
        is_admin=user_data.is_admin,
    )
    session.add(user)
    await session.commit()
    await session.refresh(user)
    return user


@router.get("/users", response_model=list[AdminUserResponse])
async def list_users(
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_session),
):
    """List all users. Requires admin role."""
    result = await session.execute(select(User))
    users = result.scalars().all()
    return users


@router.post("/users/{user_id}/reset-password")
async def reset_user_password(
    user_id: int,
    reset_data: AdminPasswordReset,
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_session),
):
    """Reset a user's password. Requires admin role."""
    result = await session.execute(select(User).where(User.id == user_id))
    user = result.scalar()

    if not user:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="User not found",
        )

    user.password_hash = get_password_hash(reset_data.new_password)
    user.auth_version += 1
    await session.commit()

    return {"message": "Password reset successfully"}


@router.put("/users/{user_id}/status", response_model=AdminUserResponse)
async def update_user_status(
    user_id: int,
    status_data: UserStatusUpdate,
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_session),
):
    """Enable or disable a user account. Requires admin role."""
    result = await session.execute(select(User).where(User.id == user_id))
    user = result.scalar()

    if not user:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="User not found",
        )

    if user.id == admin.id and not status_data.is_active:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Administrators cannot disable their own account",
        )

    user.is_active = status_data.is_active
    user.status = "active" if status_data.is_active else "disabled"
    if not status_data.is_active:
        user.auth_version += 1
    await session.commit()
    await session.refresh(user)

    return user


async def _household_or_404(session: AsyncSession, household_id: UUID) -> Household:
    result = await session.execute(
        select(Household).where(Household.public_id == str(household_id))
    )
    household = result.scalar_one_or_none()
    if household is None:
        raise HTTPException(status_code=404, detail="Household not found")
    return household


async def _household_response(
    session: AsyncSession,
    household: Household,
) -> AdminHouseholdResponse:
    creator = await session.get(User, household.created_by_user_id)
    rows = await session.execute(
        select(HouseholdMembership, User)
        .join(User, HouseholdMembership.user_id == User.id)
        .where(HouseholdMembership.household_id == household.id)
        .order_by(User.username)
    )
    members = [
        AdminHouseholdMemberResponse(
            membership_id=membership.public_id,
            user_id=user.public_id,
            username=user.username,
            role=membership.role,
            status=membership.status,
            joined_at=membership.joined_at,
        )
        for membership, user in rows.all()
    ]
    return AdminHouseholdResponse(
        household_id=household.public_id,
        name=household.name,
        is_active=household.deleted_at is None,
        revision=household.revision,
        created_by_user_id=creator.public_id,
        members=members,
    )


@router.post(
    "/households",
    response_model=AdminHouseholdResponse,
    status_code=status.HTTP_201_CREATED,
)
async def create_household(
    request: AdminHouseholdCreate,
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_session),
) -> AdminHouseholdResponse:
    household = Household(name=request.name, created_by_user_id=admin.id)
    session.add(household)
    await session.flush()
    membership = HouseholdMembership(
        household_id=household.id,
        user_id=admin.id,
        role="owner",
        status="active",
        joined_at=utc_now(),
    )
    session.add(membership)
    await session.flush()
    return await _household_response(session, household)


@router.get("/households", response_model=list[AdminHouseholdResponse])
async def list_households(
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_session),
) -> list[AdminHouseholdResponse]:
    rows = await session.execute(select(Household).order_by(Household.name, Household.id))
    return [await _household_response(session, household) for household in rows.scalars().all()]


@router.patch("/households/{household_id}", response_model=AdminHouseholdResponse)
async def update_household(
    household_id: UUID,
    request: AdminHouseholdUpdate,
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_session),
) -> AdminHouseholdResponse:
    household = await _household_or_404(session, household_id)
    changed = False
    if request.name is not None and request.name != household.name:
        household.name = request.name
        changed = True
    if request.is_active is not None:
        deleted_at = None if request.is_active else utc_now()
        if (household.deleted_at is None) != request.is_active:
            household.deleted_at = deleted_at
            changed = True
    if changed:
        household.revision += 1
        household.updated_at = utc_now()
    return await _household_response(session, household)


@router.put(
    "/households/{household_id}/members",
    response_model=AdminHouseholdResponse,
)
async def upsert_household_member(
    household_id: UUID,
    request: AdminHouseholdMemberUpsert,
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_session),
) -> AdminHouseholdResponse:
    household = await _household_or_404(session, household_id)
    user_result = await session.execute(select(User).where(User.public_id == str(request.user_id)))
    user = user_result.scalar_one_or_none()
    if user is None:
        raise HTTPException(status_code=404, detail="User not found")
    member_result = await session.execute(
        select(HouseholdMembership).where(
            HouseholdMembership.household_id == household.id,
            HouseholdMembership.user_id == user.id,
        )
    )
    membership = member_result.scalar_one_or_none()
    now = utc_now()
    if membership is None:
        membership = HouseholdMembership(
            household_id=household.id,
            user_id=user.id,
            role=request.role,
            status="active",
            joined_at=now,
        )
        session.add(membership)
    else:
        membership.role = request.role
        membership.status = "active"
        membership.deleted_at = None
        membership.joined_at = membership.joined_at or now
        membership.revision += 1
        membership.updated_at = now
    household.revision += 1
    household.updated_at = now
    await session.flush()
    return await _household_response(session, household)


@router.delete(
    "/households/{household_id}/members/{user_id}",
    response_model=AdminHouseholdResponse,
)
async def remove_household_member(
    household_id: UUID,
    user_id: UUID,
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_session),
) -> AdminHouseholdResponse:
    household = await _household_or_404(session, household_id)
    result = await session.execute(
        select(HouseholdMembership)
        .join(User, HouseholdMembership.user_id == User.id)
        .where(
            HouseholdMembership.household_id == household.id,
            User.public_id == str(user_id),
            HouseholdMembership.status == "active",
        )
    )
    membership = result.scalar_one_or_none()
    if membership is None:
        raise HTTPException(status_code=404, detail="Active household member not found")
    if membership.role == "owner":
        owners = await session.execute(
            select(HouseholdMembership.id).where(
                HouseholdMembership.household_id == household.id,
                HouseholdMembership.role == "owner",
                HouseholdMembership.status == "active",
                HouseholdMembership.id != membership.id,
            )
        )
        if owners.first() is None:
            raise HTTPException(status_code=409, detail="A household must keep an active owner")
    now = utc_now()
    membership.status = "removed"
    membership.deleted_at = now
    membership.revision += 1
    membership.updated_at = now
    household.revision += 1
    household.updated_at = now
    await session.flush()
    return await _household_response(session, household)


async def _user_by_public_id(session: AsyncSession, user_id: UUID) -> User:
    result = await session.execute(select(User).where(User.public_id == str(user_id)))
    user = result.scalar_one_or_none()
    if user is None:
        raise HTTPException(status_code=404, detail="User not found")
    return user


async def _admin_device(
    session: AsyncSession,
    user: User,
    device_id: UUID,
    *,
    active_only: bool = True,
) -> ClientDevice:
    query = select(ClientDevice).where(
        ClientDevice.user_id == user.id,
        ClientDevice.public_id == str(device_id),
    )
    if active_only:
        query = query.where(ClientDevice.revoked_at.is_(None))
    result = await session.execute(query)
    device = result.scalar_one_or_none()
    if device is None:
        raise HTTPException(status_code=404, detail="Device not found")
    return device


@router.get("/users/{user_id}/devices", response_model=list[DeviceResponse])
async def list_user_devices(
    user_id: UUID,
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_session),
) -> list[DeviceResponse]:
    user = await _user_by_public_id(session, user_id)
    rows = await session.execute(
        select(ClientDevice).where(ClientDevice.user_id == user.id).order_by(ClientDevice.created_at)
    )
    return [await to_device_response(session, device) for device in rows.scalars().all()]


@router.post(
    "/users/{user_id}/devices",
    response_model=DeviceResponse,
    status_code=status.HTTP_201_CREATED,
)
async def provision_user_device(
    user_id: UUID,
    request: AdminDeviceProvision,
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_session),
) -> DeviceResponse:
    user = await _user_by_public_id(session, user_id)
    existing = await session.execute(
        select(ClientDevice).where(
            ClientDevice.user_id == user.id,
            ClientDevice.installation_id == request.installation_id,
        )
    )
    if existing.scalar_one_or_none() is not None:
        raise HTTPException(status_code=409, detail="Installation is already registered")
    device = ClientDevice(
        user_id=user.id,
        installation_id=request.installation_id,
        platform=request.platform,
        device_class=request.device_class,
        display_name=request.display_name,
    )
    session.add(device)
    await session.flush()
    return await to_device_response(session, device)


@router.post("/users/{user_id}/devices/{device_id}/make-primary", response_model=DeviceResponse)
async def admin_make_device_primary(
    user_id: UUID,
    device_id: UUID,
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_session),
) -> DeviceResponse:
    user = await _user_by_public_id(session, user_id)
    device = await _admin_device(session, user, device_id)
    try:
        await make_primary(session, device)
    except ValueError as error:
        raise HTTPException(status_code=400, detail=str(error)) from error
    return await to_device_response(session, device)


@router.patch("/users/{user_id}/devices/{device_id}/editing", response_model=DeviceResponse)
async def admin_update_device_editing(
    user_id: UUID,
    device_id: UUID,
    request: AdminDeviceEditingUpdate,
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_session),
) -> DeviceResponse:
    user = await _user_by_public_id(session, user_id)
    device = await _admin_device(session, user, device_id)
    try:
        await set_structural_editing(session, device, request.structural_edit_enabled)
    except ValueError as error:
        raise HTTPException(status_code=400, detail=str(error)) from error
    return await to_device_response(session, device)


@router.delete("/users/{user_id}/devices/{device_id}", status_code=status.HTTP_204_NO_CONTENT)
async def admin_revoke_device(
    user_id: UUID,
    device_id: UUID,
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_session),
) -> None:
    user = await _user_by_public_id(session, user_id)
    device = await _admin_device(session, user, device_id)
    await revoke(session, device)
