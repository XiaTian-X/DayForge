import base64
from dataclasses import replace
from io import BytesIO
import itertools
import json
from pathlib import Path
import zipfile

import pytest

from src.appearance.input import ImageInputError
from src.appearance.svg_path import SvgValidationError
from src.storage.asset_archive import ArchiveBlob, AssetArchive, write_asset_archive
from src.storage.asset_files import AssetFiles
from src.storage.backup_zip import BackupZipError, ZipLimits
from tests.asset_file_fixtures import OWNER, OTHER, SVG, blob, directory


def descriptor(owner=OWNER, data=SVG, media="image/svg+xml", profile="svg-v1"):
    return ArchiveBlob(owner, blob(data, media), profile)


def saved_files(root, entries):
    files = AssetFiles(root)
    for entry, data in entries:
        with BytesIO(data) as source:
            receipt = files.publish(entry.owner_public_id, source, entry.blob)
        files.finish(receipt)
    return files


def zip_bytes(entries):
    output = BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as writer:
        for name, data in entries:
            writer.writestr(name, data)
    return output.getvalue()


def test_real_files_round_trip_both_formats_and_cross_account_identity(tmp_path):
    cases = json.loads(
        (Path(__file__).resolve().parents[2] / "contracts/next/png.json").read_text()
    )
    png = base64.b64decode(
        next(case["png"] for case in cases if case["name"] == "rgba"), validate=True
    )
    entries = [
        (descriptor(), SVG),
        (descriptor(OTHER), SVG),
        (descriptor(data=png, media="image/png", profile="png-v1"), png),
    ]
    files = saved_files(tmp_path, entries)
    expected = [item for item, _ in entries]
    with BytesIO() as output:
        write_asset_archive(output, files, [*expected, expected[0]])
        assert not output.closed
        with zipfile.ZipFile(output) as independent:
            assert len(independent.infolist()) == 3  # Same-owner dedup only.
            for entry, data in entries:
                assert independent.read(entry.name) == data
        reader = AssetArchive(output, expected)
        reader.verify()
        for entry, data in entries:
            assert reader.read(entry.name) == data
    assert len(list(tmp_path.rglob("*.json"))) == 0


def test_empty_archive_has_no_implicit_pending_bytes(tmp_path):
    with BytesIO() as output:
        write_asset_archive(output, AssetFiles(tmp_path), [])
        AssetArchive(output, []).verify()
        with pytest.raises(BackupZipError, match="ZIP_ASSET_ENTRIES"):
            AssetArchive(output, [descriptor()])
    assert list(tmp_path.iterdir()) == []


@pytest.mark.parametrize(
    "additional",
    [
        frozenset({"manifest.json"}),
        frozenset(),
        frozenset({"manifest.json", descriptor().name}),
    ],
)
def test_logical_archive_additional_names_are_exact_disjoint_and_not_assets(additional):
    entry = descriptor()
    with BytesIO(zip_bytes([(entry.name, SVG), ("manifest.json", b"{}")])) as source:
        if additional != frozenset({"manifest.json"}):
            with pytest.raises(BackupZipError, match="ZIP_ASSET_ENTRIES"):
                AssetArchive(source, [entry], additional_names=additional)
        else:
            reader = AssetArchive(source, [entry], additional_names=additional)
            assert reader.read(entry.name) == SVG
            with pytest.raises(BackupZipError, match="ZIP_ENTRY_MISSING"):
                reader.read("manifest.json")


@pytest.mark.parametrize(
    "extra", [[], [("unrelated", b"x")], [(descriptor(OTHER).name, SVG)]]
)
def test_archive_exact_set_requires_all_snapshot_owners(extra):
    with BytesIO(zip_bytes(extra)) as source:
        with pytest.raises(BackupZipError, match="ZIP_ASSET_ENTRIES"):
            AssetArchive(source, [descriptor()])
    with BytesIO(zip_bytes([(descriptor().name, SVG), *extra])) as source:
        if extra:
            with pytest.raises(BackupZipError, match="ZIP_ASSET_ENTRIES"):
                AssetArchive(source, [descriptor()])
        else:
            AssetArchive(source, [descriptor()]).verify()


@pytest.mark.parametrize("owner", ["../outside", OWNER.upper(), OWNER + "/x", ""])
def test_invalid_owner_is_not_a_zip_or_filesystem_path(owner):
    with pytest.raises(ValueError):
        descriptor(owner)


