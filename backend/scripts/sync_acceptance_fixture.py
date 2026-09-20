#!/usr/bin/env python3
"""Seed and verify a real Sync V2 Android acceptance fixture."""

from __future__ import annotations

import argparse
import json
from typing import Any
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


GOAL_UUID = "10000000-0000-4000-8000-000000000001"
ACTIVITY_UUID = "10000000-0000-4000-8000-000000000002"
GOAL_OPERATION_UUID = "20000000-0000-4000-8000-000000000001"
ACTIVITY_OPERATION_UUID = "20000000-0000-4000-8000-000000000002"
GOAL_TITLE = "E2E Family Goal"
ACTIVITY_TITLE = "E2E Check Habit"

FEATURE_ACTIVITIES = (
    (
        "30000000-0000-4000-8000-000000000001",
        "API测试-正计数（目标5）",
        "count",
        False,
        "5",
    ),
    (
        "30000000-0000-4000-8000-000000000002",
        "API测试-倒计数（目标5）",
        "count",
        True,
        "5",
    ),
    (
        "30000000-0000-4000-8000-000000000003",
        "API测试-正计时（1分钟）",
        "duration",
        False,
        "60",
    ),
    (
        "30000000-0000-4000-8000-000000000004",
        "API测试-倒计时（1分钟）",
        "duration",
        True,
        "60",
    ),
)
FEATURE_METRIC_UUID = "30000000-0000-4000-8000-000000000005"
FEATURE_OBSERVATION_UUID = "30000000-0000-4000-8000-000000000006"
FEATURE_LINK_UUID = "30000000-0000-4000-8000-000000000007"
REMOTE_UPDATE_UUID = "60000000-0000-4000-8000-000000000001"
REMOTE_DELETE_UUID = "60000000-0000-4000-8000-000000000002"


def check_activity_payload(title: str) -> dict[str, Any]:
    return {
        "node_kind": "activity",
        "title": title,
        "parent_uuid": None,
        "activity": {
            "tracking_mode": "check",
            "recurrence_rule": {"schema_version": 1, "type": "daily", "interval": 1},
            "completion_policy": "recurring",
            "target_value": "1",
            "failure_policy": {"schema_version": 1, "type": "strict"},
            "timezone": "Asia/Shanghai",
        },
    }


class AcceptanceClient:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")

    def request(
        self,
        method: str,
        path: str,
        *,
        payload: dict[str, Any] | None = None,
        token: str | None = None,
        expected: tuple[int, ...] = (200,),
    ) -> Any:
        body = json.dumps(payload).encode() if payload is not None else None
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        request = Request(
            f"{self.base_url}{path}",
            data=body,
            headers=headers,
            method=method,
        )
        try:
            with urlopen(request, timeout=10) as response:
                status = response.status
                raw = response.read()
        except HTTPError as error:
            status = error.code
            raw = error.read()
        parsed = json.loads(raw) if raw else None
        if status not in expected:
            raise RuntimeError(f"{method} {path} returned {status}: {parsed}")
        return parsed

    def login(self, username: str, password: str) -> dict[str, Any]:
        return self.request(
            "POST",
            "/api/v1/auth/login",
            payload={"username": username, "password": password},
        )

    def register_device(self, token: str, installation_id: str) -> str:
        response = self.request(
            "POST",
            "/api/v2/devices/register",
            token=token,
            payload={
                "installation_id": installation_id,
                "protocol_version": 4,
                "platform": "android",
                "device_class": "interactive",
                "app_version": "2.0-e2e",
                "display_name": "E2E fixture",
            },
        )
        return response["device_id"]


