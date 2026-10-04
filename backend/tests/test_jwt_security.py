"""Bounded JWT rejection, upstream security regressions and old-token compatibility."""

import base64
from datetime import timedelta
import hashlib
import hmac
import json
from typing import Any

import jwt
import pytest
from sqlalchemy import event
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import select

from src.auth.models import User
from src.auth.service import (
    DATABASE_INTEGER_MAX,
    DATABASE_INTEGER_MIN,
    MAX_JWT_JSON_DEPTH,
    MAX_JWT_LENGTH,
    token_account_identity,
    verify_token,
)
from src.config import settings
from src.time_utils import utc_now
from tests.account_fixtures import account_password_hash


FIXTURE_KEY = "jwt-compatibility-fixture-key-not-for-production"
# Frozen segments produced by the previous locked library, not re-signed by
# the upgraded decoder. This key is synthetic and never a deployment secret.
LEGACY_HEADER = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
LEGACY_SEGMENTS = {
    "access": (
        "eyJzdWIiOiIxNyIsInZlciI6NCwidHlwZSI6ImFjY2VzcyIsImV4cCI6MjUyNDYwODAwMH0",
        "18td-9SwPW_mK2MOifG7vqoGVmfFCG0W7kzbmiL-ON0",
    ),
    "refresh": (
        "eyJzdWIiOiIxNyIsInZlciI6NCwidHlwZSI6InJlZnJlc2giLCJleHAiOjI1MjQ2MDgwMDB9",
        "8q1HPHO68IjrA73HmOEkzUwj4ll_xn0fBxkdVp5tBQE",
    ),
}


@pytest.fixture(autouse=True)
def isolated_signing_key(monkeypatch):
    monkeypatch.setattr(settings, "JWT_SECRET_KEY", FIXTURE_KEY)
    monkeypatch.setattr(settings, "JWT_ALGORITHM", "HS256")


def _claims(kind: str = "access") -> dict[str, Any]:
    return {"sub": "17", "ver": 4, "type": kind, "exp": 2524608000}


def _token(claims: dict[str, Any]) -> str:
    return jwt.encode(claims, FIXTURE_KEY, algorithm="HS256")


def _legacy(kind: str) -> str:
    return ".".join((LEGACY_HEADER, *LEGACY_SEGMENTS[kind]))


def _segment(data: bytes) -> bytes:
    return base64.urlsafe_b64encode(data).rstrip(b"=")


def _signed_json(header: bytes, payload: bytes) -> str:
    signing_input = _segment(header) + b"." + _segment(payload)
    signature = hmac.digest(FIXTURE_KEY.encode(), signing_input, hashlib.sha256)
    return (signing_input + b"." + _segment(signature)).decode("ascii")


@pytest.mark.parametrize("kind", ["access", "refresh"])
def test_upgrade_preserves_frozen_previous_library_tokens(kind):
    assert verify_token(_legacy(kind)) == _claims(kind)


@pytest.mark.parametrize("claim", ["exp", "iat", "nbf"])
@pytest.mark.parametrize(
    "value", [None, [], {}, float("inf"), float("-inf"), float("nan"), "invalid"]
)
def test_malformed_numeric_dates_are_rejected_without_parser_exceptions(claim, value):
    assert verify_token(_token(_claims() | {claim: value})) is None


@pytest.mark.parametrize("claim", ["iat", "nbf"])
def test_future_numeric_dates_still_rejected(claim):
    future = int((utc_now() + timedelta(hours=1)).timestamp())
    assert verify_token(_token(_claims() | {claim: future})) is None


def test_expiration_is_required_and_expired_tokens_still_rejected():
    claims = _claims()
    del claims["exp"]
    assert verify_token(_token(claims)) is None
    expired = int((utc_now() - timedelta(hours=1)).timestamp())
    assert verify_token(_token(_claims() | {"exp": expired})) is None


