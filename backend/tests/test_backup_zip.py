from io import BytesIO
import struct
import random
import zipfile
import zlib

import pytest

from src.storage.backup_zip import BackupZip, BackupZipError, ZipLimits


def archive(entries=(("one.json", b"hello"),), *, method=zipfile.ZIP_DEFLATED):
    output = BytesIO()
    with zipfile.ZipFile(output, "w", compression=method) as writer:
        for name, data in entries:
            writer.writestr(name, data)
    return output.getvalue()


def change(data, position, fmt, value):
    result = bytearray(data)
    struct.pack_into(fmt, result, position, value)
    return bytes(result)


def raw_archive(payload, *, size, crc, method=8):
    """Independent ZIP fixture permits lies rejected by the production reader."""
    name = b"one.json"
    local = (
        struct.pack(
            "<4s5H3L2H",
            b"PK\x03\x04",
            20,
            0,
            method,
            0,
            0,
            crc,
            len(payload),
            size,
            len(name),
            0,
        )
        + name
        + payload
    )
    central = (
        struct.pack(
            "<4s6H3L5H2L",
            b"PK\x01\x02",
            20,
            20,
            0,
            method,
            0,
            0,
            crc,
            len(payload),
            size,
            len(name),
            0,
            0,
            0,
            0,
            0,
            0,
        )
        + name
    )
    return (
        local
        + central
        + struct.pack(
            "<4s4H2LH", b"PK\x05\x06", 0, 0, 1, 1, len(central), len(local), 0
        )
    )


def deflate(data):
    codec = zlib.compressobj(wbits=-15)
    return codec.compress(data) + codec.flush()


@pytest.mark.parametrize("method", [zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED])
def test_standard_writer_round_trip_empty_and_many_block_entries(method):
    entries = [
        ("one.json", b"hello"),
        ("empty", b""),
        ("large", bytes(range(256)) * 700),
    ]
    with BytesIO(archive(entries, method=method)) as source:
        reader = BackupZip(source)
        assert reader.names == {"one.json", "empty", "large"}
        for name, data in reversed(entries):
            assert reader.read(name, limit=len(data)) == data
            assert reader.read(name, limit=len(data)) == data
        assert not source.closed
    with BytesIO(archive(())) as source:
        assert BackupZip(source).names == set()


def test_short_reads_and_bounded_read_requests():
    class Short(BytesIO):
        def read(self, n=-1):
            assert 0 < n <= 65536
            return super().read(min(7, n))

    with Short(archive()) as source:
        assert BackupZip(source).read("one.json", limit=5) == b"hello"


@pytest.mark.parametrize(
    "field,value",
    [
        ("entries", 0),
        ("entries", True),
        ("entries", 1_000_001),
        ("metadata_bytes", 0),
        ("metadata_bytes", 64 * 1024 * 1024 + 1),
        ("archive_bytes", -1),
        ("archive_bytes", 1024**4 + 1),
    ],
)
def test_invalid_limits(field, value):
    with pytest.raises(BackupZipError, match="ZIP_LIMIT"):
        ZipLimits(**{field: value})


def test_metadata_and_archive_limits_before_directory_read():
    data = archive([("one", b"1"), ("two", b"2")])
    offset = data.index(b"PK\x01\x02")

    class Guard(BytesIO):
        def read(self, n=-1):
            assert not (self.tell() == offset and n > 20), "directory consumed"
            return super().read(n)

    for limits in (
        ZipLimits(entries=1),
        ZipLimits(metadata_bytes=1),
        ZipLimits(archive_bytes=len(data) - 1),
    ):
        with Guard(data) as source, pytest.raises(BackupZipError, match="ZIP_LIMIT"):
            BackupZip(source, limits)
    with BytesIO(data) as exact_source:
        assert (
            len(
                BackupZip(
                    exact_source,
                    ZipLimits(
                        entries=2,
                        metadata_bytes=len(data) - offset - 22,
                        archive_bytes=len(data),
                    ),
                ).names
            )
            == 2
        )


def test_actual_count_not_just_declared_count_is_bounded():
    data = archive([("one", b"1"), ("two", b"2")])
    data = change(change(data, len(data) - 14, "<H", 1), len(data) - 12, "<H", 1)
    with BytesIO(data) as source, pytest.raises(BackupZipError, match="ZIP_LIMIT"):
        BackupZip(source, ZipLimits(entries=1))
    with BytesIO(data) as source, pytest.raises(BackupZipError, match="ZIP_STRUCTURE"):
        BackupZip(source)


@pytest.mark.parametrize(
    "offset,fmt,value",
    [
        (0, "<L", 0),
        (4, "<H", 1),
        (6, "<H", 1),
        (8, "<H", 2),
        (10, "<H", 65535),
        (12, "<L", 0xFFFFFFFF),
        (16, "<L", 0),
        (20, "<H", 1),
    ],
)
def test_bad_end_record(offset, fmt, value):
    data = archive()
    data = change(data, len(data) - 22 + offset, fmt, value)
    with BytesIO(data) as source, pytest.raises(BackupZipError):
        BackupZip(source)


