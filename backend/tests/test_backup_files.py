import hashlib
from io import BytesIO
import os

import pytest

from src.storage import backup_files as files
from src.storage.errors import StorageValidationError


def test_copy_handles_short_reads_and_writes_without_closing_callers_streams():
    class ShortInput(BytesIO):
        def read(self, n=-1):
            assert 0 < n <= 65536
            return super().read(min(n, 3))

    class ShortOutput(BytesIO):
        def write(self, data):
            return super().write(data[:2])

    data = b"backup byte identity"
    with ShortInput(data) as source, ShortOutput() as output:
        files.verify_stream(
            source, len(data), hashlib.sha256(data).hexdigest(), output=output
        )
        assert output.getvalue() == data and not source.closed and not output.closed


@pytest.mark.parametrize(
    "size,digest,data",
    [
        (3, "a" * 64, b"abc"),
        (4, hashlib.sha256(b"abc").hexdigest(), b"abc"),
        (2, hashlib.sha256(b"ab").hexdigest(), b"abc"),
    ],
)
def test_corrupt_short_and_oversize_data_rejected(size, digest, data):
    with (
        BytesIO(data) as source,
        pytest.raises(StorageValidationError, match="checksum mismatch"),
    ):
        files.verify_stream(source, size, digest)


@pytest.mark.parametrize("value", [None, True, -1, 2**50, 1.5, "3"])
def test_size_validation(value):
    with pytest.raises(StorageValidationError, match="size"):
        files.checked_size(value)


@pytest.mark.parametrize("value", [None, True, "A" * 64, "a" * 63, "a" * 64 + "\n"])
def test_digest_validation(value):
    with pytest.raises(StorageValidationError, match="checksum"):
        files.checked_digest(value)


@pytest.mark.parametrize("result", [None, 0, -1, 100, True])
def test_nonprogress_and_impossible_write_counts_fail(result):
    class Broken(BytesIO):
        def write(self, _data):
            return result

    with (
        BytesIO(b"data") as source,
        Broken() as output,
        pytest.raises(OSError, match="no valid progress"),
    ):
        files.verify_stream(
            source, 4, hashlib.sha256(b"data").hexdigest(), output=output
        )


@pytest.mark.parametrize("result", [None, b"too many bytes"])
def test_invalid_reads_are_not_eof(result):
    class Broken(BytesIO):
        def read(self, _size=-1):
            return result

    with (
        Broken() as source,
        pytest.raises(StorageValidationError, match="invalid backup read"),
    ):
        files.verify_stream(source, 4, hashlib.sha256(b"data").hexdigest())


@pytest.mark.parametrize(
    "payload",
    [
        b"[]",
        b"null",
        b'{"x":1,"x":2}',
        b'{"x":{"a":1,"a":2}}',
        b'{"x":NaN}',
        b'{"x":Infinity}',
        b"{",
        b"[" * 2000 + b"]" * 2000,
        b"x" * 65537,
    ],
)
def test_manifest_rejects_nonobjects_duplicate_keys_constants_recursion_and_size(
    tmp_path, payload
):
    path = tmp_path / "manifest.json"
    path.write_bytes(payload)
    with pytest.raises(StorageValidationError):
        files.read_manifest(path)


def test_manifest_round_trip_and_no_clobber_publication(tmp_path):
    staging = tmp_path / "staged.json"
    final = tmp_path / "manifest.json"
    files.write_manifest(staging, {"format_version": 2, "label": "合成"})
    assert files.read_manifest(staging) == {"format_version": 2, "label": "合成"}
    files.publish_new(staging, final)
    size, digest = files.file_fingerprint(final)
    assert (size, digest) == (
        len(final.read_bytes()),
        hashlib.sha256(final.read_bytes()).hexdigest(),
    )
    assert final.stat().st_mode & 0o777 == 0o600
    with pytest.raises(FileExistsError):
        files.publish_new(staging, final)
    with pytest.raises(FileExistsError):
        files.write_manifest(final, {"wrong": True})
    assert files.read_manifest(final) == {"format_version": 2, "label": "合成"}


@pytest.mark.parametrize("kind", ["directory", "fifo", "symlink"])
def test_nonregular_backup_components_are_rejected_without_reading(tmp_path, kind):
    path = tmp_path / "input"
    outside = tmp_path / "outside"
    outside.write_bytes(b"keep")
    if kind == "directory":
        path.mkdir()
    elif kind == "fifo":
        os.mkfifo(path)
    else:
        path.symlink_to(outside)
    with pytest.raises((OSError, StorageValidationError)):
        with files.regular_file(path):
            pytest.fail("nonregular source admitted")
    assert outside.read_bytes() == b"keep"


def test_file_fsync_failure_prevents_new_publication(tmp_path, monkeypatch):
    staging = tmp_path / "staged"
    staging.write_bytes(b"keep")

    def fail(_descriptor):
        raise OSError("synthetic fsync")

    monkeypatch.setattr(files.os, "fsync", fail)
    with pytest.raises(OSError, match="synthetic fsync"):
        files.publish_new(staging, tmp_path / "final")
    assert not (tmp_path / "final").exists() and staging.read_bytes() == b"keep"


def test_large_manifest_writer_refuses_before_creating_output(tmp_path):
    with pytest.raises(StorageValidationError, match="byte budget"):
        files.write_manifest(tmp_path / "output", {"large": "x" * 65536})
    assert not list(tmp_path.iterdir())
