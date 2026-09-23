"""Blocking, account-scoped immutable files. No authentication or DB publication.

Callers must authorize before I/O, bound worker concurrency, then reauthorize in
a fresh transaction before marking ready. A receipt proves durable bytes only.
The configured root must already exist; no live route uses this adapter yet.
"""

from contextlib import ExitStack, contextmanager
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import stat
from typing import BinaryIO, Generator, Iterator
from uuid import UUID, uuid4

from src.appearance.input import read_icon_bytes
from src.appearance.png import inspect_png
from src.appearance.profiles import IMAGE_PROFILES
from src.appearance.svg import inspect_svg
from src.v2.appearance import IconBlob


MAX_JOURNAL = 8192
DIGEST = re.compile(r"[0-9a-f]{64}")
JOURNAL_NAME = re.compile(r"\.install-([0-9a-f-]{36})\.json")


class AssetFileError(ValueError):
    """Invalid storage identity, file type, profile or journal; not an I/O retry."""


def _uuid(value: str) -> str:
    try:
        if type(value) is not str or str(UUID(value)) != value:
            raise ValueError
    except (ValueError, AttributeError) as error:
        raise AssetFileError("ASSET_STORAGE_ID") from error
    return value


def _blob(value: IconBlob) -> IconBlob:
    # Validate even model_construct/model_copy inputs before using a path segment.
    result = IconBlob.model_validate(value.model_dump())
    if DIGEST.fullmatch(result.sha256) is None:
        raise AssetFileError("ASSET_STORAGE_ID")
    return result


def _inspect(data: bytes, blob: IconBlob, profile: str) -> None:
    if IMAGE_PROFILES[blob.media_type] != profile:
        raise AssetFileError("ASSET_STORAGE_PROFILE")
    if blob.media_type == "image/png":
        inspect_png(data, blob)
    else:
        inspect_svg(data, blob)


@dataclass(frozen=True)
class InstallReceipt:
    operation_id: str
    owner_public_id: str
    blob: IconBlob
    profile: str

    def __post_init__(self) -> None:
        _uuid(self.operation_id)
        _uuid(self.owner_public_id)
        _blob(self.blob)
        if IMAGE_PROFILES[self.blob.media_type] != self.profile:
            raise AssetFileError("ASSET_STORAGE_PROFILE")

    @property
    def stem(self) -> str:
        return f".install-{self.operation_id}"

    def encode(self) -> bytes:
        return json.dumps(
            {
                "version": 1,
                "operation_id": self.operation_id,
                "owner_public_id": self.owner_public_id,
                "blob": self.blob.model_dump(),
                "profile": self.profile,
            },
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=True,
            allow_nan=False,
        ).encode("ascii")


def _decode(data: bytes, owner: str, operation: str) -> InstallReceipt:
    try:
        value = json.loads(data)
        if (
            not isinstance(value, dict)
            or set(value)
            != {"version", "operation_id", "owner_public_id", "blob", "profile"}
            or type(value["version"]) is not int
            or value["version"] != 1
            or value["owner_public_id"] != owner
            or value["operation_id"] != operation
        ):
            raise ValueError
        receipt = InstallReceipt(
            operation, owner, IconBlob.model_validate(value["blob"]), value["profile"]
        )
        # Equality to canonical bytes also rejects duplicate keys, BOM, alternate
        # encodings, whitespace and numeric/string spellings. Never repair logs.
        if receipt.encode() != data:
            raise ValueError
        return receipt
    except (ValueError, TypeError, KeyError, UnicodeError, RecursionError) as error:
        raise AssetFileError("ASSET_STORAGE_JOURNAL") from error


@contextmanager
def _regular(directory: int, name: str) -> Iterator[BinaryIO]:
    # NONBLOCK avoids waiting on a FIFO/device before fstat can reject it.
    descriptor = os.open(
        name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory
    )
    try:
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            raise AssetFileError("ASSET_STORAGE_FILE_TYPE")
        with os.fdopen(descriptor, "rb", closefd=False) as source:
            yield source
    finally:
        os.close(descriptor)


def _write_new(directory: int, name: str, data: bytes) -> None:
    descriptor = os.open(
        name,
        os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
        0o600,
        dir_fd=directory,
    )
    try:
        remaining = memoryview(data)
        while remaining:
            count = os.write(descriptor, remaining)
            if not 0 < count <= len(remaining):
                raise OSError("asset write made no valid progress")
            remaining = remaining[count:]
        os.lseek(descriptor, 0, os.SEEK_SET)
        with os.fdopen(descriptor, "rb", closefd=False) as source:
            saved = source.read(len(data) + 1)
        if (
            len(saved) != len(data)
            or hashlib.sha256(saved).digest() != hashlib.sha256(data).digest()
        ):
            raise OSError("asset write readback mismatch")
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _validate_root(root: Path) -> None:
    if not root.is_absolute() or len(root.parts) < 2 or ".." in root.parts:
        raise AssetFileError("ASSET_STORAGE_ROOT")


