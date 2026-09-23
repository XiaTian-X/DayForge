import base64
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
import hashlib
from io import BytesIO
import json
import os
from pathlib import Path
import stat
from uuid import UUID

import pytest

from src.appearance.input import ImageInputError
from src.appearance.svg_path import SvgValidationError
from src.storage.asset_files import AssetFileError, AssetFiles, MAX_JOURNAL
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


def test_publish_reopen_retry_isolated_namespaces_and_exact_cleanup(tmp_path):
    store = AssetFiles(tmp_path)
    source = BytesIO(SVG)
    first = store.publish(OWNER, source, blob())
    assert source.tell() == len(SVG) and not source.closed
    path = directory(tmp_path)
    assert (path / first.blob.sha256).read_bytes() == SVG
    assert stat.S_IMODE(path.stat().st_mode) == 0o700
    assert stat.S_IMODE((path / first.blob.sha256).stat().st_mode) == 0o600
    assert stat.S_IMODE((path / (first.stem + ".json")).stat().st_mode) == 0o600
    assert intents(AssetFiles(tmp_path)) == [first]
    assert store.read(OWNER, blob(), "svg-v1") == SVG
    second = store.publish(OWNER, BytesIO(SVG), blob())
    other = store.publish(OTHER, BytesIO(SVG), blob())
    assert first.operation_id != second.operation_id
    assert first.blob.sha256 == other.blob.sha256
    assert (directory(tmp_path, OTHER) / other.blob.sha256).stat().st_ino != (
        path / first.blob.sha256
    ).stat().st_ino
    # Only this intent's temporary files may be removed, final CAS bytes remain.
    (path / (first.stem + ".part")).write_bytes(b"incomplete attempt")
    (path / "unknown.part").write_bytes(b"not ours")
    store.finish(first)
    store.finish(first)
    assert intents(store) == [second]
    assert intents(store, OTHER) == [other]
    assert (path / "unknown.part").read_bytes() == b"not ours"
    assert store.read(OWNER, blob(), "svg-v1") == SVG


def test_png_publication_runs_real_decode_and_keeps_original_bytes(tmp_path):
    cases = json.loads(
        (Path(__file__).resolve().parents[2] / "contracts/next/png.json").read_text()
    )
    data = base64.b64decode(
        next(item["png"] for item in cases if item["name"] == "rgba"), validate=True
    )
    store = AssetFiles(tmp_path)
    receipt = store.publish(OWNER, BytesIO(data), blob(data, "image/png"))
    assert receipt.profile == "png-v1"
    assert store.read(OWNER, receipt.blob, "png-v1") == data


@pytest.mark.parametrize(
    "owner", ["../escape", "", OWNER.upper(), OWNER + "/x", "{" + OWNER + "}"]
)
def test_owner_path_identity_rejected_before_consuming_input(tmp_path, owner):
    source = BytesIO(SVG)
    with pytest.raises(AssetFileError, match="ASSET_STORAGE_ID"):
        AssetFiles(tmp_path).publish(owner, source, blob())
    assert source.tell() == 0
    assert list(tmp_path.iterdir()) == []


@pytest.mark.parametrize("root", [Path("relative"), Path("/"), Path("/private/../tmp")])
def test_root_configuration_must_be_absolute_narrow_and_unambiguous(root):
    with pytest.raises(AssetFileError, match="ASSET_STORAGE_ROOT"):
        AssetFiles(root)


def test_invalid_or_forged_descriptor_never_becomes_a_path(tmp_path):
    for digest in ("../" + "a" * 61, "a" * 64 + "\n", "A" * 64):
        forged = blob().model_copy(update={"sha256": digest})
        with pytest.raises(ValueError):
            AssetFiles(tmp_path).publish(OWNER, BytesIO(SVG), forged)
    assert list(tmp_path.iterdir()) == []


@pytest.mark.parametrize("data", [SVG[:-1], SVG + b"x", b"x" * len(SVG)])
def test_length_hash_failure_creates_no_directory_or_journal(tmp_path, data):
    with pytest.raises(ImageInputError):
        AssetFiles(tmp_path).publish(OWNER, BytesIO(data), blob())
    assert list(tmp_path.iterdir()) == []


def test_format_failure_does_not_persist_even_when_hash_matches(tmp_path):
    data = b'<svg width="1" height="1"><script/></svg>'
    with pytest.raises(SvgValidationError, match="SVG_ELEMENT"):
        AssetFiles(tmp_path).publish(OWNER, BytesIO(data), blob(data))
    assert list(tmp_path.iterdir()) == []


