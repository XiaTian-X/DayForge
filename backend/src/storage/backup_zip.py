"""Bounded reader for new, seekable DayForge backup ZIPs, not a general unzipper.

Only stored/deflated regular entries and the ZIP64 size/offset extension are
supported. No extraction, encryption, descriptors, comments or multipart ZIPs.
The caller owns the blocking seekable stream and must not use it concurrently.
Metadata is frozen once; each read revalidates actual compressed bytes, not a
ZipExtFile's declared-length EOF. Callers must additionally verify their exact
entry set, cryptographic hashes and image profiles before publishing anything.
"""

from dataclasses import dataclass
import os
import re
import stat
import struct
from typing import BinaryIO
import zlib


U32 = 0xFFFFFFFF
BLOCK = 65_536
MAX_READ = 16 * 1024 * 1024
NAME = re.compile(rb"[A-Za-z0-9_-][A-Za-z0-9_.-]*(?:/[A-Za-z0-9_-][A-Za-z0-9_.-]*)*")
END = struct.Struct("<4s4H2LH")
END64 = struct.Struct("<4sQ2H2L4Q")
LOCATOR = struct.Struct("<4sLQL")
CENTRAL = struct.Struct("<4s6H3L5H2L")
LOCAL = struct.Struct("<4s5H3L2H")


class BackupZipError(ValueError):
    """Unsupported, inconsistent or over-budget backup archive."""


@dataclass(frozen=True)
class ZipLimits:
    entries: int = 100_000
    metadata_bytes: int = 32 * 1024 * 1024
    archive_bytes: int = 1024**4

    def __post_init__(self) -> None:
        for value, maximum in (
            (self.entries, 1_000_000),
            (self.metadata_bytes, 64 * 1024 * 1024),
            (self.archive_bytes, 1024**4),
        ):
            if type(value) is not int or not 1 <= value <= maximum:
                raise BackupZipError("ZIP_LIMIT")


def _require(condition: bool) -> None:
    if not condition:
        raise BackupZipError("ZIP_STRUCTURE")


def _read(source: BinaryIO, length: int) -> bytes:
    result = bytearray()
    while len(result) < length:
        requested = min(BLOCK, length - len(result))
        block = source.read(requested)
        if not isinstance(block, bytes) or not 0 < len(block) <= requested:
            raise BackupZipError("ZIP_TRUNCATED")
        result.extend(block)
    return bytes(result)


def _directory(source: BinaryIO, limits: ZipLimits) -> tuple[int, int, int]:
    size = source.seek(0, os.SEEK_END)
    if not END.size <= size <= limits.archive_bytes:
        raise BackupZipError("ZIP_LIMIT")
    end_offset = size - END.size
    source.seek(end_offset)
    sig, disk, cd_disk, count_disk, count, cd_size, offset, comment = END.unpack(
        _read(source, END.size)
    )
    _require(sig == b"PK\x05\x06" and disk == cd_disk == comment == 0)
    _require(count_disk == count)
    # ZIP64 can be used without saturated 32-bit values; accept both forms but
    # demand that any non-sentinel legacy values agree with the ZIP64 record.
    locator = b""
    if end_offset >= LOCATOR.size:
        source.seek(end_offset - LOCATOR.size)
        locator = _read(source, LOCATOR.size)
    if locator[:4] == b"PK\x06\x07":
        _, disk64, location, disks = LOCATOR.unpack(locator)
        _require(disk64 == 0 and disks == 1)
        _require(location + END64.size == end_offset - LOCATOR.size)
        source.seek(location)
        values = END64.unpack(_read(source, END64.size))
        sig64, length, made, version, disk64, cd_disk64, n_disk, n, length_cd, start = (
            values
        )
        _require(sig64 == b"PK\x06\x06" and length == 44)
        _require(version == 45 and made & 255 <= 45 and disk64 == cd_disk64 == 0)
        _require(n_disk == n)
        _require(count in (65535, n) and cd_size in (U32, length_cd))
        _require(offset in (U32, start))
        count, cd_size, offset, end_offset = n, length_cd, start, location
    else:
        # Exactly 65535 entries still fits classic ZIP (CPython switches only
        # above that count). The bounded actual record count is checked below.
        _require(cd_size != U32 and offset != U32)
    if count > limits.entries or cd_size > limits.metadata_bytes:
        raise BackupZipError("ZIP_LIMIT")
    _require(offset + cd_size == end_offset and count * CENTRAL.size <= cd_size)
    return offset, cd_size, count


def _sizes(extra: bytes, *values: int) -> tuple[int, ...]:
    needed = values.count(U32)
    if not needed:
        _require(not extra)
        return values
    # Only the required ZIP64 values, in size/compressed-size/offset order.
    _require(len(extra) == 4 + needed * 8)
    _require(struct.unpack_from("<HH", extra) == (1, needed * 8))
    extended = iter(struct.unpack_from("<" + "Q" * needed, extra, 4))
    return tuple(next(extended) if value == U32 else value for value in values)


@dataclass(frozen=True)
class _Entry:
    name: bytes
    method: int
    crc: int
    size: int
    compressed: int
    offset: int
    data_offset: int


