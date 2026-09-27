"""Bounded logical JSON/ZIP boundary. No database or deployment mutation."""

from dataclasses import dataclass
import hashlib
from io import BytesIO
import json
import math
from typing import Any

from src.storage.backup_files import checked_digest, checked_size
from src.storage.backup_zip import BackupZip
from src.storage.errors import StorageValidationError


MANIFEST_LIMIT = 65_536
COLLECTION_LIMIT = 16 * 1024 * 1024
TOTAL_LIMIT = 64 * 1024 * 1024
RECORD_LIMIT = 1024 * 1024
ROW_LIMIT = 250_000
DEPTH_LIMIT = 32


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise StorageValidationError("duplicate logical JSON key")
        result[key] = value
    return result


def _constant(_value):
    raise StorageValidationError("nonfinite logical JSON number")


def json_object(data: bytes, *, limit: int) -> dict[str, Any]:
    if len(data) > limit:
        raise StorageValidationError("logical JSON exceeds byte budget")
    # Count syntax nesting before json.loads allocates a nested object graph.
    depth = 0
    quoted = escaped = False
    for value in data:
        if quoted:
            if escaped:
                escaped = False
            elif value == 92:
                escaped = True
            elif value == 34:
                quoted = False
        elif value == 34:
            quoted = True
        elif value in (91, 123):
            depth += 1
            if depth > DEPTH_LIMIT:
                raise StorageValidationError("logical JSON exceeds depth budget")
        elif value in (93, 125):
            depth -= 1
    try:
        result = json.loads(
            data.decode("utf-8"), object_pairs_hook=_pairs, parse_constant=_constant
        )
    except (ValueError, RecursionError) as error:
        raise StorageValidationError("invalid logical JSON") from error
    if not isinstance(result, dict):
        raise StorageValidationError("logical JSON must be an object")

    # json.loads also accepts 1e999 as infinity without parse_constant.
    def finite(value):
        if isinstance(value, float) and not math.isfinite(value):
            raise StorageValidationError("nonfinite logical JSON number")
        if isinstance(value, dict):
            for item in value.values():
                finite(item)
        elif isinstance(value, list):
            for item in value:
                finite(item)

    finite(result)
    return result


@dataclass
class Budget:
    size: int = 0
    rows: int = 0

    def consume(self, size: int, rows: int = 0) -> None:
        self.size += size
        self.rows += rows
        if self.size > TOTAL_LIMIT or self.rows > ROW_LIMIT:
            raise StorageValidationError("logical collections exceed total budget")


def read_collections(
    archive: BackupZip, allowed: frozenset[str]
) -> tuple[dict[str, Any], dict[str, list[dict[str, Any]]], frozenset[str]]:
    if "manifest.json" not in archive.names:
        raise StorageValidationError("logical archive has no manifest")
    manifest = json_object(
        archive.read("manifest.json", limit=MANIFEST_LIMIT), limit=MANIFEST_LIMIT
    )
    version = manifest.get("format_version")
    if type(version) is not int or version not in (1, 2, 3):
        raise StorageValidationError("unsupported logical archive version")
    specifications = manifest.get("collections")
    if (
        not isinstance(specifications, dict)
        or not {"users", "server_instances"} <= specifications.keys()
    ):
        raise StorageValidationError("logical archive is missing required collections")
    if not specifications.keys() <= allowed:
        raise StorageValidationError("logical archive has an unknown collection")
    expected = {"manifest.json"}
    collections = {}
    budget = Budget()
    for name, specification in specifications.items():
        if not isinstance(specification, dict) or set(specification) != {
            "file",
            "rows",
            "sha256",
        }:
            raise StorageValidationError("invalid logical collection descriptor")
        member = specification["file"]
        if member != f"collections/{name}.jsonl" or member not in archive.names:
            raise StorageValidationError(f"invalid collection path for {name}")
        expected.add(member)
        rows = checked_size(specification["rows"], maximum=ROW_LIMIT)
        content = archive.read(
            member, limit=min(COLLECTION_LIMIT, TOTAL_LIMIT - budget.size)
        )
        budget.consume(len(content))
        if hashlib.sha256(content).hexdigest() != checked_digest(
            specification["sha256"]
        ):
            raise StorageValidationError(f"collection checksum mismatch: {name}")
        records = []
        keys = set()
        # Iterate lines, not splitlines() allocating every line in advance.
        with BytesIO(content) as lines:
            for line in lines:
                if line == b"\n":
                    continue
                budget.consume(0, 1)
                record = json_object(line, limit=RECORD_LIMIT)
                if (
                    set(record) != {"key", "data"}
                    or type(record["key"]) is not str
                    or not record["key"]
                    or not isinstance(record["data"], dict)
                ):
                    raise StorageValidationError("invalid logical record")
                if record["key"] in keys:
                    raise StorageValidationError(f"duplicate logical key in {name}")
                keys.add(record["key"])
                records.append(record)
        if len(records) != rows:
            raise StorageValidationError(f"collection row count mismatch: {name}")
        collections[name] = records
    if version < 3 and archive.names != expected:
        raise StorageValidationError("logical archive contains undeclared files")
    return manifest, collections, frozenset(expected)