def test_reads_do_not_create_missing_owner_or_root(tmp_path):
    for root in (tmp_path, tmp_path / "absent"):
        with pytest.raises(FileNotFoundError):
            AssetFiles(root).read(OWNER, blob(), "svg-v1")
    assert list(tmp_path.iterdir()) == []


@pytest.mark.parametrize("component", ["root", "accounts", "owner", "blobs"])
def test_symlink_directory_is_never_followed(tmp_path, component):
    outside = tmp_path / "outside"
    outside.mkdir()
    root = tmp_path / "root"
    root.mkdir()
    target = {
        "root": root,
        "accounts": root / "accounts",
        "owner": root / "accounts" / OWNER,
        "blobs": directory(root),
    }[component]
    if target == root:
        root.rmdir()
    else:
        target.parent.mkdir(parents=True, exist_ok=True)
    target.symlink_to(outside, target_is_directory=True)
    with pytest.raises(OSError):
        AssetFiles(root).publish(OWNER, BytesIO(SVG), blob())
    assert list(outside.iterdir()) == []


@pytest.mark.parametrize("kind", ["symlink", "fifo", "directory"])
def test_non_regular_final_file_cannot_be_read_or_replaced(tmp_path, kind):
    store = AssetFiles(tmp_path)
    receipt = store.publish(OWNER, BytesIO(SVG), blob())
    target = directory(tmp_path) / receipt.blob.sha256
    target.unlink()
    outside = tmp_path / "outside"
    outside.write_bytes(b"do not touch")
    if kind == "symlink":
        target.symlink_to(outside)
    elif kind == "fifo":
        os.mkfifo(target)
    else:
        target.mkdir()
    with pytest.raises((OSError, AssetFileError)):
        store.read(OWNER, blob(), "svg-v1")
    with pytest.raises(AssetFileError, match="ASSET_STORAGE_FILE_TYPE"):
        store.publish(OWNER, BytesIO(SVG), blob())
    assert outside.read_bytes() == b"do not touch"


def test_missing_corrupt_and_unknown_profile_are_not_success_or_repaired(tmp_path):
    store = AssetFiles(tmp_path)
    receipt = store.publish(OWNER, BytesIO(SVG), blob())
    target = directory(tmp_path) / receipt.blob.sha256
    with pytest.raises(AssetFileError, match="ASSET_STORAGE_PROFILE"):
        store.read(OWNER, blob(), "svg-v2")
    target.write_bytes(b"x" * len(SVG))
    with pytest.raises(ImageInputError, match="IMAGE_HASH"):
        store.read(OWNER, blob(), "svg-v1")
    with pytest.raises(ImageInputError, match="IMAGE_HASH"):
        store.publish(OWNER, BytesIO(SVG), blob())
    assert target.read_bytes() == b"x" * len(SVG)
    target.unlink()
    with pytest.raises(FileNotFoundError):
        store.read(OWNER, blob(), "svg-v1")


@pytest.mark.parametrize(
    "change",
    [
        "version",
        "boolean",
        "owner",
        "operation",
        "profile",
        "duplicate",
        "unknown",
        "whitespace",
        "large",
        "deep",
        "bom",
    ],
)
def test_bad_intent_never_authorizes_cleanup(tmp_path, change):
    store = AssetFiles(tmp_path)
    receipt = store.publish(OWNER, BytesIO(SVG), blob())
    journal = directory(tmp_path) / (receipt.stem + ".json")
    original = receipt.encode()
    value = json.loads(original)
    if change in {"version", "boolean"}:
        value["version"] = 2 if change == "version" else True
    elif change == "owner":
        value["owner_public_id"] = OTHER
    elif change == "operation":
        value["operation_id"] = OTHER
    elif change == "profile":
        value["profile"] = "svg-v2"
    elif change == "unknown":
        value["extra"] = 1
    data = json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
    if change == "duplicate":
        data = b'{"version":1,' + original[1:]
    elif change == "whitespace":
        data = original + b"\n"
    elif change == "large":
        data = b"x" * (MAX_JOURNAL + 1)
    elif change == "deep":
        data = b"[" * 2000 + b"0" + b"]" * 2000
    elif change == "bom":
        data = b"\xef\xbb\xbf" + original
    journal.write_bytes(data)
    with pytest.raises(AssetFileError, match="ASSET_STORAGE_JOURNAL"):
        intents(store)
    with pytest.raises(AssetFileError, match="ASSET_STORAGE_JOURNAL"):
        store.finish(receipt)
    assert journal.read_bytes() == data
    assert store.read(OWNER, blob(), "svg-v1") == SVG