def seed(args: argparse.Namespace) -> None:
    client = AcceptanceClient(args.base_url)
    admin = client.login(args.admin_username, args.admin_password)
    client.request(
        "POST",
        "/api/v1/admin/users",
        token=admin["access_token"],
        payload={
            "username": args.member_username,
            "password": args.member_password,
            "is_admin": False,
        },
        expected=(201, 409),
    )
    member = client.login(args.member_username, args.member_password)
    device_id = client.register_device(member["access_token"], "e2e-fixture-writer")
    response = client.request(
        "POST",
        "/api/v2/sync/push",
        token=member["access_token"],
        payload={
            "device_id": device_id,
            "operations": [
                {
                    "operation_id": GOAL_OPERATION_UUID,
                    "entity_type": "plan_node",
                    "entity_uuid": GOAL_UUID,
                    "action": "upsert",
                    "base_revision": None,
                    "payload": {
                        "node_kind": "goal",
                        "title": GOAL_TITLE,
                        "goal": {
                            "evaluation_policy": {
                                "schema_version": 1,
                                "type": "manual",
                            }
                        },
                    },
                },
                {
                    "operation_id": ACTIVITY_OPERATION_UUID,
                    "entity_type": "plan_node",
                    "entity_uuid": ACTIVITY_UUID,
                    "action": "upsert",
                    "base_revision": None,
                    "payload": {
                        "node_kind": "activity",
                        "title": ACTIVITY_TITLE,
                        "parent_uuid": GOAL_UUID,
                        "activity": {
                            "tracking_mode": "check",
                            "recurrence_rule": {
                                "schema_version": 1,
                                "type": "daily",
                                "interval": 1,
                            },
                            "completion_policy": "recurring",
                            "target_value": "1",
                            "failure_policy": {
                                "schema_version": 1,
                                "type": "strict",
                            },
                            "timezone": "Asia/Shanghai",
                        },
                    },
                },
            ],
        },
    )
    statuses = [item["status"] for item in response["results"]]
    if any(status not in {"applied", "already_applied"} for status in statuses):
        raise RuntimeError(f"Fixture seed was rejected: {response}")
    print(json.dumps({"seeded": True, "statuses": statuses}, ensure_ascii=False))


def create_empty_member(args: argparse.Namespace) -> None:
    client = AcceptanceClient(args.base_url)
    admin = client.login(args.admin_username, args.admin_password)
    client.request(
        "POST",
        "/api/v1/admin/users",
        token=admin["access_token"],
        payload={
            "username": args.member_username,
            "password": args.member_password,
            "is_admin": False,
        },
        expected=(201, 409),
    )
    client.login(args.member_username, args.member_password)
    print(
        json.dumps(
            {"available": True, "username": args.member_username}, ensure_ascii=False
        )
    )


def seed_remote_mutations(args: argparse.Namespace) -> None:
    client = AcceptanceClient(args.base_url)
    member = client.login(args.member_username, args.member_password)
    device_id = client.register_device(
        member["access_token"], "e2e-remote-mutation-writer"
    )
    operations = [
        {
            "operation_id": "70000000-0000-4000-8000-000000000001",
            "entity_type": "plan_node",
            "entity_uuid": REMOTE_UPDATE_UUID,
            "action": "upsert",
            "base_revision": None,
            "payload": check_activity_payload("服务端更新测试-旧名称"),
        },
        {
            "operation_id": "70000000-0000-4000-8000-000000000002",
            "entity_type": "plan_node",
            "entity_uuid": REMOTE_DELETE_UUID,
            "action": "upsert",
            "base_revision": None,
            "payload": check_activity_payload("服务端删除测试"),
        },
    ]
    response = client.request(
        "POST",
        "/api/v2/sync/push",
        token=member["access_token"],
        payload={"device_id": device_id, "operations": operations},
    )
    statuses = [result["status"] for result in response["results"]]
    if any(status not in {"applied", "already_applied"} for status in statuses):
        raise RuntimeError(f"Remote mutation seed was rejected: {response}")
    print(json.dumps({"seeded": True, "statuses": statuses}, ensure_ascii=False))


def apply_remote_mutations(args: argparse.Namespace) -> None:
    client = AcceptanceClient(args.base_url)
    member = client.login(args.member_username, args.member_password)
    device_id = client.register_device(
        member["access_token"], "e2e-remote-mutation-writer"
    )
    operations = [
        {
            "operation_id": "80000000-0000-4000-8000-000000000001",
            "entity_type": "plan_node",
            "entity_uuid": REMOTE_UPDATE_UUID,
            "action": "upsert",
            "base_revision": 1,
            "payload": check_activity_payload("服务端更新测试-新名称"),
        },
        {
            "operation_id": "80000000-0000-4000-8000-000000000002",
            "entity_type": "plan_node",
            "entity_uuid": REMOTE_DELETE_UUID,
            "action": "delete",
            "base_revision": 1,
            "payload": {},
        },
    ]
    response = client.request(
        "POST",
        "/api/v2/sync/push",
        token=member["access_token"],
        payload={"device_id": device_id, "operations": operations},
    )
    statuses = [result["status"] for result in response["results"]]
    if any(status not in {"applied", "already_applied"} for status in statuses):
        raise RuntimeError(f"Remote mutations were rejected: {response}")
    print(json.dumps({"mutated": True, "statuses": statuses}, ensure_ascii=False))