@contextmanager
def _root_directory(root: Path) -> Iterator[int]:
    _validate_root(root)
    descriptor = os.open("/", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for name in root.parts[1:]:
            child = os.open(
                name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptor
            )
            os.close(descriptor)
            descriptor = child
        yield descriptor
    finally:
        os.close(descriptor)


@contextmanager
def _child_directory(parent: int, name: str) -> Iterator[int]:
    descriptor = os.open(
        name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent
    )
    try:
        yield descriptor
    finally:
        os.close(descriptor)


class AssetFiles:
    def __init__(self, root: Path):
        _validate_root(root)
        self.root = root

    @contextmanager
    def _directory(self, owner: str, *, create: bool = False) -> Iterator[int]:
        _uuid(owner)
        with ExitStack() as stack:
            descriptor = stack.enter_context(_root_directory(self.root))
            for name in ("accounts", owner, "blobs"):
                if create:
                    try:
                        os.mkdir(name, 0o700, dir_fd=descriptor)
                    except FileExistsError:
                        pass
                    # Also sync parents created by an interrupted attempt.
                    os.fsync(descriptor)
                descriptor = stack.enter_context(_child_directory(descriptor, name))
            yield descriptor

    def publish(
        self, owner: str, source: BinaryIO, expected: IconBlob
    ) -> InstallReceipt:
        """Validate once, persist an intent and immutable bytes; leave intent open.

        Caller owns source closing/timeouts. Exceptions retain recovery evidence;
        success must never be equated with authenticated DB ready publication.
        """
        _uuid(owner)
        blob = _blob(expected)
        profile = IMAGE_PROFILES[blob.media_type]
        data = read_icon_bytes(source, blob)
        _inspect(data, blob, profile)
        receipt = InstallReceipt(str(uuid4()), owner, blob, profile)
        with self._directory(owner, create=True) as directory:
            journal = receipt.stem + ".json"
            temporary = journal + ".tmp"
            _write_new(directory, temporary, receipt.encode())
            # Atomic, no-clobber publication. Even a UUID collision must not
            # overwrite a prior operation's recovery evidence.
            os.link(
                temporary,
                journal,
                src_dir_fd=directory,
                dst_dir_fd=directory,
                follow_symlinks=False,
            )
            os.unlink(temporary, dir_fd=directory)
            os.fsync(directory)
            _write_new(directory, receipt.stem + ".part", data)
            try:
                info = os.stat(blob.sha256, dir_fd=directory, follow_symlinks=False)
            except FileNotFoundError:
                pass
            else:
                if not stat.S_ISREG(info.st_mode):
                    raise AssetFileError("ASSET_STORAGE_FILE_TYPE")
                # A legitimate existing immutable blob must describe these bytes.
                # Corruption is reported, not silently overwritten by a retry.
                with _regular(directory, blob.sha256) as existing:
                    read_icon_bytes(existing, blob)
            os.replace(
                receipt.stem + ".part",
                blob.sha256,
                src_dir_fd=directory,
                dst_dir_fd=directory,
            )
            os.fsync(directory)
        return receipt

    def read(self, owner: str, expected: IconBlob, profile: str) -> bytes:
        """Recheck a same-handle file, never trust a ready flag or filename alone."""
        blob = _blob(expected)
        if IMAGE_PROFILES[blob.media_type] != profile:
            raise AssetFileError("ASSET_STORAGE_PROFILE")
        with self._directory(owner) as directory:
            with _regular(directory, blob.sha256) as source:
                data = read_icon_bytes(source, blob)
        _inspect(data, blob, profile)
        return data

    @contextmanager
    def pending(self, owner: str) -> Iterator[Iterator[InstallReceipt]]:
        """Enumerate canonical committed intents, not guessed orphan ownership.

        Use as a context manager, including when stopping iteration early.
        A malformed committed intent raises and remains untouched. Unknown files
        and journal preparation leftovers are not treated as removable garbage.
        """
        with self._directory(owner) as directory:
            with os.scandir(directory) as entries:

                def load() -> Generator[InstallReceipt, None, None]:
                    for entry in entries:
                        match = JOURNAL_NAME.fullmatch(entry.name)
                        if match:
                            operation = _uuid(match[1])
                            yield self._read_intent(directory, owner, operation)

                iterator = load()
                try:
                    yield iterator
                finally:
                    iterator.close()

    @staticmethod
    def _read_intent(directory: int, owner: str, operation: str) -> InstallReceipt:
        with _regular(directory, f".install-{operation}.json") as source:
            data = source.read(MAX_JOURNAL + 1)
        if len(data) > MAX_JOURNAL:
            raise AssetFileError("ASSET_STORAGE_JOURNAL")
        return _decode(data, owner, operation)

    def finish(self, receipt: InstallReceipt) -> None:
        """Remove this proven intent's temporary files, never a final blob.

        Coordinator decides if DB committed/bytes verified or pending abandoned.
        Repetition after successful cleanup is safe; absent intent grants no
        authority to remove leftover files. Corrupt evidence remains untouched.
        """
        with self._directory(receipt.owner_public_id) as directory:
            try:
                saved = self._read_intent(
                    directory, receipt.owner_public_id, receipt.operation_id
                )
            except FileNotFoundError:
                os.fsync(directory)
                return
            if saved != receipt:
                raise AssetFileError("ASSET_STORAGE_JOURNAL")
            for suffix in (".part", ".json.tmp", ".json"):
                name = receipt.stem + suffix
                try:
                    info = os.stat(name, dir_fd=directory, follow_symlinks=False)
                except FileNotFoundError:
                    continue
                if not stat.S_ISREG(info.st_mode):
                    raise AssetFileError("ASSET_STORAGE_FILE_TYPE")
                os.unlink(name, dir_fd=directory)
            os.fsync(directory)
