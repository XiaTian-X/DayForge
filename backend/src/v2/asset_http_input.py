"""Bounded transport input. No source, session or request escapes to a worker."""

import asyncio
import json
import math
from typing import Any

from fastapi import HTTPException
from starlette.requests import ClientDisconnect, Request

from src.v2.errors import DomainError
from src.storage.asset_io import AssetIoBusy, AssetIoClosed


JSON_LIMIT = 1_048_576
BODY_SECONDS = 30
REQUEST_SECONDS = 45


def invalid() -> DomainError:
    return DomainError("ASSET_INPUT_INVALID", "Invalid appearance request")


def header_values(request: Request, name: str) -> tuple[str, ...]:
    key = name.lower().encode("ascii")
    return tuple(
        value.decode("latin-1")
        for header, value in request.scope["headers"]
        if header.lower() == key
    )


def single_header(request: Request, name: str) -> str | None:
    values = header_values(request, name)
    if len(values) > 1:
        raise invalid()
    return values[0] if values else None


def content_length(
    request: Request, limit: int, *, media: str | tuple[str, ...]
) -> int | None:
    allowed = (media,) if isinstance(media, str) else media
    value = single_header(request, "content-type")
    parts = [] if value is None else [part.strip().lower() for part in value.split(";")]
    if (
        not parts
        or parts[0] not in allowed
        or (
            len(parts) > 1
            and (
                parts[0] != "application/json"
                or parts[1:] not in (["charset=utf-8"], ['charset="utf-8"'])
            )
        )
    ):
        raise invalid()
    encoding = single_header(request, "content-encoding")
    if encoding not in (None, "identity"):
        raise invalid()
    value = single_header(request, "content-length")
    if value is None:
        return None
    if not value.isascii() or not value.isdecimal() or len(value) > 10:
        raise invalid()
    length = int(value)
    if length > limit:
        raise DomainError("ASSET_INPUT_TOO_LARGE", "Appearance input exceeds its limit")
    return length


async def receive_bytes(
    request: Request, limit: int, *, media: str | tuple[str, ...]
) -> bytes:
    length = content_length(request, limit, media=media)
    output = bytearray()
    empty = 0
    chunks = 0
    async with asyncio.timeout(BODY_SECONDS):
        while True:
            message = await request.receive()
            if message["type"] == "http.disconnect":
                raise ClientDisconnect()
            if message["type"] != "http.request":
                raise invalid()
            block = message.get("body", b"")
            if len(block) > limit - len(output):
                raise DomainError(
                    "ASSET_INPUT_TOO_LARGE", "Appearance input exceeds its limit"
                )
            output.extend(block)
            chunks += 1
            empty = empty + 1 if not block else 0
            if empty > 64:
                raise invalid()
            if not message.get("more_body", False):
                break
            if chunks % 16 == 0:
                await asyncio.sleep(0)
    if length is not None and length != len(output):
        raise invalid()
    return bytes(output)


def parse_json(data: bytes) -> Any:
    """Bound allocation BEFORE json.loads, including syntax/error object budgets."""
    try:
        source = data.decode("utf-8", errors="strict")
        depth = punctuation = 0
        quoted = escaped = False
        for char in source:
            if quoted:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    quoted = False
            elif char == '"':
                quoted = True
            elif char in "[{":
                depth += 1
                punctuation += 1
            elif char in "}]":
                depth -= 1
                punctuation += 1
            elif char in ",:":
                punctuation += 1
            if depth > 16 or depth < 0 or punctuation > 32_768:
                raise ValueError

        def pairs(items):
            result = {}
            for key, value in items:
                if key in result:
                    raise ValueError
                result[key] = value
            return result

        def finite(value):
            result = float(value)
            if not math.isfinite(result):
                raise ValueError
            return result

        def constant(_value):
            raise ValueError

        value = json.loads(
            source, object_pairs_hook=pairs, parse_float=finite, parse_constant=constant
        )

        def strings(item):
            if isinstance(item, str):
                item.encode("utf-8", errors="strict")
            elif isinstance(item, dict):
                for key, child in item.items():
                    strings(key)
                    strings(child)
            elif isinstance(item, list):
                for child in item:
                    strings(child)

        strings(value)
        if not isinstance(value, dict):
            raise ValueError
        return value
    except (ValueError, UnicodeError, RecursionError) as error:
        raise invalid() from error


class AppearanceRequest(Request):
    async def body(self) -> bytes:
        if not hasattr(self, "_body"):
            try:
                self._body = await receive_bytes(
                    self, JSON_LIMIT, media="application/json"
                )
            except DomainError as error:
                raise HTTPException(
                    413 if error.code == "ASSET_INPUT_TOO_LARGE" else 422,
                    detail={"code": error.code, "message": error.message},
                ) from error
            except (TimeoutError, ClientDisconnect) as error:
                raise HTTPException(
                    503, detail={"code": "ASSET_CONTENT_UNAVAILABLE"}
                ) from error
        return self._body

    async def json(self) -> Any:
        if not hasattr(self, "_json"):
            data = await self.body()
            try:
                self._json = await self.app.state.appearance.parser.run(
                    lambda: parse_json(data)
                )
            except DomainError as error:
                raise HTTPException(
                    422, detail={"code": error.code, "message": error.message}
                ) from error
            except (AssetIoBusy, AssetIoClosed) as error:
                raise HTTPException(
                    503, detail={"code": "ASSET_CONTENT_UNAVAILABLE"}
                ) from error
        return self._json