@pytest.mark.parametrize(
    "offset,fmt,value",
    [
        (0, "<L", 0),
        (4, "<H", 0x1414),
        (6, "<H", 99),
        (8, "<H", 1),
        (8, "<H", 8),
        (10, "<H", 12),
        (16, "<L", 123),
        (28, "<H", 65535),
        (30, "<H", 1),
        (32, "<H", 1),
        (34, "<H", 1),
        (36, "<H", 1),
        (38, "<L", 0o120777 << 16),
        (38, "<L", 0x10),
        (42, "<L", 1),
    ],
)
def test_bad_central_record(offset, fmt, value):
    data = archive()
    data = change(data, data.index(b"PK\x01\x02") + offset, fmt, value)
    with BytesIO(data) as source, pytest.raises(BackupZipError):
        BackupZip(source)


@pytest.mark.parametrize(
    "offset,fmt,value",
    [
        (0, "<L", 0),
        (4, "<H", 10),
        (6, "<H", 1),
        (8, "<H", 0),
        (14, "<L", 123),
        (18, "<L", 1),
        (22, "<L", 1),
        (26, "<H", 1),
        (28, "<H", 65535),
        (30, "<B", 120),
    ],
)
def test_local_header_must_match_central(offset, fmt, value):
    data = change(archive(), offset, fmt, value)
    with BytesIO(data) as source, pytest.raises(BackupZipError):
        BackupZip(source)


@pytest.mark.parametrize(
    "name",
    [
        "/absolute",
        "../relative",
        "a/../b",
        "a//b",
        "a/",
        "a\\b",
        "é",
        ".hidden",
        "x" * 241,
    ],
)
def test_unsafe_or_noncanonical_names(name):
    with BytesIO(archive([(name, b"x")])) as source, pytest.raises(BackupZipError):
        BackupZip(source)


def test_duplicate_and_nul_names_without_writer_normalization():
    data = archive([("one.json", b"one"), ("two.json", b"two")])
    for altered in (
        data.replace(b"two.json", b"one.json"),
        data.replace(b"two.json", b"two\x00json"),
    ):
        with BytesIO(altered) as source, pytest.raises(BackupZipError):
            BackupZip(source)


@pytest.mark.parametrize(
    "damage", ["prefix", "suffix", "overlap", "gap", "empty_prefix"]
)
def test_hidden_bytes_and_overlapping_records(damage):
    data = archive([("one", b"1"), ("two", b"2")])
    cd = data.index(b"PK\x01\x02")
    if damage == "prefix":
        data = b"hidden" + data
    elif damage == "suffix":
        data += b"hidden"
    elif damage == "empty_prefix":
        data = b"hidden" + archive(())
        data = change(data, len(data) - 6, "<L", 6)
    elif damage == "overlap":
        data = change(data, data.index(b"PK\x01\x02", cd + 4) + 42, "<L", 0)
    else:
        data = data[:cd] + b"hidden" + data[cd:]
        data = change(data, len(data) - 6, "<L", cd + 6)
    with BytesIO(data) as source, pytest.raises(BackupZipError):
        BackupZip(source)


def test_all_truncated_archive_prefixes_rejected():
    data = archive()
    for end in range(len(data)):
        with BytesIO(data[:end]) as source, pytest.raises(BackupZipError):
            BackupZip(source)


@pytest.mark.parametrize(
    "payload,size,crc",
    [
        (deflate(b"hello hidden"), 5, zlib.crc32(b"hello")),
        (deflate(b"hello") + b"tail", 5, zlib.crc32(b"hello")),
        (deflate(b"hello") + deflate(b"tail"), 5, zlib.crc32(b"hello")),
        (deflate(b"hello")[:-1], 5, zlib.crc32(b"hello")),
        (deflate(b"hell"), 5, zlib.crc32(b"hell")),
        (deflate(b"hello"), 5, 123),
        (b"\xff", 5, 123),
        (b"", 0, 0),
        (deflate(b"x" * 1_000_000), 5, zlib.crc32(b"xxxxx")),
    ],
)
def test_actual_deflate_eof_size_crc_and_bomb_are_checked(payload, size, crc):
    with BytesIO(raw_archive(payload, size=size, crc=crc)) as source:
        reader = BackupZip(source)
        with pytest.raises(BackupZipError):
            reader.read("one.json", limit=5)


