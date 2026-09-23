"""Canonical immutable metadata shared by storage and declaration validation."""

import json

from src.v2.appearance import IconAsset, IconPack


def canonical_metadata(value: IconAsset | IconPack) -> str:
    return json.dumps(
        value.model_dump(mode="json"),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    )