def _entry(source: BinaryIO, record: bytes, cd_offset: int) -> _Entry:
    values = CENTRAL.unpack_from(record)
    (
        sig,
        made,
        version,
        flags,
        method,
        _time,
        _date,
        crc,
        compressed,
        size,
        name_size,
        extra_size,
        comment,
        disk,
        internal,
        external,
        offset,
    ) = values
    _require(sig == b"PK\x01\x02" and version in (10, 20, 45))
    _require(flags in (0, 0x800) and method in (0, 8))
    _require(comment == disk == internal == 0 and made >> 8 in (0, 3))
    _require(stat.S_IFMT(external >> 16) in (0, stat.S_IFREG))
    _require(external & 0xFFFF in (0, 0x20))
    name = record[CENTRAL.size : CENTRAL.size + name_size]
    _require(0 < len(name) <= 240 and NAME.fullmatch(name) is not None)
    offset_only_zip64 = offset == U32 and size != U32 and compressed != U32
    _require(not extra_size or version == 45)
    size, compressed, offset = _sizes(
        record[CENTRAL.size + name_size :], size, compressed, offset
    )
    _require(offset + LOCAL.size <= cd_offset)
    source.seek(offset)
    local = LOCAL.unpack(_read(source, LOCAL.size))
    (
        l_sig,
        l_version,
        l_flags,
        l_method,
        _t,
        _d,
        l_crc,
        l_comp,
        l_size,
        l_name,
        l_extra,
    ) = local
    _require(
        (l_sig, l_flags, l_method, l_crc, l_name)
        == (b"PK\x03\x04", flags, method, crc, name_size)
    )
    # A small entry beyond 4 GiB needs ZIP64 only in its central offset;
    # Python's seekable writer correctly keeps its local version at 2.0.
    _require(l_version == version or (offset_only_zip64 and l_version == 20))
    _require(not l_extra or l_version == 45)
    data_offset = offset + LOCAL.size + l_name + l_extra
    _require(data_offset + compressed <= cd_offset)
    _require(_read(source, l_name) == name)
    l_size, l_comp = _sizes(_read(source, l_extra), l_size, l_comp)
    _require((l_size, l_comp) == (size, compressed))
    _require(method != 0 or size == compressed)
    return _Entry(name, method, crc, size, compressed, offset, data_offset)


class BackupZip:
    def __init__(self, source: BinaryIO, limits: ZipLimits = ZipLimits()) -> None:
        self._source = source
        offset, length, count = _directory(source, limits)
        source.seek(offset)
        # Bounded immutable metadata; never feed a mutable second read to an
        # eager standard-library parser. Count actual records, not just EOCD.
        directory = _read(source, length)
        entries: dict[str, _Entry] = {}
        position = 0
        while position < length:
            if len(entries) >= limits.entries:
                raise BackupZipError("ZIP_LIMIT")
            _require(position + CENTRAL.size <= length)
            values = CENTRAL.unpack_from(directory, position)
            record_size = CENTRAL.size + sum(values[10:13])
            _require(position + record_size <= length)
            entry = _entry(source, directory[position : position + record_size], offset)
            name = entry.name.decode("ascii")
            _require(name not in entries)
            entries[name] = entry
            position += record_size
        _require(len(entries) == count)
        cursor = 0
        for entry in sorted(entries.values(), key=lambda value: value.offset):
            # Forbid overlaps, prefixes, hidden records and unaccounted tails.
            _require(entry.offset == cursor)
            cursor = entry.data_offset + entry.compressed
        _require(cursor == offset)
        self._entries = entries

    @property
    def names(self) -> frozenset[str]:
        return frozenset(self._entries)

    def read(self, name: str, *, limit: int) -> bytes:
        if type(limit) is not int or not 0 <= limit <= MAX_READ:
            raise BackupZipError("ZIP_LIMIT")
        entry = self._entries.get(name)
        if entry is None:
            raise BackupZipError("ZIP_ENTRY_MISSING")
        # Bound compressed work too; pathological deflate empty-block streams
        # must not spend archive-sized work on a tiny declared image.
        if entry.size > limit or entry.compressed > limit + BLOCK:
            raise BackupZipError("ZIP_LIMIT")
        self._source.seek(entry.data_offset)
        remaining = entry.compressed
        output = bytearray()
        decoder = zlib.decompressobj(-15) if entry.method == 8 else None
        try:
            while remaining:
                block = _read(self._source, min(BLOCK, remaining))
                remaining -= len(block)
                decoded = (
                    decoder.decompress(block, entry.size + 1 - len(output))
                    if decoder is not None
                    else block
                )
                output.extend(decoded)
                if len(output) > entry.size:
                    raise BackupZipError("ZIP_CONTENT")
                if decoder is not None:
                    _require(not decoder.unused_data and not decoder.unconsumed_tail)
                    _require(not decoder.eof or remaining == 0)
        except zlib.error as error:
            raise BackupZipError("ZIP_CONTENT") from error
        if (
            (decoder is not None and not decoder.eof)
            or len(output) != entry.size
            or zlib.crc32(output) != entry.crc
        ):
            raise BackupZipError("ZIP_CONTENT")
        return bytes(output)