def seed_feature_matrix(args: argparse.Namespace) -> None:
    client = AcceptanceClient(args.base_url)
    admin = client.login(args.admin_username, args.admin_password)
    client.request(
        "POST",
        "/api/v1/admin/users",
        token=admin["access_token"],
        payload={
            "username": args.member_username,
            "password": args.member_password,
            "is_admin": False,
        },
        expected=(201, 409),
    )
    member = client.login(args.member_username, args.member_password)
    device_id = client.register_device(
        member["access_token"], "e2e-feature-matrix-writer"
    )
    operations = []
    for index, (
        entity_uuid,
        title,
        tracking_mode,
        is_countdown,
        target_value,
    ) in enumerate(
        FEATURE_ACTIVITIES,
        start=1,
    ):
        activity = {
            "tracking_mode": tracking_mode,
            "is_countdown": is_countdown,
            "recurrence_rule": {"schema_version": 1, "type": "daily", "interval": 1},
            "completion_policy": "recurring",
            "target_value": target_value,
            "target_cycles": None,
            "failure_policy": {"schema_version": 1, "type": "strict"},
            "preferred_local_time": None,
            "timezone": "Asia/Shanghai",
        }
        if tracking_mode == "duration":
            activity["target_unit"] = "second"
        operations.append(
            {
                "operation_id": f"40000000-0000-4000-8000-{index:012d}",
                "entity_type": "plan_node",
                "entity_uuid": entity_uuid,
                "action": "upsert",
                "base_revision": None,
                "payload": {
                    "node_kind": "activity",
                    "title": title,
                    "description": "由 Sync V2 API 创建的真实设备验收数据",
                    "icon": "work" if tracking_mode == "duration" else "calculate",
                    "color_hex": "#2196F3",
                    "status": "active",
                    "visibility": "private",
                    "parent_uuid": None,
                    "activity": activity,
                },
            }
        )
    operations.extend(
        [
            {
                "operation_id": "40000000-0000-4000-8000-000000000005",
                "entity_type": "metric",
                "entity_uuid": FEATURE_METRIC_UUID,
                "action": "upsert",
                "base_revision": None,
                "payload": {
                    "name": "API测试指标（体重）",
                    "description": "用于验证指标定义和记录同步",
                    "unit": "kg",
                    "decimal_places": 1,
                    "aggregation_type": "average",
                    "target_direction": "decrease",
                    "target_value": "65.0",
                    "target_value_upper": None,
                    "icon": "health",
                    "color_hex": "#4CAF50",
                    "status": "active",
                },
            },
            {
                "operation_id": "40000000-0000-4000-8000-000000000006",
                "entity_type": "metric_observation",
                "entity_uuid": FEATURE_OBSERVATION_UUID,
                "action": "upsert",
                "base_revision": None,
                "payload": {
                    "metric_uuid": FEATURE_METRIC_UUID,
                    "value": "65.5",
                    "unit": "kg",
                    "occurred_at": "2026-08-12T09:00:00+08:00",
                    "local_date": "2026-08-12",
                    "timezone": "Asia/Shanghai",
                    "note": "API 同步验收记录",
                    "source_type": "api",
                },
            },
            {
                "operation_id": "40000000-0000-4000-8000-000000000007",
                "entity_type": "activity_metric_link",
                "entity_uuid": FEATURE_LINK_UUID,
                "action": "upsert",
                "base_revision": None,
                "payload": {
                    "activity_uuid": FEATURE_ACTIVITIES[0][0],
                    "metric_uuid": FEATURE_METRIC_UUID,
                    "coefficient": "1",
                    "show_in_activity_detail": True,
                    "prompt_on_complete": False,
                    "is_active": True,
                },
            },
        ]
    )
    response = client.request(
        "POST",
        "/api/v2/sync/push",
        token=member["access_token"],
        payload={"device_id": device_id, "operations": operations},
    )
    statuses = [item["status"] for item in response["results"]]
    if any(status not in {"applied", "already_applied"} for status in statuses):
        raise RuntimeError(f"Feature matrix seed was rejected: {response}")
    print(
        json.dumps(
            {
                "seeded": True,
                "activities": [item[1] for item in FEATURE_ACTIVITIES],
                "metric": "API测试指标（体重）",
                "statuses": statuses,
            },
            ensure_ascii=False,
        )
    )