@pytest.mark.parametrize("profile", ["svg-v2", "png-v1", "", None])
def test_unknown_or_mismatched_profile_is_rejected(profile):
    with pytest.raises(BackupZipError, match="ZIP_ASSET_PROFILE"):
        descriptor(profile=profile)


def test_descriptor_limits_and_inconsistent_same_hash_precede_io(tmp_path):
    files = AssetFiles(tmp_path)
    item = descriptor()
    with BytesIO() as output:
        with pytest.raises(BackupZipError, match="ZIP_LIMIT"):
            write_asset_archive(
                output, files, itertools.repeat(item), ZipLimits(entries=2)
            )
        assert output.getvalue() == b""
        mismatch = replace(item, blob=item.blob.model_copy(update={"width": 2}))
        with pytest.raises(BackupZipError, match="ZIP_ASSET_DESCRIPTION"):
            write_asset_archive(output, files, [item, mismatch])
        assert output.getvalue() == b""
    assert list(tmp_path.iterdir()) == []


@pytest.mark.parametrize("data", [SVG[:-1], SVG + b"x", SVG.replace(b"rect", b"path")])
def test_matching_zip_crc_is_not_blob_identity(data):
    item = descriptor()
    with BytesIO(zip_bytes([(item.name, data)])) as source:
        with pytest.raises((ImageInputError, BackupZipError)):
            AssetArchive(source, [item]).verify()


def test_matching_hash_is_not_format_validation():
    data = b'<svg width="1" height="1"><script/></svg>'
    item = descriptor(data=data)
    with BytesIO(zip_bytes([(item.name, data)])) as source:
        with pytest.raises(SvgValidationError, match="SVG_ELEMENT"):
            AssetArchive(source, [item]).verify()


def test_read_after_verify_revalidates_same_open_source():
    item = descriptor()
    # STORE makes in-place, same-length mutation independent of deflate.
    with BytesIO() as source:
        with zipfile.ZipFile(source, "w") as writer:
            writer.writestr(item.name, SVG)
        reader = AssetArchive(source, [item])
        reader.verify()
        source.seek(30 + len(item.name))
        source.write(b"x")
        with pytest.raises(BackupZipError, match="ZIP_CONTENT"):
            reader.read(item.name)
        with pytest.raises(BackupZipError, match="ZIP_ENTRY_MISSING"):
            reader.read(descriptor(OTHER).name)


@pytest.mark.parametrize("damage", ["missing", "corrupt", "symlink"])
def test_missing_or_corrupt_ready_file_cannot_be_exported(tmp_path, damage):
    item = descriptor()
    files = saved_files(tmp_path, [(item, SVG)])
    path = directory(tmp_path) / item.blob.sha256
    path.unlink()
    if damage == "corrupt":
        path.write_bytes(b"bad")
    elif damage == "symlink":
        target = tmp_path / "not-an-asset"
        target.write_bytes(SVG)
        path.symlink_to(target)
    with BytesIO() as output, pytest.raises((OSError, ImageInputError)):
        write_asset_archive(output, files, [item])
    assert not list(tmp_path.rglob(".install-*"))


def test_nonempty_output_is_preserved(tmp_path):
    for position in (0, 4):
        with BytesIO(b"keep") as output:
            output.seek(position)
            with pytest.raises(BackupZipError, match="ZIP_OUTPUT_NOT_EMPTY"):
                write_asset_archive(output, AssetFiles(tmp_path), [])
            assert output.getvalue() == b"keep"


def test_short_output_writes_do_not_return_success(tmp_path):
    class Broken(BytesIO):
        def write(self, data):
            return super().write(data[: max(0, len(data) - 1)])

    files = saved_files(tmp_path, [(descriptor(), SVG)])
    with Broken() as output, pytest.raises(BackupZipError):
        write_asset_archive(output, files, [descriptor()])


def test_archive_budget_checked_on_written_bytes(tmp_path):
    files = saved_files(tmp_path, [(descriptor(), SVG)])
    with BytesIO() as output, pytest.raises(BackupZipError, match="ZIP_LIMIT"):
        write_asset_archive(output, files, [descriptor()], ZipLimits(archive_bytes=30))


def test_failure_after_first_file_is_not_complete_backup(tmp_path):
    item, other = descriptor(), descriptor(OTHER)
    files = saved_files(tmp_path, [(item, SVG)])
    with BytesIO() as output:
        with pytest.raises(FileNotFoundError):
            write_asset_archive(output, files, [item, other])
        with pytest.raises(BackupZipError, match="ZIP_ASSET_ENTRIES"):
            AssetArchive(output, [item, other])
    assert files.read(OWNER, item.blob, item.profile) == SVG