@pytest.mark.parametrize("token", [None, False, [], "", "\ud800", "é", "x" * 16385])
def test_input_budget_rejects_before_decoder_is_called(monkeypatch, token):
    def unexpected(*args, **kwargs):
        raise AssertionError("decoder must not run for invalid input")

    monkeypatch.setattr(jwt, "decode", unexpected)
    assert verify_token(token) is None


def test_valid_token_at_exact_budget_is_accepted_and_growth_rejected(monkeypatch):
    token = ""
    estimate = (MAX_JWT_LENGTH - len(_token(_claims() | {"padding": ""}))) * 3 // 4
    for size in range(estimate - 3, estimate + 5):
        candidate = _token(_claims() | {"padding": "x" * size})
        if len(candidate) == MAX_JWT_LENGTH:
            token = candidate
            break
    assert len(token) == MAX_JWT_LENGTH
    decoded = verify_token(token)
    assert decoded is not None and decoded["sub"] == "17"
    oversized = _token(_claims() | {"padding": "x" * (size + 3)})
    assert len(oversized) > MAX_JWT_LENGTH

    def unexpected(*args, **kwargs):
        raise AssertionError("oversized signed token must be rejected before decoding")

    monkeypatch.setattr(jwt, "decode", unexpected)
    assert verify_token(oversized) is None


@pytest.mark.parametrize("location", ["header", "payload"])
@pytest.mark.parametrize("raw", [b"null", b"[]", b"0", b'"text"', b"{", b"\xff"])
def test_signed_invalid_json_shapes_fail_closed(location, raw):
    header = b'{"alg":"HS256","typ":"JWT"}'
    payload = json.dumps(_claims()).encode()
    assert (
        verify_token(
            _signed_json(
                raw if location == "header" else header,
                raw if location == "payload" else payload,
            )
        )
        is None
    )


@pytest.mark.parametrize("location", ["header", "payload"])
def test_deep_json_is_rejected_as_credentials_not_uncaught_recursion(location):
    nested = b"[" * 1500 + b"0" + b"]" * 1500
    header = b'{"alg":"HS256","x":' + nested + b"}"
    payload = (
        b'{"sub":"17","ver":4,"type":"access","exp":2524608000,"x":' + nested + b"}"
    )
    token = _signed_json(
        header if location == "header" else b'{"alg":"HS256"}',
        payload if location == "payload" else json.dumps(_claims()).encode(),
    )
    assert len(token) < MAX_JWT_LENGTH
    assert verify_token(token) is None


@pytest.mark.parametrize("location", ["header", "payload"])
def test_exact_depth_budget_accepts_and_one_more_level_never_enters_decoder(
    monkeypatch, location
):
    def at_depth(depth):
        nested = b"[" * (depth - 1) + b"0" + b"]" * (depth - 1)
        header = b'{"alg":"HS256","x":' + nested + b"}"
        payload = (
            b'{"sub":"17","ver":4,"type":"access","exp":2524608000,"x":' + nested + b"}"
        )
        return _signed_json(
            header if location == "header" else b'{"alg":"HS256"}',
            payload if location == "payload" else json.dumps(_claims()).encode(),
        )

    accepted = verify_token(at_depth(MAX_JWT_JSON_DEPTH))
    assert accepted is not None and accepted["sub"] == "17"

    def unexpected(*args, **kwargs):
        raise AssertionError("over-depth JSON must not enter recursive decoding")

    monkeypatch.setattr(jwt, "decode", unexpected)
    assert verify_token(at_depth(MAX_JWT_JSON_DEPTH + 1)) is None


def test_quotes_escapes_and_utf8_strings_are_not_counted_as_nesting():
    note = '习惯 [ { \\" quoted: " } ]' * 100
    claims = _claims() | {"note": note}
    token = _signed_json(
        b'{"alg":"HS256"}', json.dumps(claims, ensure_ascii=False).encode("utf-8")
    )
    assert verify_token(token) == claims


@pytest.mark.parametrize("location", ["header", "payload"])
@pytest.mark.parametrize(
    "encoding", ["utf-16", "utf-32", "utf-16-le", "utf-16-be", "utf-32-le", "utf-32-be"]
)
def test_json_components_require_utf8(location, encoding):
    assert (
        verify_token(
            _signed_json(
                '{"alg":"HS256"}'.encode(encoding if location == "header" else "utf-8"),
                json.dumps(_claims()).encode(
                    encoding if location == "payload" else "utf-8"
                ),
            )
        )
        is None
    )


