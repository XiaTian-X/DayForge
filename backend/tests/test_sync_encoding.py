"""Stored JSON and request identity must survive module extraction unchanged."""

from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
import hashlib
import json
from uuid import UUID

import pytest

from src.v2.schemas import SyncOperationRequest
from src.v2.encoding import canonical_json, jsonable_utc, operation_hash, parse_json


def test_canonical_json_keeps_unicode_numeric_decimals_and_stable_key_order():
    value = {
        "z": [Decimal("1"), Decimal("1.25")],
        "名称": "喝水",
        "a": {"y": None, "x": True},
    }
    expected = '{"a":{"x":true,"y":null},"z":[1,1.25],"名称":"喝水"}'
    assert canonical_json(value) == expected
    assert canonical_json(dict(reversed(list(value.items())))) == expected
    assert parse_json(expected) == {
        "a": {"x": True, "y": None},
        "z": [1, 1.25],
        "名称": "喝水",
    }


def test_nested_utc_encoding_preserves_instant_microseconds_and_json_leaf_types():
    instant = datetime(2026, 9, 14, 1, 2, 3, 456789)
    value = {
        "naive_utc": instant,
        "nested": (
            {"offset": instant.replace(tzinfo=timezone(timedelta(hours=8)))},
            None,
        ),
        "date": date(2026, 9, 14),
        "uuid": UUID("11111111-1111-4111-8111-111111111111"),
        "decimal": Decimal("2.5"),
    }
    assert jsonable_utc(value) == {
        "naive_utc": "2026-09-14T01:02:03.456789Z",
        "nested": [{"offset": "2026-09-13T17:02:03.456789Z"}, None],
        "date": "2026-09-14",
        "uuid": "11111111-1111-4111-8111-111111111111",
        "decimal": 2.5,
    }
    assert value["naive_utc"] is instant
    assert isinstance(value["nested"], tuple)


@pytest.mark.parametrize("value", ["", "null", "[]", "false", "42", '"string"'])
def test_stored_non_object_json_has_an_empty_object_fallback(value):
    assert parse_json(value) == {}


def test_malformed_stored_json_is_not_silently_accepted():
    with pytest.raises(json.JSONDecodeError):
        parse_json('{"unfinished":')


def test_operation_identity_hashes_the_original_wire_request_not_a_normalized_edit():
    operation = SyncOperationRequest.model_validate(
        {
            "operation_id": "11111111-1111-4111-8111-111111111111",
            "entity_type": "metric",
            "entity_uuid": "22222222-2222-4222-8222-222222222222",
            "action": "upsert",
            "base_revision": 1,
            "payload": {"name": "体重", "unit": "kg", "target_value": "70"},
        }
    )
    expected_wire = (
        '{"action":"upsert","base_revision":1,'
        '"entity_type":"metric","entity_uuid":"22222222-2222-4222-8222-222222222222",'
        '"operation_id":"11111111-1111-4111-8111-111111111111",'
        '"payload":{"name":"体重","target_value":"70","unit":"kg"}}'
    )
    assert (
        operation_hash(operation)
        == hashlib.sha256(expected_wire.encode("utf-8")).hexdigest()
    )
    reordered = operation.model_copy(
        update={"payload": dict(reversed(list(operation.payload.items())))}
    )
    assert operation_hash(reordered) == operation_hash(operation)
    for update in (
        {"base_revision": 2},
        {"payload": {**operation.payload, "description": ""}},
        {"payload": {**operation.payload, "target_value": 70}},
    ):
        assert operation_hash(operation.model_copy(update=update)) != operation_hash(
            operation
        )