def test_declared_eof_is_not_a_real_deflate_eof():
    data = raw_archive(deflate(b"hello hidden"), size=5, crc=zlib.crc32(b"hello"))
    with BytesIO(data) as source, zipfile.ZipFile(source) as stdlib:
        assert stdlib.read("one.json") == b"hello"  # Demonstrate the actual boundary.
    with BytesIO(data) as source, pytest.raises(BackupZipError):
        BackupZip(source).read("one.json", limit=5)


def test_entry_read_limits_and_missing_entry():
    with BytesIO(archive()) as source:
        reader = BackupZip(source)
        for limit in (-1, True, 4, 16 * 1024 * 1024 + 1):
            with pytest.raises(BackupZipError, match="ZIP_LIMIT"):
                reader.read("one.json", limit=limit)
        with pytest.raises(BackupZipError, match="ZIP_ENTRY_MISSING"):
            reader.read("missing", limit=5)
    with BytesIO(raw_archive(b"x" * 65542, size=5, crc=0)) as source:
        with pytest.raises(BackupZipError, match="ZIP_LIMIT"):
            BackupZip(source).read("one.json", limit=5)


def test_payload_mutation_is_rechecked_metadata_is_frozen():
    data = archive(method=zipfile.ZIP_STORED)
    with BytesIO(data) as source:
        reader = BackupZip(source)
        source.seek(data.index(b"PK\x01\x02"))
        source.write(b"junk")
        assert reader.read("one.json", limit=5) == b"hello"
        source.seek(30 + len("one.json"))
        source.write(b"other")
        with pytest.raises(BackupZipError, match="ZIP_CONTENT"):
            reader.read("one.json", limit=5)
        source.truncate(30)
        with pytest.raises(BackupZipError, match="ZIP_TRUNCATED"):
            reader.read("one.json", limit=5)


def test_real_zip64_writer_and_empty_entries(monkeypatch):
    monkeypatch.setattr(zipfile, "ZIP64_LIMIT", 4)
    entries = [("one.json", b"hello"), ("empty", b"")]
    with BytesIO(archive(entries)) as source:
        reader = BackupZip(source)
        for name, data in entries:
            assert reader.read(name, limit=len(data)) == data


def test_forced_zip64_local_header_without_zip64_directory():
    output = BytesIO()
    with zipfile.ZipFile(output, "w") as writer:
        with writer.open("one", "w", force_zip64=True) as entry:
            entry.write(b"hello")
    output.seek(0)
    assert BackupZip(output).read("one", limit=5) == b"hello"
    output.close()


@pytest.mark.parametrize(
    "record,offset,fmt,value",
    [
        (b"PK\x06\x07", 4, "<L", 1),
        (b"PK\x06\x07", 8, "<Q", 1),
        (b"PK\x06\x07", 16, "<L", 2),
        (b"PK\x06\x06", 4, "<Q", 45),
        (b"PK\x06\x06", 14, "<H", 99),
        (b"PK\x06\x06", 16, "<L", 1),
        (b"PK\x06\x06", 20, "<L", 1),
        (b"PK\x06\x06", 24, "<Q", 3),
        (b"PK\x06\x06", 32, "<Q", 3),
        (b"PK\x06\x06", 40, "<Q", 1),
        (b"PK\x06\x06", 48, "<Q", 1),
    ],
)
def test_invalid_zip64_footer(monkeypatch, record, offset, fmt, value):
    monkeypatch.setattr(zipfile, "ZIP64_LIMIT", 4)
    data = archive()
    data = change(data, data.index(record) + offset, fmt, value)
    with BytesIO(data) as source, pytest.raises(BackupZipError):
        BackupZip(source)


def test_zip64_actual_entry_count_exceeds_legacy_count(monkeypatch):
    monkeypatch.setattr(zipfile, "ZIP_FILECOUNT_LIMIT", 2)
    data = archive([("one", b"1"), ("two", b"2"), ("three", b"3")])
    # CPython's lowered test threshold leaves 2 in EOCD instead of the real
    # specification sentinel, so make this an independently valid ZIP64 footer.
    data = change(
        change(data, len(data) - 14, "<H", 65535), len(data) - 12, "<H", 65535
    )
    with BytesIO(data) as source:
        assert BackupZip(source).names == {"one", "two", "three"}


@pytest.mark.parametrize("count", [65535, 65536])
def test_real_classic_to_zip64_entry_count_boundary(count):
    data = archive(((f"e{i}", b"") for i in range(count)), method=zipfile.ZIP_STORED)
    assert (b"PK\x06\x06" in data) == (count > 65535)
    with BytesIO(data) as source:
        reader = BackupZip(source)
        assert len(reader.names) == count
        assert reader.read(f"e{count - 1}", limit=0) == b""


def test_incompressible_deflate_crosses_multiple_input_blocks():
    data = random.Random(37).randbytes(200000)
    encoded = archive([("one", data)])
    assert len(encoded) > 3 * 65536
    with BytesIO(encoded) as source:
        assert BackupZip(source).read("one", limit=len(data)) == data


