"""Strict, platform-neutral values shared by the next protocol/file contracts."""

from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field


PublicId = Annotated[
    str,
    Field(pattern=r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"),
]
RgbColor = Annotated[str, Field(pattern=r"^#[0-9A-Fa-f]{6}$")]
AccentColor = Annotated[str, Field(pattern=r"^#(?:[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")]


class ContractModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)


def visible_name(value: str, maximum: int = 80) -> str:
    if (
        not value.strip()
        or len(value) > maximum
        or any(ord(char) < 32 or 127 <= ord(char) <= 159 for char in value)
    ):
        raise ValueError("name must contain visible text without control characters")
    return value


def exact_integer(value):
    if type(value) is not int:
        raise ValueError("expected a JSON integer")
    return value
