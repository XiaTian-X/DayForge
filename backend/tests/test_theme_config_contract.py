"""Metadata contracts only; file installation and database import have separate gates."""

from copy import deepcopy
import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from src.v2.appearance import IconPack
from src.v2.config_contract import ConfigBundle
from src.v2.contract_types import ContractModel
from src.v2.theme_contract import ThemeDefinition


FIXTURES = Path(__file__).resolve().parents[2] / "contracts" / "next"


def fixture(name):
    return json.loads((FIXTURES / name).read_text())


def test_theme_has_complete_independent_light_and_dark_palettes():
    source = fixture("theme.json")
    theme = ThemeDefinition.model_validate(source)
    assert theme.model_dump(mode="json") == source
    assert len(theme.light.material) == len(theme.dark.material) == 36
    assert len(theme.light.status) == 12
    assert len(theme.light.chart) == 4
    assert theme.light.material["surface_container_highest"] == "#E5E3E7"
    assert theme.dark.material["surface_container_highest"] == "#343438"
    assert theme.dark.chart["line"] == "#AFC6FF"
    source["generator_id"] = "unknown-future-generator-v99"
    source["seed"] = "#123456"
    saved = ThemeDefinition.model_validate(source)
    assert saved.light == theme.light and saved.dark == theme.dark


def test_config_preserves_all_modes_and_rejects_runtime_history():
    source = fixture("config.json")
    bundle = ConfigBundle.model_validate(source)
    assert bundle.model_dump(mode="json") == source
    assert len(bundle.nodes) == 7
    activities = [node.activity for node in bundle.nodes if node.activity is not None]
    assert [(item.tracking_mode, item.is_countdown) for item in activities] == [
        ("check", False),
        ("count", False),
        ("count", True),
        ("duration", False),
        ("duration", True),
        ("check", False),
    ]
    assert {item.schedule.type for item in activities} == {
        "daily",
        "weekly",
        "monthly",
        "interval",
        "once",
    }
    assert activities[-1].completion_policy == "one_and_done"
    assert bundle.nodes[2].appearance.accent_color == "#802196F3"
    assert bundle.metrics[0].aggregation_type == "by_time"
    assert bundle.links[0].prompt_on_complete is True
    assert bundle.unresolved_roles == ["goal.default", "metric.weight"]


@pytest.mark.parametrize(
    "case", fixture("theme-config-invalid.json"), ids=lambda case: case["name"]
)
def test_shared_invalid_inputs(case):
    source = fixture(case["base"] + ".json")
    cursor = source
    for key in case["path"][:-1]:
        cursor = cursor[key]
    cursor[case["path"][-1]] = case["value"]
    models: dict[str, type[ContractModel]] = {
        "theme": ThemeDefinition,
        "config": ConfigBundle,
        "icon-pack": IconPack,
    }
    with pytest.raises(ValidationError):
        models[case["base"]].model_validate(source)


def test_empty_config_is_explicit_and_not_a_missing_fields_default():
    empty = {
        "format": "dayforge.config",
        "format_version": 2,
        "nodes": [],
        "metrics": [],
        "links": [],
        "icon_pack": None,
        "unresolved_roles": [],
        "themes": [],
    }
    assert ConfigBundle.model_validate(empty).model_dump(mode="json") == empty
    for field in empty:
        missing = deepcopy(empty)
        del missing[field]
        with pytest.raises(ValidationError):
            ConfigBundle.model_validate(missing)


def test_missing_color_role_cannot_fall_back_to_library_defaults():
    source = fixture("theme.json")
    del source["light"]["material"]["surface_dim"]
    with pytest.raises(ValidationError):
        ThemeDefinition.model_validate(source)


def test_duplicate_links_themes_and_package_budgets():
    base = fixture("config.json")
    duplicate = deepcopy(base)
    duplicate["links"].append({**duplicate["links"][0], "key": "another_link"})
    with pytest.raises(ValidationError, match="duplicate metric links"):
        ConfigBundle.model_validate(duplicate)
    duplicate = deepcopy(base)
    duplicate["themes"].append(duplicate["themes"][0])
    with pytest.raises(ValidationError, match="duplicate theme version"):
        ConfigBundle.model_validate(duplicate)
    for field, count in (
        ("nodes", 1001),
        ("metrics", 1001),
        ("links", 5001),
        ("themes", 17),
    ):
        oversized = deepcopy(base)
        oversized[field] = [oversized[field][0]] * count
        with pytest.raises(ValidationError):
            ConfigBundle.model_validate(oversized)


def test_metadata_serialization_does_not_add_account_or_completion_state():
    bundle = ConfigBundle.model_validate(fixture("config.json"))
    raw = bundle.model_dump_json()
    for forbidden in (
        "owner_id",
        "operation_id",
        "completion_event_uuid",
        "sync_epoch",
        "created_at",
    ):
        assert forbidden not in raw
