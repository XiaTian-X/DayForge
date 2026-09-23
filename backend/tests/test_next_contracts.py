"""Shared contract vectors; these do not claim live HTTP/DB integration."""

from copy import deepcopy
import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from src.v2.appearance import ICON_REFERENCE, IconPack, icon_allowed
from src.v2.one_time import (
    ContractModel,
    OneTimeIntent,
    OneTimeState,
    OneTimeTransitionError,
    advance_one_time,
)


FIXTURES = Path(__file__).resolve().parents[2] / "contracts" / "next"


def fixture(name):
    return json.loads((FIXTURES / name).read_text())


@pytest.mark.parametrize(
    "case", fixture("one-time-transitions.json"), ids=lambda case: case["name"]
)
def test_one_time_transitions(case):
    state = OneTimeState.model_validate(case["state"])
    intent = OneTimeIntent.model_validate(case["intent"])
    assert state.model_dump(mode="json") == case["state"]
    assert intent.model_dump(mode="json") == case["intent"]
    if "error" in case:
        with pytest.raises(OneTimeTransitionError) as failure:
            advance_one_time(state, intent, deleted=case["deleted"])
        assert str(failure.value) == case["error"]
    else:
        result = advance_one_time(state, intent, deleted=case["deleted"])
        assert result.model_dump(mode="json") == case["result"]
    assert state.model_dump(mode="json") == case["state"]


def test_pack_round_trip_and_boundary_budgets():
    source = fixture("icon-pack.json")
    pack = IconPack.model_validate(source)
    assert pack.model_dump(mode="json") == source
    source["assets"][0]["light"]["byte_length"] = 524_288
    source["assets"][1]["light"]["byte_length"] = 2_097_152
    source["assets"][1]["light"]["width"] = 1024
    assert IconPack.model_validate(source).model_dump(mode="json") == source


@pytest.mark.parametrize("case", fixture("icon-references.json"))
def test_icon_reference_roles_and_missing_metadata(case):
    icon = ICON_REFERENCE.validate_python(case["icon"])
    assert ICON_REFERENCE.dump_python(icon, mode="json") == case["icon"]
    pack = IconPack.model_validate(fixture("icon-pack.json"))
    index = case["asset_index"]
    asset = None if index is None else pack.assets[index]
    assert icon_allowed(icon, one_time=case["one_time"], asset=asset) == case["allowed"]


@pytest.mark.parametrize("case", fixture("invalid.json"), ids=lambda case: case["name"])
def test_invalid_contracts(case):
    first = fixture("one-time-transitions.json")[0]
    bases = {"pack": fixture("icon-pack.json"), **first}
    value = deepcopy(bases[case["base"]])
    cursor = value
    for key in case["path"][:-1]:
        cursor = cursor[key]
    cursor[case["path"][-1]] = case["value"]
    models: dict[str, type[ContractModel]] = {
        "pack": IconPack,
        "state": OneTimeState,
        "intent": OneTimeIntent,
    }
    with pytest.raises(ValidationError):
        models[case["base"]].model_validate(value)


def test_reducer_requires_replay_before_transition():
    case = fixture("one-time-transitions.json")[0]
    intent = OneTimeIntent.model_validate(case["intent"])
    result = advance_one_time(OneTimeState.model_validate(case["state"]), intent)
    with pytest.raises(OneTimeTransitionError, match="^TASK_STATE_CONFLICT$"):
        advance_one_time(result, intent)


def test_pack_total_size_and_asset_count_are_bounded():
    source = fixture("icon-pack.json")
    source["roles"] = {}
    source["placeholder_asset_id"] = None
    template = source["assets"][1]
    source["assets"] = []
    for index in range(33):
        asset = deepcopy(template)
        asset["asset_id"] = f"40000000-0000-0000-0000-{index:012x}"
        asset["dark"] = None
        asset["light"]["sha256"] = f"{index:064x}"
        asset["light"]["byte_length"] = 2_097_152
        source["assets"].append(asset)
    with pytest.raises(ValidationError, match="64 MiB"):
        IconPack.model_validate(source)
    source["assets"].pop()
    assert len(IconPack.model_validate(source).assets) == 32
    for asset in source["assets"]:
        asset["light"]["byte_length"] = 1
    for index in range(32, 129):
        asset = deepcopy(source["assets"][0])
        asset["asset_id"] = f"40000000-0000-0000-0000-{index:012x}"
        source["assets"].append(asset)
    with pytest.raises(ValidationError):
        IconPack.model_validate(source)


def test_unknown_icon_kind_and_noncanonical_identity_are_rejected():
    for value in (
        {"kind": "material", "icon": 53},
        {"kind": "role", "role": "operations.delete"},
        {"kind": "asset", "asset_id": "00000000-0000-0000-0000-00000000000A"},
    ):
        with pytest.raises(ValidationError):
            ICON_REFERENCE.validate_python(value)
