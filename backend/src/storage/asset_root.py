"""POSIX root lease and bounded, non-mutating install inventory.

Call off the event loop, with no active file jobs while inventory is collected.
The lease is advisory: every application/maintenance writer must participate.
It does not protect against a server administrator replacing managed paths.
"""

from dataclasses import dataclass
import errno
import fcntl
import os
from pathlib import Path
import re
import stat

from src.storage.asset_files import (
    AssetFileError,
    AssetFiles,
    DIGEST,
    InstallReceipt,
    JOURNAL_NAME,
    _child_directory,
    _root_directory,
    _uuid,
)


LOCK_NAME = ".asset-root.lock"
TEMP_NAME = re.compile(r"\.install-([0-9a-f-]{36})\.(part|json\.tmp)")


class AssetRootBusy(RuntimeError):
    """Another process owns this root; no waiting or lock-file deletion."""


class AssetRootLease:
    """One long-lived lock descriptor, released only after actual workers drain.

    acquire/release are serialized by the owning runtime, never concurrent jobs.
    Cancellation of an awaiting task does not release a worker-acquired lease.
    """

    def __init__(self, root: Path):
        self.root = root
        self._descriptor: int | None = None

    def acquire(self) -> None:
        if self._descriptor is not None:
            return
        with _root_directory(self.root) as root:
            descriptor = os.open(
                LOCK_NAME,
                os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_NONBLOCK,
                0o600,
                dir_fd=root,
            )
            try:
                info = os.fstat(descriptor)
                if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size:
                    raise AssetFileError("ASSET_STORAGE_LOCK_FILE")
                try:
                    fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except OSError as error:
                    if error.errno in {errno.EACCES, errno.EAGAIN}:
                        raise AssetRootBusy("asset root is already in use") from error
                    raise
                os.fsync(descriptor)
                os.fsync(root)
            except BaseException:
                os.close(descriptor)
                raise
            self._descriptor = descriptor

    def release(self) -> None:
        if self._descriptor is not None:
            # Never unlink the lock file: another waiter could own its inode.
            descriptor, self._descriptor = self._descriptor, None
            os.close(descriptor)


@dataclass(frozen=True)
class ScanLimits:
    max_entries: int = 100_000
    max_intents: int = 64

    def __post_init__(self) -> None:
        for value, maximum in (
            (self.max_entries, 1_000_000),
            (self.max_intents, 4096),
        ):
            if type(value) is not int or not 1 <= value <= maximum:
                raise ValueError("asset scan limit is not a bounded positive integer")


def scan_installations(
    files: AssetFiles, limits: ScanLimits = ScanLimits()
) -> tuple[InstallReceipt, ...]:
    """Collect a complete bounded list before performing any recovery deletion.

    Final blobs are counted/type-checked, not loaded. Every temporary must have
    a canonical committed intent. Preparation leftovers, unknown paths, corrupt
    logs and limits fail closed and preserve all files. This is not garbage
    collection or a substitute for checking ready bytes against the database.
    """
    visited = 0
    receipts: list[InstallReceipt] = []

    def visit(directory: int, level: int, owner: str | None = None) -> None:
        nonlocal visited
        temporary: set[str] = set()
        committed: set[str] = set()
        with os.scandir(directory) as entries:
            for entry in entries:
                visited += 1
                if visited > limits.max_entries:
                    raise AssetFileError("ASSET_STORAGE_SCAN_LIMIT")
                name = entry.name
                if level == 0 and name == LOCK_NAME:
                    info = entry.stat(follow_symlinks=False)
                    if (
                        not stat.S_ISREG(info.st_mode)
                        or info.st_nlink != 1
                        or info.st_size
                    ):
                        raise AssetFileError("ASSET_STORAGE_LOCK_FILE")
                    continue
                if level == 1:
                    account = _uuid(name)
                    with _child_directory(directory, account) as child:
                        visit(child, 2, account)
                elif (level == 0 and name == "accounts") or (
                    level == 2 and name == "blobs"
                ):
                    with _child_directory(directory, name) as child:
                        visit(child, level + 1, owner)
                elif level == 3:
                    if not stat.S_ISREG(entry.stat(follow_symlinks=False).st_mode):
                        raise AssetFileError("ASSET_STORAGE_FILE_TYPE")
                    if DIGEST.fullmatch(name):
                        continue
                    match = JOURNAL_NAME.fullmatch(name)
                    if match:
                        if len(receipts) >= limits.max_intents:
                            raise AssetFileError("ASSET_STORAGE_SCAN_LIMIT")
                        operation = _uuid(match[1])
                        assert owner is not None
                        receipts.append(files._read_intent(directory, owner, operation))
                        committed.add(operation)
                        continue
                    match = TEMP_NAME.fullmatch(name)
                    if match:
                        temporary.add(_uuid(match[1]))
                        if len(temporary) > limits.max_intents:
                            raise AssetFileError("ASSET_STORAGE_SCAN_LIMIT")
                        continue
                    raise AssetFileError("ASSET_STORAGE_UNKNOWN_FILE")
                else:
                    raise AssetFileError("ASSET_STORAGE_UNKNOWN_FILE")
        if temporary - committed:
            raise AssetFileError("ASSET_STORAGE_UNPROVEN_TEMPORARY")

    with _root_directory(files.root) as root:
        visit(root, 0)
    return tuple(receipts)
