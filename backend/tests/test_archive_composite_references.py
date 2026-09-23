"""Account ownership FKs must not change the logical identity of each column."""

import pytest
from sqlalchemy import (
    Column,
    ForeignKey,
    ForeignKeyConstraint,
    Integer,
    MetaData,
    Table,
    UniqueConstraint,
)

from src.storage.logical_archive import _archive_reference
from src.storage.sqlite_maintenance import StorageValidationError


def test_owner_and_blob_references_remain_deterministic_with_composite_ownership():
    metadata = MetaData()
    users = Table("users", metadata, Column("id", Integer, primary_key=True))
    blobs = Table(
        "blobs",
        metadata,
        Column("id", Integer, primary_key=True),
        Column("owner_user_id", Integer, ForeignKey("users.id")),
        UniqueConstraint("owner_user_id", "id"),
    )
    assets = Table(
        "assets",
        metadata,
        Column("id", Integer, primary_key=True),
        Column("owner_user_id", Integer, ForeignKey("users.id")),
        Column("light_blob_id", Integer),
        Column("dark_blob_id", Integer),
        ForeignKeyConstraint(
            ["owner_user_id", "light_blob_id"], ["blobs.owner_user_id", "blobs.id"]
        ),
        ForeignKeyConstraint(
            ["owner_user_id", "dark_blob_id"], ["blobs.owner_user_id", "blobs.id"]
        ),
    )
    owner = _archive_reference(assets.c.owner_user_id)
    assert owner is not None and owner.column is users.c.id
    for name in ("light_blob_id", "dark_blob_id"):
        reference = _archive_reference(assets.c[name])
        assert reference is not None and reference.column is blobs.c.id
    assert _archive_reference(assets.c.id) is None


@pytest.mark.parametrize("kind", ["two-identities", "non-primary", "composite-primary"])
def test_unsupported_reference_shape_fails_instead_of_guessing(kind):
    metadata = MetaData()
    Table(
        "first",
        metadata,
        Column("id", Integer, primary_key=True),
        Column("alias", Integer, unique=True),
    )
    Table("second", metadata, Column("id", Integer, primary_key=True))
    Table(
        "pair",
        metadata,
        Column("left", Integer, primary_key=True),
        Column("right", Integer, primary_key=True),
    )
    references = {
        "two-identities": (ForeignKey("first.id"), ForeignKey("second.id")),
        "non-primary": (ForeignKey("first.alias"),),
        "composite-primary": (ForeignKey("pair.left"),),
    }
    child = Table(
        "child",
        metadata,
        Column("id", Integer, primary_key=True),
        Column("reference", Integer, *references[kind]),
    )
    with pytest.raises(StorageValidationError, match="ambiguous identity reference"):
        _archive_reference(child.c.reference)
