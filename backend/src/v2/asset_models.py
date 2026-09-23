"""Account-owned immutable appearance metadata; no upload readiness is inferred."""

from datetime import datetime

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    ForeignKeyConstraint,
    Text,
    UniqueConstraint,
)
from sqlmodel import Field, SQLModel

from src.time_utils import utc_now


class AppearanceAccount(SQLModel, table=True):
    __tablename__ = "appearance_accounts"
    __table_args__ = tuple(
        CheckConstraint(
            f"{name} >= 0 AND {name} <= 9223372036854775807",
            name=f"ck_appearance_account_{name}",
        )
        for name in (
            "byte_limit",
            "reserved_bytes",
            "asset_limit",
            "reserved_assets",
            "metadata_byte_limit",
            "reserved_metadata_bytes",
            "catalog_sequence",
        )
    )

    user_id: int = Field(foreign_key="users.id", primary_key=True)
    byte_limit: int = Field(default=268_435_456, sa_type=BigInteger)
    reserved_bytes: int = Field(default=0, sa_type=BigInteger)
    asset_limit: int = Field(default=1000, sa_type=BigInteger)
    reserved_assets: int = Field(default=0, sa_type=BigInteger)
    metadata_byte_limit: int = Field(default=8_388_608, sa_type=BigInteger)
    reserved_metadata_bytes: int = Field(default=0, sa_type=BigInteger)
    catalog_sequence: int = Field(default=0, sa_type=BigInteger)


class AccountIconBlob(SQLModel, table=True):
    __tablename__ = "account_icon_blobs"
    __table_args__ = (
        UniqueConstraint("owner_user_id", "sha256", name="uq_icon_blob_owner_hash"),
        UniqueConstraint("owner_user_id", "id", name="uq_icon_blob_owner_id"),
        CheckConstraint("length(sha256) = 64", name="ck_icon_blob_hash_length"),
        CheckConstraint(
            "byte_length >= 1 AND byte_length <= 2097152", name="ck_icon_blob_bytes"
        ),
        CheckConstraint(
            "media_type IN ('image/png','image/svg+xml')", name="ck_icon_blob_media"
        ),
        CheckConstraint(
            "media_type != 'image/svg+xml' OR byte_length <= 524288",
            name="ck_icon_blob_svg_bytes",
        ),
        CheckConstraint(
            "width >= 1 AND width <= 1024 AND height >= 1 AND height <= 1024",
            name="ck_icon_blob_dimensions",
        ),
        CheckConstraint(
            "(ready_at IS NULL AND validation_profile IS NULL) OR "
            "(ready_at IS NOT NULL AND validation_profile IS NOT NULL AND "
            "((media_type = 'image/png' AND validation_profile = 'png-v1') OR "
            "(media_type = 'image/svg+xml' AND validation_profile = 'svg-v1')))",
            name="ck_icon_blob_ready_profile",
        ),
    )

    id: int | None = Field(default=None, primary_key=True)
    owner_user_id: int = Field(foreign_key="appearance_accounts.user_id")
    sha256: str = Field(max_length=64)
    byte_length: int = Field(sa_type=BigInteger)
    media_type: str = Field(max_length=24)
    width: int
    height: int
    # Set only by the future verified file-install transaction, never declaration.
    ready_at: datetime | None = None
    validation_profile: str | None = Field(default=None, max_length=16)
    created_at: datetime = Field(default_factory=utc_now)


class AccountIconAsset(SQLModel, table=True):
    __tablename__ = "account_icon_assets"
    __table_args__ = (
        UniqueConstraint("owner_user_id", "public_id", name="uq_icon_asset_owner_uuid"),
        UniqueConstraint("owner_user_id", "id", name="uq_icon_asset_owner_id"),
        ForeignKeyConstraint(
            ["owner_user_id", "light_blob_id"],
            ["account_icon_blobs.owner_user_id", "account_icon_blobs.id"],
            name="fk_icon_asset_owner_light",
        ),
        ForeignKeyConstraint(
            ["owner_user_id", "dark_blob_id"],
            ["account_icon_blobs.owner_user_id", "account_icon_blobs.id"],
            name="fk_icon_asset_owner_dark",
        ),
        CheckConstraint(
            "metadata_bytes >= 1 AND metadata_bytes <= 9223372036854775807",
            name="ck_icon_asset_metadata_bytes",
        ),
    )

    id: int | None = Field(default=None, primary_key=True)
    owner_user_id: int = Field(foreign_key="appearance_accounts.user_id")
    public_id: str = Field(max_length=36)
    light_blob_id: int
    dark_blob_id: int | None = None
    metadata_json: str = Field(sa_type=Text)
    metadata_bytes: int = Field(sa_type=BigInteger)
    created_at: datetime = Field(default_factory=utc_now)


class AccountIconPack(SQLModel, table=True):
    __tablename__ = "account_icon_packs"
    __table_args__ = (
        UniqueConstraint(
            "owner_user_id", "pack_uuid", "revision", name="uq_icon_pack_owner_version"
        ),
        UniqueConstraint("owner_user_id", "id", name="uq_icon_pack_owner_id"),
        CheckConstraint(
            "revision >= 1 AND revision <= 2147483647", name="ck_icon_pack_revision"
        ),
        CheckConstraint(
            "metadata_bytes >= 1 AND metadata_bytes <= 9223372036854775807",
            name="ck_icon_pack_metadata_bytes",
        ),
    )

    id: int | None = Field(default=None, primary_key=True)
    owner_user_id: int = Field(foreign_key="appearance_accounts.user_id")
    pack_uuid: str = Field(max_length=36)
    revision: int
    metadata_json: str = Field(sa_type=Text)
    metadata_bytes: int = Field(sa_type=BigInteger)
    created_at: datetime = Field(default_factory=utc_now)


class AppearanceCatalog(SQLModel, table=True):
    __tablename__ = "appearance_catalog"
    __table_args__ = (
        UniqueConstraint(
            "owner_user_id", "sequence", name="uq_appearance_catalog_sequence"
        ),
        UniqueConstraint("asset_id", name="uq_appearance_catalog_asset"),
        UniqueConstraint("pack_version_id", name="uq_appearance_catalog_pack"),
        ForeignKeyConstraint(
            ["owner_user_id", "asset_id"],
            ["account_icon_assets.owner_user_id", "account_icon_assets.id"],
            name="fk_appearance_catalog_owner_asset",
        ),
        ForeignKeyConstraint(
            ["owner_user_id", "pack_version_id"],
            ["account_icon_packs.owner_user_id", "account_icon_packs.id"],
            name="fk_appearance_catalog_owner_pack",
        ),
        CheckConstraint(
            "sequence >= 1 AND sequence <= 9223372036854775807",
            name="ck_appearance_catalog_sequence",
        ),
        CheckConstraint(
            "(kind = 'asset' AND asset_id IS NOT NULL AND pack_version_id IS NULL) OR "
            "(kind = 'pack' AND pack_version_id IS NOT NULL AND asset_id IS NULL)",
            name="ck_appearance_catalog_identity",
        ),
    )

    id: int | None = Field(default=None, primary_key=True)
    owner_user_id: int = Field(foreign_key="appearance_accounts.user_id")
    sequence: int = Field(sa_type=BigInteger)
    kind: str = Field(max_length=8)
    asset_id: int | None = None
    pack_version_id: int | None = None
