"""Complete, resolved theme file contract; no UI activation or seed regeneration."""

from typing import Literal

from pydantic import Field, field_validator, model_validator

from src.v2.contract_types import (
    ContractModel,
    PublicId,
    RgbColor,
    exact_integer,
    visible_name,
)


MATERIAL_ROLES = frozenset(
    {
        "primary",
        "on_primary",
        "primary_container",
        "on_primary_container",
        "inverse_primary",
        "secondary",
        "on_secondary",
        "secondary_container",
        "on_secondary_container",
        "tertiary",
        "on_tertiary",
        "tertiary_container",
        "on_tertiary_container",
        "background",
        "on_background",
        "surface",
        "on_surface",
        "surface_variant",
        "on_surface_variant",
        "surface_tint",
        "inverse_surface",
        "inverse_on_surface",
        "error",
        "on_error",
        "error_container",
        "on_error_container",
        "outline",
        "outline_variant",
        "scrim",
        "surface_bright",
        "surface_dim",
        "surface_container",
        "surface_container_high",
        "surface_container_highest",
        "surface_container_low",
        "surface_container_lowest",
    }
)
STATUS_ROLES = frozenset(
    {
        "success",
        "on_success",
        "success_container",
        "on_success_container",
        "warning",
        "on_warning",
        "warning_container",
        "on_warning_container",
        "pending",
        "on_pending",
        "pending_container",
        "on_pending_container",
    }
)
CHART_ROLES = frozenset(
    {
        "line",
        "target",
        "grid",
        "selection",
    }
)


class ThemePalette(ContractModel):
    material: dict[str, RgbColor]
    status: dict[str, RgbColor]
    chart: dict[str, RgbColor]

    @model_validator(mode="after")
    def complete_roles(self):
        for values, required in (
            (self.material, MATERIAL_ROLES),
            (self.status, STATUS_ROLES),
            (self.chart, CHART_ROLES),
        ):
            if set(values) != required:
                raise ValueError(
                    "palette must contain exactly the versioned color roles"
                )
        return self


class ThemeDefinition(ContractModel):
    format: Literal["dayforge.theme"]
    format_version: Literal[1]
    theme_id: PublicId
    revision: int = Field(ge=1, le=2_147_483_647)
    name: str = Field(min_length=1, max_length=80)
    generator_id: str | None = Field(pattern=r"^[a-z][a-z0-9.-]{0,63}$")
    seed: RgbColor | None
    light: ThemePalette
    dark: ThemePalette

    @field_validator("format_version", mode="before")
    @classmethod
    def exact_version_type(cls, value):
        return exact_integer(value)

    @model_validator(mode="after")
    def valid_metadata(self):
        visible_name(self.name)
        if (self.generator_id is None) != (self.seed is None):
            raise ValueError("generator and seed must both be specified or null")
        return self