def test_wrong_valid_receipt_and_cross_owner_cleanup_are_rejected(tmp_path):
    store = AssetFiles(tmp_path)
    receipt = store.publish(OWNER, BytesIO(SVG), blob())
    wrong = replace(receipt, blob=blob(b'<svg width="1" height="1"/>'))
    with pytest.raises(AssetFileError, match="ASSET_STORAGE_JOURNAL"):
        store.finish(wrong)
    with pytest.raises(FileNotFoundError):
        store.finish(replace(receipt, owner_public_id=OTHER))
    assert intents(store) == [receipt]


def test_short_writes_are_completed_and_zero_progress_leaves_intent(
    tmp_path, monkeypatch
):
    write = os.write
    monkeypatch.setattr(os, "write", lambda fd, data: write(fd, data[:3]))
    store = AssetFiles(tmp_path)
    receipt = store.publish(OWNER, BytesIO(SVG), blob())
    assert store.read(OWNER, blob(), "svg-v1") == SVG
    store.finish(receipt)

    def stop_on_image(fd, data):
        return 0 if bytes(data).startswith(b"<svg") else write(fd, data)

    monkeypatch.setattr(os, "write", stop_on_image)
    with pytest.raises(OSError, match="no valid progress"):
        store.publish(OWNER, BytesIO(SVG), blob())
    pending = intents(store)
    assert len(pending) == 1
    assert store.read(OWNER, blob(), "svg-v1") == SVG
    store.finish(pending[0])


def test_written_bytes_are_verified_from_same_descriptor(tmp_path, monkeypatch):
    write = os.write

    def corrupt_image(fd, data):
        return (
            write(fd, b"x" * len(data))
            if bytes(data).startswith(b"<svg")
            else write(fd, data)
        )

    monkeypatch.setattr(os, "write", corrupt_image)
    store = AssetFiles(tmp_path)
    with pytest.raises(OSError, match="readback mismatch"):
        store.publish(OWNER, BytesIO(SVG), blob())
    assert not (directory(tmp_path) / blob().sha256).exists()
    pending = intents(store)
    assert len(pending) == 1
    store.finish(pending[0])


@pytest.mark.parametrize("fail_at", range(1, 8))
def test_each_fsync_failure_is_failure_and_reopen_retry_is_safe(
    tmp_path, monkeypatch, fail_at
):
    fsync = os.fsync
    calls = 0

    def fail(fd):
        nonlocal calls
        calls += 1
        if calls == fail_at:
            raise OSError("synthetic fsync failure")
        fsync(fd)

    store = AssetFiles(tmp_path)
    with monkeypatch.context() as context:
        context.setattr(os, "fsync", fail)
        with pytest.raises(OSError, match="synthetic fsync failure"):
            store.publish(OWNER, BytesIO(SVG), blob())
    assert calls == fail_at
    reopened = AssetFiles(tmp_path)
    receipt = reopened.publish(OWNER, BytesIO(SVG), blob())
    assert reopened.read(OWNER, blob(), "svg-v1") == SVG
    for pending in intents(reopened):
        reopened.finish(pending)
    assert intents(reopened) == []
    assert reopened.read(OWNER, receipt.blob, receipt.profile) == SVG


def test_rename_failure_preserves_intent_and_partial_for_recovery(
    tmp_path, monkeypatch
):
    store = AssetFiles(tmp_path)

    def fail(*args, **kwargs):
        raise OSError("synthetic rename failure")

    with monkeypatch.context() as context:
        context.setattr(os, "replace", fail)
        with pytest.raises(OSError, match="synthetic rename failure"):
            store.publish(OWNER, BytesIO(SVG), blob())
    pending = intents(store)
    assert len(pending) == 1
    assert not (directory(tmp_path) / blob().sha256).exists()
    assert (directory(tmp_path) / (pending[0].stem + ".part")).read_bytes() == SVG
    store.finish(pending[0])
    assert list(directory(tmp_path).iterdir()) == []


def test_operation_collision_does_not_clobber_intent(tmp_path, monkeypatch):
    store = AssetFiles(tmp_path)
    receipt = store.publish(OWNER, BytesIO(SVG), blob())
    monkeypatch.setattr(
        "src.storage.asset_files.uuid4", lambda: UUID(receipt.operation_id)
    )
    other_data = b'<svg width="1" height="1"/>'
    with pytest.raises(FileExistsError):
        store.publish(OWNER, BytesIO(other_data), blob(other_data))
    assert intents(store) == [receipt]
    assert not (directory(tmp_path) / blob(other_data).sha256).exists()
    assert store.read(OWNER, blob(), "svg-v1") == SVG


