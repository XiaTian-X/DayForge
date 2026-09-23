"""Bind blob readiness to a known format profile, preserving parent identities."""

from alembic import context, op
import sqlalchemy as sa


revision = "000000000005"
down_revision = "000000000004"
branch_labels = None
depends_on = None

# Frozen SQL, not imported from an evolving application validator.
PROFILE_CHECK = (
    "(ready_at IS NULL AND validation_profile IS NULL) OR "
    "(ready_at IS NOT NULL AND validation_profile IS NOT NULL AND "
    "((media_type = 'image/png' AND validation_profile = 'png-v1') OR "
    "(media_type = 'image/svg+xml' AND validation_profile = 'svg-v1')))"
)


def upgrade() -> None:
    if context.is_offline_mode():
        raise RuntimeError("asset profile upgrade requires an online data-safety check")
    if (
        op.get_bind()
        .execute(
            sa.text(
                "SELECT 1 FROM account_icon_blobs WHERE ready_at IS NOT NULL LIMIT 1"
            )
        )
        .first()
    ):
        raise RuntimeError(
            "unversioned ready blobs exist; verified forward repair is required"
        )
    # An inline ADD COLUMN CHECK validates existing rows without rebuilding the
    # parent table (and without disabling its dependent composite foreign keys).
    op.add_column(
        "account_icon_blobs",
        sa.Column(
            "validation_profile",
            sa.String(16),
            sa.CheckConstraint(PROFILE_CHECK, name="ck_icon_blob_ready_profile"),
            nullable=True,
        ),
    )


def downgrade() -> None:
    if context.is_offline_mode():
        raise RuntimeError(
            "asset profile downgrade requires an online data-safety check"
        )
    if (
        op.get_bind()
        .execute(
            sa.text(
                "SELECT 1 FROM account_icon_blobs WHERE validation_profile IS NOT NULL OR ready_at IS NOT NULL LIMIT 1"
            )
        )
        .first()
    ):
        raise RuntimeError(
            "installed appearance data exists; use a matching backup or forward repair"
        )
    op.drop_column("account_icon_blobs", "validation_profile")
