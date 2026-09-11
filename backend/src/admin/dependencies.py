"""Admin dependencies for protected admin routes."""

from fastapi import Depends, HTTPException, status

from src.auth.dependencies import get_current_user
from src.auth.models import User


async def get_admin_user(
    current_user: User = Depends(get_current_user),
) -> User:
    """Get current user and verify admin role.

    Use as dependency in admin routes:
    ```
    @router.get("/admin/users")
    async def list_users(
        admin: User = Depends(get_admin_user)
    ):
        # admin is verified as an admin user
    ```
    """
    if not current_user.is_admin:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Admin access required",
        )
    return current_user
