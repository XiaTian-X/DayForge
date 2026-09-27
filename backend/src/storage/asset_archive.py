"""Byte-only backup sidecar codec; database/manifest publication is separate.

Expected descriptors must come from the same validated database snapshot as the
backup, not an archive-supplied ownership index. This module never sets ready,
creates a deployment root, restores a database or authorizes an account.
"""

from collections.abc import Iterable
from dataclasses import dataclass
from io import BytesIO
import os
from typing import BinaryIO
import zipfile

from src.appearance.input import read_icon_bytes
from src.appearance.profiles import IMAGE_PROFILES
from src.storage.asset_files import AssetFiles, _blob, _inspect, _uuid
from src.storage.backup_zip import BackupZip, BackupZipError, ZipLimits
from src.v2.appearance import IconBlob


@dataclass(frozen=True)
class ArchiveBlob:
    owner_public_id: str
    blob: IconBlob
    profile: str

    def __post_init__(self) -> None:
        _uuid(self.owner_public_id)
        object.__setattr__(self, "blob", _blob(self.blob))
        if IMAGE_PROFILES[self.blob.media_type] != self.profile:
            raise BackupZipError("ZIP_ASSET_PROFILE")

    @property
    def name(self) -> str:
        return f"accounts/{self.owner_public_id}/blobs/{self.blob.sha256}"


def _index(
    expected: Iterable[ArchiveBlob], limits: ZipLimits
) -> dict[str, ArchiveBlob]:
    entries: dict[str, ArchiveBlob] = {}
    for count, item in enumerate(expected, 1):
        # Bound descriptor work as well as unique ZIP entries (even an endless
        # sequence of duplicates cannot evade the inventory limit).
        if count > limits.entries:
            raise BackupZipError("ZIP_LIMIT")
        entry = ArchiveBlob(item.owner_public_id, item.blob, item.profile)
        previous = entries.get(entry.name)
        if previous is not None and previous != entry:
            raise BackupZipError("ZIP_ASSET_DESCRIPTION")
        entries[entry.name] = entry
    return entries


class AssetArchive:
    def __init__(
        self,
        source: BinaryIO,
        expected: Iterable[ArchiveBlob],
        limits: ZipLimits = ZipLimits(),
        *,
        additional_names: frozenset[str] = frozenset(),
    ) -> None:
        self._entries = _index(expected, limits)
        self._zip = BackupZip(source, limits)
        if additional_names.intersection(self._entries) or self._zip.names != (
            self._entries.keys() | additional_names
        ):
            raise BackupZipError("ZIP_ASSET_ENTRIES")

    def read(self, name: str) -> bytes:
        expected = self._entries.get(name)
        if expected is None:
            raise BackupZipError("ZIP_ENTRY_MISSING")
        data = self._zip.read(name, limit=expected.blob.byte_length)
        with BytesIO(data) as source:
            read_icon_bytes(source, expected.blob)
        _inspect(data, expected.blob, expected.profile)
        return data

    def verify(self) -> None:
        """Check every required entry, retaining at most one image at a time.

        Verification is not a ticket to reopen the path or trust a later read.
        A restore must use read() again and install those returned frozen bytes.
        """
        for name in self._entries:
            self.read(name)


def write_asset_archive(
    output: BinaryIO,
    files: AssetFiles,
    expected: Iterable[ArchiveBlob],
    limits: ZipLimits = ZipLimits(),
) -> None:
    """Write and read back an empty, seekable/readable private staging stream.

    Caller owns stream lifetime, durable fsync/publication, incomplete output
    cleanup and the database snapshot. Never pass the completed backup path.
    Failure does not roll back already-written private staging bytes.
    """
    entries = _index(expected, limits)
    if output.tell() != 0 or output.seek(0, os.SEEK_END) != 0:
        raise BackupZipError("ZIP_OUTPUT_NOT_EMPTY")
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as writer:
        for entry in entries.values():
            data = files.read(entry.owner_public_id, entry.blob, entry.profile)
            writer.writestr(entry.name, data)
            if output.tell() > limits.archive_bytes:
                raise BackupZipError("ZIP_LIMIT")
    output.flush()
    AssetArchive(output, entries.values(), limits).verify()
