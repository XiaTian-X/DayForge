"""Synthetic bytes and independent descriptors for account file/worker tests."""

import hashlib

from src.v2.appearance import IconBlob


OWNER = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
OTHER = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
SVG = b'<svg width="1" height="1"><rect width="1" height="1"/></svg>'


def blob(data=SVG, media_type="image/svg+xml") -> IconBlob:
    return IconBlob(
        sha256=hashlib.sha256(data).hexdigest(),
        byte_length=len(data),
        media_type=media_type,
        width=1,
        height=1,
    )


def directory(root, owner=OWNER):
    return root / "accounts" / owner / "blobs"


def intents(store, owner=OWNER):
    with store.pending(owner) as pending:
        return list(pending)
