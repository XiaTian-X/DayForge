from contextlib import contextmanager
from io import BytesIO
import os
import subprocess
import sys

import pytest

from src.storage.asset_files import AssetFileError, AssetFiles
from src.storage.asset_root import (
    AssetRootBusy,
    AssetRootLease,
    LOCK_NAME,
    ScanLimits,
    scan_installations,
)
from tests.asset_file_fixtures import OWNER, OTHER, SVG, blob, directory


def publish(files, owner=OWNER):
    with BytesIO(SVG) as source:
        return files.publish(owner, source, blob())


def test_root_lease_excludes_other_handles_and_processes_without_unlink(tmp_path):
    first, second = AssetRootLease(tmp_path), AssetRootLease(tmp_path)
    probe = """
import sys
from pathlib import Path
from src.storage.asset_root import AssetRootBusy, AssetRootLease
lease = AssetRootLease(Path(sys.argv[1]))
try:
    lease.acquire()
except AssetRootBusy:
    print('busy')
else:
    lease.release()
    print('acquired')
"""

    def child():
        return subprocess.run(
            [sys.executable, "-c", probe, str(tmp_path)],
            check=True,
            capture_output=True,
            text=True,
            timeout=10,
        ).stdout.strip()

    try:
        first.acquire()
        inode = (tmp_path / LOCK_NAME).stat().st_ino
        first.acquire()
        with pytest.raises(AssetRootBusy):
            second.acquire()
        assert child() == "busy"
        first.release()
        first.release()
        second.acquire()
        assert child() == "busy"
        second.release()
        assert child() == "acquired"
        assert (tmp_path / LOCK_NAME).stat().st_ino == inode
        assert (tmp_path / LOCK_NAME).stat().st_mode & 0o777 == 0o600
    finally:
        first.release()
        second.release()


@pytest.mark.parametrize("damage", ["link", "hardlink", "directory", "fifo", "content"])
def test_invalid_lock_is_preserved_and_never_followed(tmp_path, damage):
    root = tmp_path / "root"
    root.mkdir()
    target = tmp_path / "private"
    target.write_bytes(b"keep")
    path = root / LOCK_NAME
    if damage == "link":
        path.symlink_to(target)
    elif damage == "hardlink":
        os.link(target, path)
    elif damage == "directory":
        path.mkdir()
    elif damage == "fifo":
        os.mkfifo(path)
    else:
        path.write_bytes(b"unknown content")
    lease = AssetRootLease(root)
    with pytest.raises((AssetFileError, OSError)):
        lease.acquire()
    lease.release()
    assert target.read_bytes() == b"keep"
    assert path.exists()


def test_failed_acquire_closes_descriptor_and_can_retry(tmp_path, monkeypatch):
    lease = AssetRootLease(tmp_path)
    sync = os.fsync

    def fail(_descriptor):
        raise OSError("synthetic lock sync failure")

    with monkeypatch.context() as patch:
        patch.setattr(os, "fsync", fail)
        with pytest.raises(OSError, match="synthetic lock"):
            lease.acquire()
    assert os.fsync == sync
    other = AssetRootLease(tmp_path)
    try:
        other.acquire()
        with pytest.raises(AssetRootBusy):
            lease.acquire()
    finally:
        other.release()
    lease.acquire()
    lease.release()


def test_inventory_covers_accounts_and_matching_temporary_without_deleting(tmp_path):
    files = AssetFiles(tmp_path)
    assert scan_installations(files) == ()
    assert list(tmp_path.iterdir()) == []
    receipts = [publish(files), publish(files, OTHER)]
    for receipt in receipts:
        root = directory(tmp_path, receipt.owner_public_id)
        (root / (receipt.stem + ".part")).write_bytes(b"partial")
        (root / (receipt.stem + ".json.tmp")).write_bytes(receipt.encode())
    before = {path: path.read_bytes() for path in tmp_path.rglob("*") if path.is_file()}
    assert set(scan_installations(files)) == set(receipts)
    assert {path: path.read_bytes() for path in before} == before