def verify_check_in(args: argparse.Namespace) -> None:
    client = AcceptanceClient(args.base_url)
    member = client.login(args.member_username, args.member_password)
    device_id = client.register_device(member["access_token"], "e2e-fixture-verifier")
    query = urlencode({"device_id": device_id})
    snapshot = client.request(
        "GET",
        f"/api/v2/sync/bootstrap?{query}",
        token=member["access_token"],
    )
    changes = snapshot["changes"]
    nodes = {
        change["entity_uuid"]: change
        for change in changes
        if change["entity_type"] == "plan_node"
    }
    if nodes.get(GOAL_UUID, {}).get("payload", {}).get("title") != GOAL_TITLE:
        raise RuntimeError("Seed goal is missing from the canonical snapshot")
    if nodes.get(ACTIVITY_UUID, {}).get("payload", {}).get("title") != ACTIVITY_TITLE:
        raise RuntimeError("Seed activity is missing from the canonical snapshot")
    events = [
        change
        for change in changes
        if change["entity_type"] == "activity_event"
        and change.get("payload", {}).get("activity_uuid") == ACTIVITY_UUID
        and change.get("payload", {}).get("event_type") == "check_in"
    ]
    if not events:
        raise RuntimeError("Android check-in has not reached the backend")
    print(
        json.dumps(
            {
                "verified": True,
                "check_in_events": len(events),
                "next_cursor": snapshot["next_cursor"],
            },
            ensure_ascii=False,
        )
    )


def verify_feature_matrix(args: argparse.Namespace) -> None:
    client = AcceptanceClient(args.base_url)
    member = client.login(args.member_username, args.member_password)
    device_id = client.register_device(
        member["access_token"], "e2e-feature-matrix-verifier"
    )
    query = urlencode({"device_id": device_id})
    snapshot = client.request(
        "GET",
        f"/api/v2/sync/bootstrap?{query}",
        token=member["access_token"],
    )
    changes = {change["entity_uuid"]: change for change in snapshot["changes"]}
    actual_modes = []
    for (
        entity_uuid,
        title,
        tracking_mode,
        is_countdown,
        target_value,
    ) in FEATURE_ACTIVITIES:
        change = changes.get(entity_uuid)
        if change is None or change["payload"].get("title") != title:
            raise RuntimeError(f"Feature activity is missing: {title}")
        activity = change["payload"].get("activity", {})
        actual_modes.append(
            (
                activity.get("tracking_mode"),
                activity.get("is_countdown"),
                int(float(activity.get("target_value", 0))),
            )
        )
        expected = (tracking_mode, is_countdown, int(target_value))
        if actual_modes[-1] != expected:
            raise RuntimeError(f"Feature activity changed shape: {title}: {activity}")
    required_entities = {
        FEATURE_METRIC_UUID: "metric",
        FEATURE_OBSERVATION_UUID: "metric_observation",
        FEATURE_LINK_UUID: "activity_metric_link",
    }
    for entity_uuid, entity_type in required_entities.items():
        if changes.get(entity_uuid, {}).get("entity_type") != entity_type:
            raise RuntimeError(
                f"Feature entity is missing: {entity_type}/{entity_uuid}"
            )
    print(
        json.dumps(
            {
                "verified": True,
                "activity_modes": actual_modes,
                "entity_types": [*required_entities.values()],
                "next_cursor": snapshot["next_cursor"],
            },
            ensure_ascii=False,
        )
    )


def enable_feature_metric_prompt(args: argparse.Namespace) -> None:
    client = AcceptanceClient(args.base_url)
    member = client.login(args.member_username, args.member_password)
    device_id = client.register_device(
        member["access_token"], "e2e-metric-prompt-writer"
    )
    response = client.request(
        "POST",
        "/api/v2/sync/push",
        token=member["access_token"],
        payload={
            "device_id": device_id,
            "operations": [
                {
                    "operation_id": "50000000-0000-4000-8000-000000000001",
                    "entity_type": "activity_metric_link",
                    "entity_uuid": FEATURE_LINK_UUID,
                    "action": "upsert",
                    "base_revision": 1,
                    "payload": {
                        "activity_uuid": FEATURE_ACTIVITIES[0][0],
                        "metric_uuid": FEATURE_METRIC_UUID,
                        "coefficient": "1",
                        "show_in_activity_detail": True,
                        "prompt_on_complete": True,
                        "is_active": True,
                    },
                }
            ],
        },
    )
    result = response["results"][0]
    if result["status"] not in {"applied", "already_applied"}:
        raise RuntimeError(f"Metric prompt update was rejected: {response}")
    if not result.get("entity", {}).get("prompt_on_complete"):
        raise RuntimeError(f"Metric prompt was not enabled: {response}")
    print(
        json.dumps(
            {
                "updated": True,
                "status": result["status"],
                "revision": result.get("revision"),
                "prompt_on_complete": True,
            },
            ensure_ascii=False,
        )
    )


