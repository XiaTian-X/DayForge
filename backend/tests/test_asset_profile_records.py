from datetime import UTC, datetime

import pytest

from src.v2.asset_models import AccountIconBlob
from src.v2.asset_records import blob_ready
from src.v2.errors import DomainError


STAMP = datetime(2026, 9, 23, tzinfo=UTC)


def stored(media="image/png", ready=None, profile=None):
    return AccountIconBlob(
        owner_user_id=1,
        sha256="a" * 64,
        byte_length=24,
        width=1,
        height=1,
        media_type=media,
        ready_at=ready,
        validation_profile=profile,
    )


@pytest.mark.parametrize(
    "media,profile", [("image/png", "png-v1"), ("image/svg+xml", "svg-v1")]
)
def test_known_metadata_state_does_not_assert_file_existence(media, profile):
    assert not blob_ready(stored(media))
    assert blob_ready(stored(media, STAMP, profile))


@pytest.mark.parametrize(
    "media,ready,profile",
    [
        ("image/png", None, "png-v1"),
        ("image/png", STAMP, None),
        ("image/png", STAMP, "svg-v1"),
        ("image/svg+xml", STAMP, "png-v1"),
        ("image/png", STAMP, "png-v2"),
        ("unknown", STAMP, "png-v1"),
        ("image/png", "2026-09-23", "png-v1"),
    ],
)
def test_corrupt_profile_state_cannot_be_advertised_as_ready(media, ready, profile):
    with pytest.raises(DomainError) as caught:
        blob_ready(stored(media, ready, profile))
    assert caught.value.code == "ASSET_METADATA_CORRUPT"