def test_escaped_nul_in_a_valid_string_remains_accepted():
    claims = _claims() | {"note": "escaped\x00value"}
    assert verify_token(_token(claims)) == claims


def test_algorithms_signature_and_compact_encoding_cannot_be_relaxed():
    valid = _legacy("access")
    assert verify_token(valid) is not None
    assert verify_token(valid + "=") is not None  # Legitimate Base64URL padding.
    assert verify_token(valid + "!!!!") is None
    alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    index = alphabet.index(valid[-1])
    assert index & 3 == 0
    alias = valid[:-1] + alphabet[index | 1]
    assert base64.urlsafe_b64decode(
        alias.rsplit(".", 1)[1] + "="
    ) == base64.urlsafe_b64decode(valid.rsplit(".", 1)[1] + "=")
    assert verify_token(alias) is None
    assert verify_token(jwt.encode(_claims(), "", algorithm="none")) is None
    assert (
        verify_token(jwt.encode(_claims(), FIXTURE_KEY * 2, algorithm="HS512")) is None
    )
    assert (
        verify_token(
            jwt.encode(_claims(), "other-synthetic-key" * 3, algorithm="HS256")
        )
        is None
    )


@pytest.mark.parametrize(
    "header",
    [
        {"alg": []},
        {"alg": {}},
        {"alg": None},
        {"alg": "HS256", "crit": ["unknown"]},
        {"alg": "HS256", "b64": False, "crit": ["b64"]},
    ],
)
def test_unsupported_header_parameters_are_not_trusted(header):
    assert (
        verify_token(
            _signed_json(json.dumps(header).encode(), json.dumps(_claims()).encode())
        )
        is None
    )


def test_upstream_options_mapping_is_not_poisoned_by_insecure_decode():
    token = _token(_claims() | {"exp": 1})
    options: jwt.types.Options = {"verify_signature": False}
    assert jwt.decode(token, options=options)["exp"] == 1
    assert options == {"verify_signature": False}
    options["verify_signature"] = True
    with pytest.raises(jwt.ExpiredSignatureError):
        jwt.decode(token, FIXTURE_KEY, algorithms=["HS256"], options=options)
    assert verify_token(token) is None


def test_production_decodes_have_fixed_algorithm_and_independent_options(monkeypatch):
    original = jwt.decode
    captured = []

    def spy(*args, **kwargs):
        captured.append(kwargs)
        return original(*args, **kwargs)

    monkeypatch.setattr(jwt, "decode", spy)
    assert verify_token(_legacy("access")) is not None
    assert verify_token(_legacy("refresh")) is not None
    assert len(captured) == 2
    for call in captured:
        assert call["algorithms"] == ["HS256"]
        assert call["options"] == {"require": ["exp"]}
    assert captured[0]["options"] is not captured[1]["options"]


def test_unrelated_programming_errors_are_not_converted_to_invalid_credentials(
    monkeypatch,
):
    def broken(*args, **kwargs):
        raise RuntimeError("synthetic internal bug")

    monkeypatch.setattr(jwt, "decode", broken)
    with pytest.raises(RuntimeError, match="synthetic internal bug"):
        verify_token(_legacy("access"))


@pytest.mark.parametrize(
    "subject", [None, [], {}, 17, "invalid", str(2**63), str(-(2**63) - 1), "9" * 5000]
)
def test_database_subject_rejected_before_binding(subject):
    assert token_account_identity(_claims() | {"sub": subject}) is None


@pytest.mark.parametrize(
    "version", [None, True, False, [], {}, "4", 4.0, 2**63, -(2**63) - 1]
)
def test_database_version_is_an_actual_bounded_integer(version):
    assert token_account_identity(_claims() | {"ver": version}) is None


