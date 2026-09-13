"""Compatibility contract for token response serialization, independent of config syntax."""

from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import pytest
from pydantic import TypeAdapter, ValidationError

from src.tokens.models import ApiToken
from src.tokens.schemas import TokenListResponse, TokenResponse


@pytest.mark.parametrize("schema", [TokenResponse, TokenListResponse])
@pytest.mark.parametrize("source_type", [dict, SimpleNamespace])
@pytest.mark.parametrize(
    ("created_at", "serialized"),
    [
        (datetime(2026, 9, 13, 8, 12, 34), "2026-09-13T08:12:34"),
        (datetime(2026, 9, 13, 8, 12, 34, tzinfo=timezone.utc), "2026-09-13T08:12:34Z"),
        (
            datetime(2026, 9, 13, 8, 12, 34, tzinfo=timezone(timedelta(hours=8))),
            "2026-09-13T08:12:34+08:00",
        ),
    ],
)
def test_response_preserves_fields_dates_and_filters_internal_data(
    schema, source_type, created_at, serialized
):
    values = {
        "id": 7,
        "name": "家庭同步",
        "prefix": "public-part",
        "token": "test-only-placeholder",
        "created_at": created_at,
        "last_used_at": created_at,
        "expires_at": created_at,
        "token_hash": "internal-hash-must-not-leak",
        "user_id": 42,
    }
    response = schema.model_validate(source_type(**values))
    expected = {
        "id": 7,
        "name": "家庭同步",
        "prefix": "public-part",
        "created_at": serialized,
        "last_used_at": serialized,
        "expires_at": serialized,
    }
    if schema is TokenResponse:
        expected["token"] = "test-only-placeholder"
    assert response.model_dump(mode="json") == expected
    assert response.created_at == created_at
    assert response.created_at.tzinfo == created_at.tzinfo


@pytest.mark.parametrize("schema", [TokenResponse, TokenListResponse])
def test_optional_dates_remain_null_and_creation_fields_remain_required(schema):
    values = {
        "id": 1,
        "name": "test",
        "prefix": "public-part",
        "created_at": datetime(2026, 9, 13),
    }
    if schema is TokenResponse:
        with pytest.raises(ValidationError) as error:
            schema.model_validate(values)
        assert [(e["loc"], e["type"]) for e in error.value.errors()] == [
            (("token",), "missing")
        ]
        values["token"] = "test-only-placeholder"

    response = schema.model_validate(values)
    assert response.model_dump(mode="json")["last_used_at"] is None
    assert response.model_dump(mode="json")["expires_at"] is None
    del values["created_at"]
    with pytest.raises(ValidationError) as error:
        schema.model_validate(values)
    assert [(e["loc"], e["type"]) for e in error.value.errors()] == [
        (("created_at",), "missing")
    ]


def test_sqlmodel_list_serialization_exposes_only_public_fields():
    row = ApiToken(
        id=7,
        user_id=42,
        name="test",
        prefix="public-part",
        token_hash="internal-hash-must-not-leak",
        created_at=datetime(2026, 9, 13),
    )
    adapter = TypeAdapter(list[TokenListResponse])
    responses = adapter.validate_python([row])
    assert adapter.dump_python(responses, mode="json") == [
        {
            "id": 7,
            "name": "test",
            "prefix": "public-part",
            "created_at": "2026-09-13T00:00:00",
            "last_used_at": None,
            "expires_at": None,
        }
    ]
