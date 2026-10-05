"""Persist explicit registration evidence without guessing legacy device versions."""

from alembic import context, op
import sqlalchemy as sa


revision = "000000000006"
down_revision = "000000000005"
branch_labels = None
depends_on = None

# Frozen SQL; this migration cannot depend on a future application validator.
PROTOCOL_CHECK = (
    "registered_protocol_version IS NULL OR "
    "(registered_protocol_version >= 1 AND registered_protocol_version <= 2147483647)"
)


def upgrade() -> None:
    op.add_column(
        "client_devices",
        sa.Column(
            "registered_protocol_version",
            sa.Integer(),
            sa.CheckConstraint(
                PROTOCOL_CHECK, name="ck_client_device_registered_protocol"
            ),
            nullable=True,
        ),
    )


def downgrade() -> None:
    if context.is_offline_mode():
        raise RuntimeError(
            "device protocol downgrade requires an online evidence check"
        )
    if (
        op.get_bind()
        .execute(
            sa.text(
                "SELECT 1 FROM client_devices WHERE registered_protocol_version IS NOT NULL LIMIT 1"
            )
        )
        .first()
    ):
        raise RuntimeError(
            "registered device protocol evidence exists; use forward repair or a matching backup"
        )
    op.drop_column("client_devices", "registered_protocol_version")
