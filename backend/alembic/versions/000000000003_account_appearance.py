"""Account-owned appearance metadata; online protocol and existing data unchanged."""

from alembic import context, op
import sqlalchemy as sa

revision = "000000000003"
down_revision = "000000000002"
branch_labels = None
depends_on = None

# Frozen definitions: never import evolving application models into a migration.
QUOTA_FIELDS = (
    "byte_limit",
    "reserved_bytes",
    "asset_limit",
    "reserved_assets",
    "metadata_byte_limit",
    "reserved_metadata_bytes",
    "catalog_sequence",
)
TABLES = (
    "appearance_accounts",
    "account_icon_blobs",
    "account_icon_assets",
    "account_icon_packs",
    "appearance_catalog",
)


def upgrade() -> None:
    op.create_table(
        "appearance_accounts",
        sa.Column("user_id", sa.Integer(), nullable=False),
        *(sa.Column(name, sa.BigInteger(), nullable=False) for name in QUOTA_FIELDS),
        *(
            sa.CheckConstraint(
                f"{name} >= 0 AND {name} <= 9223372036854775807",
                name=f"ck_appearance_account_{name}",
            )
            for name in QUOTA_FIELDS
        ),
        sa.PrimaryKeyConstraint("user_id"),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"]),
    )
    op.create_table(
        "account_icon_blobs",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("owner_user_id", sa.Integer(), nullable=False),
        sa.Column("sha256", sa.String(64), nullable=False),
        sa.Column("byte_length", sa.BigInteger(), nullable=False),
        sa.Column("media_type", sa.String(24), nullable=False),
        sa.Column("width", sa.Integer(), nullable=False),
        sa.Column("height", sa.Integer(), nullable=False),
        sa.Column("ready_at", sa.DateTime(), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.ForeignKeyConstraint(["owner_user_id"], ["appearance_accounts.user_id"]),
        sa.UniqueConstraint("owner_user_id", "sha256", name="uq_icon_blob_owner_hash"),
        sa.UniqueConstraint("owner_user_id", "id", name="uq_icon_blob_owner_id"),
        sa.CheckConstraint("length(sha256) = 64", name="ck_icon_blob_hash_length"),
        sa.CheckConstraint(
            "byte_length >= 1 AND byte_length <= 2097152", name="ck_icon_blob_bytes"
        ),
        sa.CheckConstraint(
            "media_type IN ('image/png','image/svg+xml')", name="ck_icon_blob_media"
        ),
        sa.CheckConstraint(
            "media_type != 'image/svg+xml' OR byte_length <= 524288",
            name="ck_icon_blob_svg_bytes",
        ),
        sa.CheckConstraint(
            "width >= 1 AND width <= 1024 AND height >= 1 AND height <= 1024",
            name="ck_icon_blob_dimensions",
        ),
    )
    op.create_table(
        "account_icon_assets",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("owner_user_id", sa.Integer(), nullable=False),
        sa.Column("public_id", sa.String(36), nullable=False),
        sa.Column("light_blob_id", sa.Integer(), nullable=False),
        sa.Column("dark_blob_id", sa.Integer(), nullable=True),
        sa.Column("metadata_json", sa.Text(), nullable=False),
        sa.Column("metadata_bytes", sa.BigInteger(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.ForeignKeyConstraint(["owner_user_id"], ["appearance_accounts.user_id"]),
        sa.UniqueConstraint(
            "owner_user_id", "public_id", name="uq_icon_asset_owner_uuid"
        ),
        sa.UniqueConstraint("owner_user_id", "id", name="uq_icon_asset_owner_id"),
        sa.ForeignKeyConstraint(
            ["owner_user_id", "light_blob_id"],
            ["account_icon_blobs.owner_user_id", "account_icon_blobs.id"],
            name="fk_icon_asset_owner_light",
        ),
        sa.ForeignKeyConstraint(
            ["owner_user_id", "dark_blob_id"],
            ["account_icon_blobs.owner_user_id", "account_icon_blobs.id"],
            name="fk_icon_asset_owner_dark",
        ),
        sa.CheckConstraint(
            "metadata_bytes >= 1 AND metadata_bytes <= 9223372036854775807",
            name="ck_icon_asset_metadata_bytes",
        ),
    )
    op.create_table(
        "account_icon_packs",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("owner_user_id", sa.Integer(), nullable=False),
        sa.Column("pack_uuid", sa.String(36), nullable=False),
        sa.Column("revision", sa.Integer(), nullable=False),
        sa.Column("metadata_json", sa.Text(), nullable=False),
        sa.Column("metadata_bytes", sa.BigInteger(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.ForeignKeyConstraint(["owner_user_id"], ["appearance_accounts.user_id"]),
        sa.UniqueConstraint(
            "owner_user_id", "pack_uuid", "revision", name="uq_icon_pack_owner_version"
        ),
        sa.UniqueConstraint("owner_user_id", "id", name="uq_icon_pack_owner_id"),
        sa.CheckConstraint(
            "revision >= 1 AND revision <= 2147483647", name="ck_icon_pack_revision"
        ),
        sa.CheckConstraint(
            "metadata_bytes >= 1 AND metadata_bytes <= 9223372036854775807",
            name="ck_icon_pack_metadata_bytes",
        ),
    )
    op.create_table(
        "appearance_catalog",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("owner_user_id", sa.Integer(), nullable=False),
        sa.Column("sequence", sa.BigInteger(), nullable=False),
        sa.Column("kind", sa.String(8), nullable=False),
        sa.Column("asset_id", sa.Integer(), nullable=True),
        sa.Column("pack_version_id", sa.Integer(), nullable=True),
        sa.PrimaryKeyConstraint("id"),
        sa.ForeignKeyConstraint(["owner_user_id"], ["appearance_accounts.user_id"]),
        sa.UniqueConstraint(
            "owner_user_id", "sequence", name="uq_appearance_catalog_sequence"
        ),
        sa.UniqueConstraint("asset_id", name="uq_appearance_catalog_asset"),
        sa.UniqueConstraint("pack_version_id", name="uq_appearance_catalog_pack"),
        sa.ForeignKeyConstraint(
            ["owner_user_id", "asset_id"],
            ["account_icon_assets.owner_user_id", "account_icon_assets.id"],
            name="fk_appearance_catalog_owner_asset",
        ),
        sa.ForeignKeyConstraint(
            ["owner_user_id", "pack_version_id"],
            ["account_icon_packs.owner_user_id", "account_icon_packs.id"],
            name="fk_appearance_catalog_owner_pack",
        ),
        sa.CheckConstraint(
            "sequence >= 1 AND sequence <= 9223372036854775807",
            name="ck_appearance_catalog_sequence",
        ),
        sa.CheckConstraint(
            "(kind = 'asset' AND asset_id IS NOT NULL AND pack_version_id IS NULL) OR (kind = 'pack' AND pack_version_id IS NOT NULL AND asset_id IS NULL)",
            name="ck_appearance_catalog_identity",
        ),
    )


def downgrade() -> None:
    if context.is_offline_mode():
        raise RuntimeError("appearance downgrade requires an online data-safety check")
    connection = op.get_bind()
    for table in TABLES:
        if connection.execute(sa.text(f"SELECT 1 FROM {table} LIMIT 1")).first():
            raise RuntimeError(
                "appearance data exists; use a matching backup or forward repair"
            )
    for table in reversed(TABLES):
        op.drop_table(table)