@pytest.mark.parametrize("value", [DATABASE_INTEGER_MIN, 0, 4, DATABASE_INTEGER_MAX])
def test_representable_integer_claims_retain_previous_semantics(value):
    assert token_account_identity({"sub": str(value), "ver": value}) == (value, value)
    assert token_account_identity({"sub": " 00017 ", "ver": value}) == (17, value)


def _bad_token(kind: str, case: str) -> str:
    claims = _claims(kind)
    if case == "missing-exp":
        del claims["exp"]
    elif case == "null-exp":
        claims["exp"] = None
    elif case == "infinite-exp":
        claims["exp"] = float("inf")
    elif case == "boolean-version":
        claims["ver"] = True
    elif case == "huge-version":
        claims["ver"] = 2**63
    elif case == "huge-subject":
        claims["sub"] = str(2**63)
    elif case == "deep-header":
        return _signed_json(
            b'{"alg":"HS256","x":' + b"[" * 1500 + b"0" + b"]" * 1500 + b"}",
            json.dumps(claims).encode(),
        )
    elif case == "oversized":
        claims["padding"] = "x" * MAX_JWT_LENGTH
    else:
        raise AssertionError("unknown synthetic test case")
    return _token(claims)


async def _seed(engine, version: int = 4):
    async with AsyncSession(engine, expire_on_commit=False) as session:
        session.add(
            User(
                id=17,
                username="jwt-boundary",
                password_hash=account_password_hash(),
                auth_version=version,
            )
        )
        await session.commit()


@pytest.mark.parametrize("kind", ["access", "refresh"])
@pytest.mark.parametrize(
    "case",
    [
        "missing-exp",
        "null-exp",
        "infinite-exp",
        "boolean-version",
        "huge-version",
        "huge-subject",
        "deep-header",
        "oversized",
    ],
)
async def test_real_http_rejects_bad_credentials_without_500_or_database_writes(
    runtime_client, runtime_engine, kind, case
):
    # True == 1 used to pass isinstance(ver, int); reproduce with an actual
    # active account at that version, not an already-mismatched fixture.
    version = 1 if case == "boolean-version" else 4
    await _seed(runtime_engine, version)
    statements = []

    def observe(_connection, _cursor, statement, _parameters, _context, _many):
        statements.append(statement)

    event.listen(runtime_engine.sync_engine, "before_cursor_execute", observe)
    try:
        token = _bad_token(kind, case)
        if kind == "access":
            response = await runtime_client.get(
                "/api/v1/auth/users/me", headers={"Authorization": "Bearer " + token}
            )
            assert response.headers["www-authenticate"] == "Bearer"
            assert response.json() == {"detail": "Could not validate credentials"}
        else:
            response = await runtime_client.post(
                "/api/v1/auth/refresh", json={"refresh_token": token}
            )
            assert response.json() == {"detail": "Invalid refresh token"}
        assert response.status_code == 401
    finally:
        event.remove(runtime_engine.sync_engine, "before_cursor_execute", observe)
    assert not statements  # Invalid claims never even query an account.
    async with AsyncSession(runtime_engine) as session:
        user = (await session.execute(select(User))).scalar_one()
        assert user.id == 17 and user.auth_version == version and user.is_active


async def test_real_http_accepts_legacy_access_and_refresh_without_changing_identity(
    runtime_client, runtime_engine
):
    await _seed(runtime_engine)
    me = await runtime_client.get(
        "/api/v1/auth/users/me",
        headers={"Authorization": "Bearer " + _legacy("access")},
    )
    assert me.status_code == 200 and me.json()["id"] == 17
    refreshed = await runtime_client.post(
        "/api/v1/auth/refresh", json={"refresh_token": _legacy("refresh")}
    )
    assert (
        refreshed.status_code == 200
        and refreshed.json()["user_id"] == me.json()["public_id"]
    )
    for field, kind in (("access_token", "access"), ("refresh_token", "refresh")):
        payload = verify_token(refreshed.json()[field])
        assert (
            payload is not None
            and payload["sub"] == "17"
            and payload["ver"] == 4
            and payload["type"] == kind
        )