@pytest.mark.parametrize("level", ["root", "accounts", "owner", "blobs"])
def test_unknown_inventory_path_blocks_writes_but_is_not_deleted(tmp_path, level):
    files = AssetFiles(tmp_path)
    publish(files)
    parent = {
        "root": tmp_path,
        "accounts": tmp_path / "accounts",
        "owner": tmp_path / "accounts" / OWNER,
        "blobs": directory(tmp_path),
    }[level]
    stray = parent / "do-not-delete"
    stray.write_bytes(b"private")
    with pytest.raises(AssetFileError):
        scan_installations(files)
    assert stray.read_bytes() == b"private"


@pytest.mark.parametrize("suffix", [".json.tmp", ".part"])
def test_unproven_temporary_is_not_assumed_to_be_disposable(tmp_path, suffix):
    files = AssetFiles(tmp_path)
    receipt = publish(files)
    files.finish(receipt)
    path = directory(tmp_path) / (receipt.stem + suffix)
    path.write_bytes(receipt.encode() if suffix == ".json.tmp" else b"partial")
    with pytest.raises(AssetFileError, match="UNPROVEN_TEMPORARY"):
        scan_installations(files)
    assert path.exists()
    assert files.read(OWNER, blob(), "svg-v1") == SVG


@pytest.mark.parametrize("damage", ["journal", "blob-link", "account-link", "fifo"])
def test_invalid_inventory_is_preserved_and_handles_close(
    tmp_path, monkeypatch, damage
):
    files = AssetFiles(tmp_path)
    receipt = publish(files)
    root = directory(tmp_path)
    if damage == "journal":
        (root / (receipt.stem + ".json")).write_bytes(b"not-json")
    elif damage == "blob-link":
        (root / blob().sha256).unlink()
        (root / blob().sha256).symlink_to("/dev/null")
    elif damage == "account-link":
        (tmp_path / "accounts" / OTHER).symlink_to(tmp_path / "accounts" / OWNER)
    else:
        os.mkfifo(root / ("f" * 64))
    opened: set[int] = set()
    scans = 0
    original_open, original_close, original_scan = os.open, os.close, os.scandir

    def tracked_open(*args, **kwargs):
        descriptor = original_open(*args, **kwargs)
        opened.add(descriptor)
        return descriptor

    def tracked_close(descriptor):
        original_close(descriptor)
        opened.remove(descriptor)

    @contextmanager
    def tracked_scan(*args, **kwargs):
        nonlocal scans
        with original_scan(*args, **kwargs) as entries:
            scans += 1
            try:
                yield entries
            finally:
                scans -= 1

    with monkeypatch.context() as patch:
        patch.setattr(os, "open", tracked_open)
        patch.setattr(os, "close", tracked_close)
        patch.setattr(os, "scandir", tracked_scan)
        for _ in range(20):
            with pytest.raises((AssetFileError, OSError)):
                scan_installations(files)
            assert opened == set()
            assert scans == 0
    assert (root / (receipt.stem + ".json")).exists()


def test_inventory_limits_include_final_blobs_and_never_return_partial_success(
    tmp_path,
):
    files = AssetFiles(tmp_path)
    one = publish(files)
    # accounts, owner, blobs, final hash, committed intent.
    assert scan_installations(files, ScanLimits(5, 1)) == (one,)
    with pytest.raises(AssetFileError, match="SCAN_LIMIT"):
        scan_installations(files, ScanLimits(4, 1))
    two = publish(files)
    with pytest.raises(AssetFileError, match="SCAN_LIMIT"):
        scan_installations(files, ScanLimits(6, 1))
    assert set(scan_installations(files, ScanLimits(6, 2))) == {one, two}
    assert (directory(tmp_path) / blob().sha256).read_bytes() == SVG


@pytest.mark.parametrize("value", [0, -1, True, 1.5, 1_000_001])
def test_scan_limits_are_bounded_not_coerced(value):
    with pytest.raises(ValueError):
        ScanLimits(max_entries=value)
    with pytest.raises(ValueError):
        ScanLimits(max_intents=value)


def test_missing_or_symlink_root_never_creates_or_locks_target(tmp_path):
    missing = tmp_path / "missing"
    with pytest.raises(FileNotFoundError):
        AssetRootLease(missing).acquire()
    assert not missing.exists()
    target = tmp_path / "target"
    target.mkdir()
    link = tmp_path / "link"
    link.symlink_to(target)
    with pytest.raises(OSError):
        AssetRootLease(link).acquire()
    with pytest.raises(OSError):
        scan_installations(AssetFiles(link))
    assert list(target.iterdir()) == []