@pytest.mark.parametrize("damage", ["type", "no_progress", "overread", "io"])
def test_bad_read_contract_and_io_failures_propagate(damage):
    class Broken(BytesIO):
        def read(self, n=-1):
            if damage == "type":
                return None
            if damage == "no_progress":
                return b""
            if damage == "overread":
                return b"x" * (n + 1)
            raise OSError("synthetic read failure")

    with Broken(archive()) as source:
        with pytest.raises(OSError if damage == "io" else BackupZipError):
            BackupZip(source)
        assert not source.closed


@pytest.mark.parametrize("location", ["local", "central"])
@pytest.mark.parametrize("damage", ["tag", "length", "value", "sentinel"])
def test_invalid_zip64_extra(monkeypatch, location, damage):
    monkeypatch.setattr(zipfile, "ZIP64_LIMIT", 4)
    data = archive()
    start = 30 + 8 if location == "local" else data.index(b"PK\x01\x02") + 46 + 8
    if damage == "tag":
        data = change(data, start, "<H", 2)
    elif damage == "length":
        data = change(data, start + 2, "<H", 8)
    elif damage == "value":
        data = change(data, start + 4, "<Q", 1)
    else:
        field = 22 if location == "local" else data.index(b"PK\x01\x02") + 24
        data = change(data, field, "<L", 5)
    with BytesIO(data) as source, pytest.raises(BackupZipError):
        BackupZip(source)


def test_offsets_beyond_32_bits_without_allocating_a_multigigabyte_fixture():
    # Sparse virtual source: the first entry occupies >4 GiB. Its bytes are not
    # read; the second is read through a true 64-bit local-header offset.
    huge = 0x100000000
    extra = struct.pack("<HHQQ", 1, 16, huge, huge)
    first = (
        struct.pack(
            "<4s5H3L2H",
            b"PK\x03\x04",
            45,
            0,
            0,
            0,
            0,
            0,
            0xFFFFFFFF,
            0xFFFFFFFF,
            3,
            len(extra),
        )
        + b"big"
        + extra
    )
    second_offset = len(first) + huge
    second = (
        struct.pack(
            "<4s5H3L2H", b"PK\x03\x04", 20, 0, 0, 0, 0, zlib.crc32(b"ok"), 2, 2, 5, 0
        )
        + b"smallok"
    )
    central_offset = second_offset + len(second)
    directory = (
        struct.pack(
            "<4s6H3L5H2L",
            b"PK\x01\x02",
            45,
            45,
            0,
            0,
            0,
            0,
            0,
            0xFFFFFFFF,
            0xFFFFFFFF,
            3,
            len(extra),
            0,
            0,
            0,
            0,
            0,
        )
        + b"big"
        + extra
    )
    offset_extra = struct.pack("<HHQ", 1, 8, second_offset)
    directory += (
        struct.pack(
            "<4s6H3L5H2L",
            b"PK\x01\x02",
            45,
            45,
            0,
            0,
            0,
            0,
            zlib.crc32(b"ok"),
            2,
            2,
            5,
            len(offset_extra),
            0,
            0,
            0,
            0,
            0xFFFFFFFF,
        )
        + b"small"
        + offset_extra
    )
    end64_offset = central_offset + len(directory)
    footer = struct.pack(
        "<4sQ2H2L4Q",
        b"PK\x06\x06",
        44,
        45,
        45,
        0,
        0,
        2,
        2,
        len(directory),
        central_offset,
    )
    footer += struct.pack("<4sLQL", b"PK\x06\x07", 0, end64_offset, 1)
    footer += struct.pack(
        "<4s4H2LH", b"PK\x05\x06", 0, 0, 2, 2, len(directory), 0xFFFFFFFF, 0
    )
    total = end64_offset + len(footer)
    regions = [
        (0, first),
        (second_offset, second),
        (central_offset, directory + footer),
    ]

    class Sparse(BytesIO):
        position = 0
        consumed = 0

        def seek(self, offset, whence=0):
            assert whence in (0, 2)
            self.position = offset + (total if whence == 2 else 0)
            return self.position

        def read(self, n=-1):
            assert 0 < n <= 65536
            result = bytearray(n)
            for start, content in regions:
                left, right = (
                    max(start, self.position),
                    min(start + len(content), self.position + n),
                )
                if left < right:
                    result[left - self.position : right - self.position] = content[
                        left - start : right - start
                    ]
            self.position += n
            self.consumed += n
            return bytes(result)

    with Sparse() as source:
        reader = BackupZip(source)
        assert reader.names == {"big", "small"}
        assert reader.read("small", limit=2) == b"ok"
        with pytest.raises(BackupZipError, match="ZIP_LIMIT"):
            reader.read("big", limit=16 * 1024 * 1024)
        assert source.consumed < 1024