def test_cleanup_failure_retains_intent_and_symlink_is_not_deleted(
    tmp_path, monkeypatch
):
    store = AssetFiles(tmp_path)
    receipt = store.publish(OWNER, BytesIO(SVG), blob())
    path = directory(tmp_path)
    partial = path / (receipt.stem + ".part")
    partial.symlink_to(path / receipt.blob.sha256)
    with pytest.raises(AssetFileError, match="ASSET_STORAGE_FILE_TYPE"):
        store.finish(receipt)
    assert partial.is_symlink() and intents(store) == [receipt]
    partial.unlink()
    unlink = os.unlink

    def fail(name, **kwargs):
        if str(name).endswith(".json"):
            raise OSError("synthetic unlink failure")
        unlink(name, **kwargs)

    with monkeypatch.context() as context:
        context.setattr(os, "unlink", fail)
        with pytest.raises(OSError, match="synthetic unlink failure"):
            store.finish(receipt)
    assert intents(store) == [receipt]
    store.finish(receipt)
    assert store.read(OWNER, blob(), "svg-v1") == SVG


def test_concurrent_same_hash_has_independent_intents_and_stable_bytes(tmp_path):
    store = AssetFiles(tmp_path)
    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = [
            pool.submit(store.publish, OWNER, BytesIO(SVG), blob()) for _ in range(8)
        ]
        receipts = [future.result(timeout=10) for future in futures]
    assert len({receipt.operation_id for receipt in receipts}) == 8
    assert set(intents(store)) == set(receipts)
    assert store.read(OWNER, blob(), "svg-v1") == SVG
    for receipt in receipts:
        store.finish(receipt)
    assert [item.name for item in directory(tmp_path).iterdir()] == [blob().sha256]


def test_file_descriptors_close_on_success_failure_and_early_scan_exit(
    tmp_path, monkeypatch
):
    opened: set[int] = set()
    original_open, original_close = os.open, os.close

    def tracked_open(*args, **kwargs):
        descriptor = original_open(*args, **kwargs)
        opened.add(descriptor)
        return descriptor

    def tracked_close(descriptor):
        original_close(descriptor)
        opened.remove(descriptor)

    store = AssetFiles(tmp_path)
    with monkeypatch.context() as context:
        context.setattr(os, "open", tracked_open)
        context.setattr(os, "close", tracked_close)
        receipt = store.publish(OWNER, BytesIO(SVG), blob())
        assert not opened
        with store.pending(OWNER) as iterator:
            assert next(iterator) == receipt
            assert opened
        assert not opened
        assert list(iterator) == []
        assert store.read(OWNER, blob(), "svg-v1") == SVG
        assert not opened
        (directory(tmp_path) / receipt.blob.sha256).write_bytes(b"broken")
        with pytest.raises(ImageInputError):
            store.read(OWNER, blob(), "svg-v1")
        assert not opened
        store.finish(receipt)
        assert not opened


@pytest.mark.parametrize("stage", ["link", "journal_unlink", "file_write"])
def test_prepublication_io_failures_cannot_return_receipt_or_publish_bytes(
    tmp_path, monkeypatch, stage
):
    store = AssetFiles(tmp_path)
    operation = UUID("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
    monkeypatch.setattr("src.storage.asset_files.uuid4", lambda: operation)
    with monkeypatch.context() as context:
        if stage == "link":

            def fail_link(*args, **kwargs):
                raise OSError("synthetic link failure")

            context.setattr(os, "link", fail_link)
        elif stage == "journal_unlink":

            def fail_unlink(*args, **kwargs):
                raise OSError("synthetic unlink failure")

            context.setattr(os, "unlink", fail_unlink)
        else:
            write = os.write

            def fail_write(fd, data):
                if bytes(data).startswith(b"<svg"):
                    raise OSError("synthetic disk full")
                return write(fd, data)

            context.setattr(os, "write", fail_write)
        with pytest.raises(OSError, match="synthetic"):
            store.publish(OWNER, BytesIO(SVG), blob())
    path = directory(tmp_path)
    assert not (path / blob().sha256).exists()
    if stage == "link":
        assert intents(store) == []
        assert (path / f".install-{operation}.json.tmp").is_file()
    else:
        pending = intents(store)
        assert len(pending) == 1
        store.finish(pending[0])
        assert list(path.iterdir()) == []


def test_cleanup_fsync_failure_is_reported_and_retry_completes(tmp_path, monkeypatch):
    store = AssetFiles(tmp_path)
    receipt = store.publish(OWNER, BytesIO(SVG), blob())

    def fail(*args):
        raise OSError("synthetic cleanup fsync failure")

    with monkeypatch.context() as context:
        context.setattr(os, "fsync", fail)
        with pytest.raises(OSError, match="synthetic cleanup"):
            store.finish(receipt)
    store.finish(receipt)
    assert intents(store) == []
    assert store.read(OWNER, blob(), "svg-v1") == SVG
