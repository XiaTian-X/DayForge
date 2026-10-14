"""Actual owned peer lifecycle; does not substitute for physical Android recovery."""

import asyncio
import json

from httpx import AsyncClient

from tests.android_replica_recovery_server import (
    ACCOUNT,
    ADDED,
    ASSET,
    CHANGED,
    INSTALLATION,
    MISSING,
    PACK,
    USERNAME,
    recovery_servers,
)
from tests.test_challenge_profile import batch, snapshot
from tests.test_next_structural_sync import metric, operation
from tests.test_product_restore_http import accepted, authenticated


async def write_new_metric(ready):
    async with AsyncClient(base_url=ready["restored_url"]) as client:
        _, headers, device = await authenticated(
            client, USERNAME, INSTALLATION, original_device=ready["device_id"]
        )
        body = metric() | {"name": "Recovery new metric"}
        item = operation(
            body,
            identity="7c160000-0000-4000-8000-000000000401",
            kind="metric",
            revision=None,
        )
        await accepted(client, headers, batch(device, [item]))
        state = await snapshot(client, headers, device)
        assert {
            row["entity_uuid"]
            for row in state["changes"]
            if row["entity_type"] == "plan_node"
        } == {CHANGED, ADDED}
        assert any(
            row["entity_type"] == "metric"
            and row["payload"]["name"] == "Recovery new metric"
            for row in state["changes"]
        )


def test_actual_stopped_backup_reopens_same_instance_with_new_epoch_and_owned_assets(
    tmp_path,
):
    directory = tmp_path / "recovery"
    with recovery_servers(directory) as ready:
        assert ready["installation_id"] == INSTALLATION
        assert ready["account_id"] == ACCOUNT
        assert ready["changed_id"] == CHANGED
        assert ready["missing_id"] == MISSING
        assert ready["added_id"] == ADDED
        assert ready["asset_id"] == ASSET
        assert ready["pack_id"] == PACK
        assert ready["source_epoch"] != ready["restored_epoch"]
        assert ready["source_url"] != ready["restored_url"]
        assert ready["old_jwt_rejected"] and ready["wrong_owner_denied"]
        assert ready["same_signing_key"]
        assert not (directory / "complete.json").exists()
        asyncio.run(write_new_metric(ready))
    proof = json.loads((directory / "complete.json").read_text())
    assert proof["backup_stopped"]
    for name in ("source", "restored"):
        assert proof[name]["valid"]
        assert proof[name]["active_timer_count"] == 0
        assert proof[name]["server_instance_id"] == ready["server_instance_id"]
        assert proof[name]["sync_epoch"] == ready[name + "_epoch"]
    assert proof["source"]["row_counts"]["tracked_metrics"] == 0
    assert proof["restored"]["row_counts"]["tracked_metrics"] == 1
    assert proof["source"]["post_recovery_metric_count"] == 0
    assert proof["restored"]["post_recovery_metric_count"] == 1
    assert proof["restored"]["post_recovery_metric_name"] == "Recovery new metric"
