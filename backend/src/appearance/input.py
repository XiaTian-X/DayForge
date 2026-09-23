"""Bounded blocking file/entry reads; transport owns deadlines and stream lifetime."""

import hashlib
from typing import BinaryIO

from src.v2.appearance import IconBlob


class ImageInputError(ValueError):
    """Incomplete, oversized or identity-mismatched image input."""


def read_icon_bytes(source: BinaryIO, expected: IconBlob) -> bytes:
    """Freeze one stream through EOF, reading at most declared length plus one.

    The caller closes the stream and supplies transport cancellation/timeouts.
    A result proves byte identity, not format safety, ownership or installation.
    Do not reopen a URI or ZIP entry after validating this returned value.
    """
    limit = expected.byte_length
    output = bytearray()
    digest = hashlib.sha256()
    while True:
        requested = min(65_536, limit + 1 - len(output))
        block = source.read(requested)
        if not isinstance(block, bytes) or len(block) > requested:
            # Nonblocking None or a broken read contract is not EOF/success.
            raise ImageInputError("IMAGE_READ_INVALID")
        if not block:
            if len(output) != limit:
                raise ImageInputError("IMAGE_BYTE_LENGTH")
            break
        if len(output) + len(block) > limit:
            raise ImageInputError("IMAGE_BYTE_LENGTH")
        output.extend(block)
        digest.update(block)
    if digest.hexdigest() != expected.sha256:
        raise ImageInputError("IMAGE_HASH")
    return bytes(output)
