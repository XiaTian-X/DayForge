"""Resource and syntax boundaries before any database transaction."""

import hashlib
from io import BytesIO
import json
import zipfile

import pytest

from src.storage import logical_io as io
from src.storage.backup_zip import BackupZip, BackupZipError
from src.storage.errors import StorageValidationError


def encoded(value):
    return json.dumps(value, separators=(",", ":")).encode()


def bundle(*, version=3, row=None):
    record = (
        encoded(row if row is not None else {"key": "synthetic", "data": {}}) + b"\n"
    )
    content = {"users": record, "server_instances": b""}
    manifest = {
        "format_version": version,
        "collections": {
            name: {
                "file": f"collections/{name}.jsonl",
                "rows": 1 if data else 0,
                "sha256": hashlib.sha256(data).hexdigest(),
            }
            for name, data in content.items()
        },
    }
    return manifest, content


def archive(manifest, collections, *, extra=None):
    output = BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as writer:
        writer.writestr(
            "manifest.json",
            manifest if isinstance(manifest, bytes) else encoded(manifest),
        )
        for name, data in collections.items():
            writer.writestr(f"collections/{name}.jsonl", data)
        if extra:
            writer.writestr(*extra)
    output.seek(0)
    return output


def read(manifest, collections, **kwargs):
    with archive(manifest, collections, **kwargs) as source:
        return io.read_collections(
            BackupZip(source), frozenset({"users", "server_instances"})
        )


@pytest.mark.parametrize(
    "payload",
    [
        b"[]",
        b"null",
        b'{"a":1,"a":2}',
        b'{"a":{"b":1,"b":2}}',
        b'{"a":NaN}',
        b'{"a":Infinity}',
        b'{"a":1e999}',
        b'{"a":-1e999}',
        b'{"a":',
        b'{"a":"\xff"}',
    ],
)
def test_bad_json_is_rejected(payload):
    with pytest.raises(StorageValidationError):
        io.json_object(payload, limit=1024)


def test_json_exact_byte_and_depth_limits_with_quotes_and_escaped_braces():
    payload = b'{"a":"literal [{ \\" quoted"}'
    assert io.json_object(payload, limit=len(payload))["a"].startswith("literal")
    with pytest.raises(StorageValidationError, match="byte budget"):
        io.json_object(payload, limit=len(payload) - 1)
    permitted = b'{"a":' * 32 + b"0" + b"}" * 32
    assert io.json_object(permitted, limit=1024)
    with pytest.raises(StorageValidationError, match="depth budget"):
        io.json_object(b'{"a":' + permitted + b"}", limit=1024)


@pytest.mark.parametrize(
    "row",
    [
        {},
        {"key": [], "data": {}},
        {"key": "", "data": {}},
        {"key": "a", "data": []},
        {"key": "a", "data": {}, "extra": 1},
    ],
)
def test_bad_record_shapes_are_rejected(row):
    manifest, collections = bundle(row=row)
    with pytest.raises(StorageValidationError, match="invalid logical record"):
        read(manifest, collections)


@pytest.mark.parametrize(
    "damage",
    [
        "unknown",
        "missing-required",
        "path",
        "bool-count",
        "negative-count",
        "bad-hash",
        "descriptor",
        "duplicate-key",
    ],
)
def test_collection_metadata_is_not_trusted(damage):
    manifest, collections = bundle()
    spec = manifest["collections"]["users"]
    if damage == "unknown":
        manifest["collections"]["unknown"] = spec
    elif damage == "missing-required":
        del manifest["collections"]["users"]
    elif damage == "path":
        spec["file"] = "../users.jsonl"
    elif damage == "bool-count":
        spec["rows"] = True
    elif damage == "negative-count":
        spec["rows"] = -1
    elif damage == "bad-hash":
        spec["sha256"] = "A" * 64
    elif damage == "descriptor":
        spec["extra"] = True
    else:
        collections["users"] *= 2
        spec.update(rows=2, sha256=hashlib.sha256(collections["users"]).hexdigest())
    with pytest.raises(StorageValidationError):
        read(manifest, collections)


def test_duplicate_json_keys_inside_collection_cannot_be_hidden_by_matching_hash():
    manifest, collections = bundle()
    collections["users"] = b'{"key":"same","data":{},"data":{"hidden":1}}\n'
    manifest["collections"]["users"]["sha256"] = hashlib.sha256(
        collections["users"]
    ).hexdigest()
    with pytest.raises(StorageValidationError, match="duplicate logical JSON key"):
        read(manifest, collections)


@pytest.mark.parametrize("kind", ["manifest", "collection", "total", "record", "rows"])
def test_exact_budgets_then_one_under_rejects(kind, monkeypatch):
    manifest, collections = bundle()
    if kind == "manifest":
        attr, size = "MANIFEST_LIMIT", len(encoded(manifest))
    elif kind == "collection":
        attr, size = "COLLECTION_LIMIT", len(collections["users"])
    elif kind == "total":
        attr, size = "TOTAL_LIMIT", len(collections["users"])
    elif kind == "record":
        attr, size = "RECORD_LIMIT", len(collections["users"])
    else:
        attr, size = "ROW_LIMIT", 1
    monkeypatch.setattr(io, attr, size)
    assert len(read(manifest, collections)[1]["users"]) == 1
    monkeypatch.setattr(io, attr, size - 1)
    with pytest.raises((StorageValidationError, BackupZipError)):
        read(manifest, collections)


def test_real_compression_bomb_is_rejected_at_read_limit():
    manifest, collections = bundle()
    collections["users"] = b" " * (16 * 1024 * 1024 + 1)
    manifest["collections"]["users"]["sha256"] = hashlib.sha256(
        collections["users"]
    ).hexdigest()
    with pytest.raises(BackupZipError, match="ZIP_LIMIT"):
        read(manifest, collections)


@pytest.mark.parametrize("version", [1, 2])
def test_legacy_archive_extra_files_are_rejected(version):
    manifest, collections = bundle(version=version)
    with pytest.raises(StorageValidationError, match="undeclared files"):
        read(manifest, collections, extra=("ignored.txt", b"not allowed"))


def test_reader_never_constructs_standard_zipfile_before_preflight(monkeypatch):
    manifest, collections = bundle()
    with archive(manifest, collections) as source:

        def fail(*_args, **_kwargs):
            pytest.fail("unbounded standard ZIP reader")

        monkeypatch.setattr(zipfile, "ZipFile", fail)
        assert (
            io.read_collections(BackupZip(source), frozenset(collections))[0]
            == manifest
        )
