"""Bounded maintenance-file reads and durable no-clobber publication.

Paths are trusted administrator-selected backup/staging directories. Archive
entries never become filesystem paths. Callers stop application writes before
restore and retain private staging ownership through all SQLite connections.
"""

from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import stat
from typing import Any, BinaryIO, Iterator

from src.storage.errors import StorageValidationError


MAX_DATABASE_BYTES = 1024**4
MAX_MANIFEST_BYTES = 65_536


@contextmanager
def regular_file(path: Path) -> Iterator[BinaryIO]:
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            raise StorageValidationError("backup component must be a regular file")
        # Repeated archive payload reads must reach this opened file, not a
        # BufferedReader's stale window after external in-place corruption.
        with os.fdopen(descriptor, "rb", buffering=0, closefd=False) as source:
            yield source
    finally:
        os.close(descriptor)


def checked_size(value: Any, *, maximum: int = MAX_DATABASE_BYTES) -> int:
    if type(value) is not int or not 0 <= value <= maximum:
        raise StorageValidationError("invalid backup component size")
    return value


def checked_digest(value: Any) -> str:
    if type(value) is not str or re.fullmatch("[0-9a-f]{64}", value) is None:
        raise StorageValidationError("invalid backup component checksum")
    return value


def _write(output: BinaryIO, data: bytes) -> None:
    remaining = memoryview(data)
    while remaining:
        written = output.write(remaining)
        if type(written) is not int or not 0 < written <= len(remaining):
            raise OSError("backup write made no valid progress")
        remaining = remaining[written:]


def verify_stream(
    source: BinaryIO, size: int, digest: str, *, output: BinaryIO | None = None
) -> None:
    size = checked_size(size)
    digest = checked_digest(digest)
    source.seek(0)
    actual = hashlib.sha256()
    consumed = 0
    while True:
        requested = min(65_536, size + 1 - consumed)
        block = source.read(requested)
        if not isinstance(block, bytes) or len(block) > requested:
            raise StorageValidationError("invalid backup read")
        if not block:
            break
        consumed += len(block)
        if consumed > size:
            raise StorageValidationError("backup checksum mismatch (size)")
        actual.update(block)
        if output is not None:
            _write(output, block)
    if consumed != size or actual.hexdigest() != digest:
        raise StorageValidationError("backup checksum mismatch")


def file_fingerprint(path: Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    with regular_file(path) as source:
        for block in iter(lambda: source.read(65_536), b""):
            size += len(block)
            checked_size(size)
            digest.update(block)
    return size, digest.hexdigest()


def _unique_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    values: dict[str, Any] = {}
    for key, value in pairs:
        if key in values:
            raise StorageValidationError("duplicate backup manifest key")
        values[key] = value
    return values


def _invalid_constant(_value: str) -> None:
    raise StorageValidationError("invalid backup manifest numeric constant")


def read_manifest(path: Path) -> dict[str, Any]:
    with regular_file(path) as source:
        data = source.read(MAX_MANIFEST_BYTES + 1)
    if len(data) > MAX_MANIFEST_BYTES:
        raise StorageValidationError("backup manifest exceeds byte budget")
    try:
        value = json.loads(
            data, object_pairs_hook=_unique_pairs, parse_constant=_invalid_constant
        )
    except (ValueError, RecursionError) as error:
        raise StorageValidationError("invalid backup manifest") from error
    if not isinstance(value, dict):
        raise StorageValidationError("invalid backup manifest object")
    return value


def sync_directory(directory: Path) -> None:
    descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def durable_file(path: Path) -> None:
    os.chmod(path, 0o600)
    with regular_file(path) as source:
        os.fsync(source.fileno())


def publish_new(staged: Path, destination: Path) -> None:
    """Same-filesystem link publication never overwrites an existing backup.

    A failure can leave an unpublished component, not a complete manifest set.
    Caller cleans only its private staging path, never guesses other orphans.
    """
    durable_file(staged)
    os.link(staged, destination, follow_symlinks=False)
    sync_directory(destination.parent)


def write_manifest(path: Path, value: dict[str, Any]) -> None:
    data = (
        json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False)
        + "\n"
    ).encode("utf-8")
    if len(data) > MAX_MANIFEST_BYTES:
        raise StorageValidationError("backup manifest exceeds byte budget")
    with path.open("xb") as output:
        os.chmod(path, 0o600)
        _write(output, data)
        output.flush()
        os.fsync(output.fileno())