def verify_feature_results(args: argparse.Namespace) -> None:
    client = AcceptanceClient(args.base_url)
    member = client.login(args.member_username, args.member_password)
    device_id = client.register_device(
        member["access_token"], "e2e-feature-result-verifier"
    )
    query = urlencode({"device_id": device_id})
    snapshot = client.request(
        "GET",
        f"/api/v2/sync/bootstrap?{query}",
        token=member["access_token"],
    )
    events_by_activity = {entity_uuid: [] for entity_uuid, *_ in FEATURE_ACTIVITIES}
    extra_observations = []
    for change in snapshot["changes"]:
        payload = change.get("payload", {})
        if change["entity_type"] == "activity_event":
            activity_uuid = payload.get("activity_uuid")
            if (
                activity_uuid in events_by_activity
                and payload.get("event_type") != "revert"
            ):
                events_by_activity[activity_uuid].append(payload)
        elif (
            change["entity_type"] == "metric_observation"
            and payload.get("metric_uuid") == FEATURE_METRIC_UUID
            and change["entity_uuid"] != FEATURE_OBSERVATION_UUID
        ):
            extra_observations.append(payload)

    result_summary = {}
    for entity_uuid, title, tracking_mode, *_ in FEATURE_ACTIVITIES:
        events = events_by_activity[entity_uuid]
        if not events:
            raise RuntimeError(f"Android result is missing: {title}")
        if tracking_mode == "count":
            values = [event.get("value") for event in events]
            if any(
                value is None or float(value) != int(float(value)) for value in values
            ):
                raise RuntimeError(
                    f"Android count result has an invalid value: {title}: {values}"
                )
            result_summary[title] = {"events": len(events), "values": values}
        else:
            durations = [event.get("duration_seconds") for event in events]
            if any(duration is None or duration <= 0 for duration in durations):
                raise RuntimeError(
                    f"Android duration result is invalid: {title}: {durations}"
                )
            result_summary[title] = {
                "events": len(events),
                "duration_seconds": durations,
            }
    if not extra_observations:
        raise RuntimeError("Android metric observation has not reached the backend")

    print(
        json.dumps(
            {
                "verified": True,
                "activity_results": result_summary,
                "metric_observations": [
                    {"value": observation["value"], "unit": observation["unit"]}
                    for observation in extra_observations
                ],
                "next_cursor": snapshot["next_cursor"],
            },
            ensure_ascii=False,
        )
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "command",
        choices=(
            "seed",
            "create-empty-member",
            "seed-remote-mutations",
            "apply-remote-mutations",
            "seed-feature-matrix",
            "enable-feature-metric-prompt",
            "verify-check-in",
            "verify-feature-matrix",
            "verify-feature-results",
        ),
    )
    parser.add_argument("--base-url", default="http://127.0.0.1:8000")
    parser.add_argument("--admin-username", default="e2e_admin")
    parser.add_argument("--admin-password", default="AdminPassword123!")
    parser.add_argument("--member-username", default="e2e_member")
    parser.add_argument("--member-password", default="MemberPassword123!")
    return parser.parse_args()


if __name__ == "__main__":
    arguments = parse_args()
    if arguments.command == "seed":
        seed(arguments)
    elif arguments.command == "create-empty-member":
        create_empty_member(arguments)
    elif arguments.command == "seed-remote-mutations":
        seed_remote_mutations(arguments)
    elif arguments.command == "apply-remote-mutations":
        apply_remote_mutations(arguments)
    elif arguments.command == "seed-feature-matrix":
        seed_feature_matrix(arguments)
    elif arguments.command == "enable-feature-metric-prompt":
        enable_feature_metric_prompt(arguments)
    elif arguments.command == "verify-check-in":
        verify_check_in(arguments)
    elif arguments.command == "verify-feature-matrix":
        verify_feature_matrix(arguments)
    else:
        verify_feature_results(arguments)
