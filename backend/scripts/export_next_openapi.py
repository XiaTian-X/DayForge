"""Generate the NOT-YET-ACTIVE v5 delta contract. No HTTP handlers are installed."""

import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend"))

from pydantic.json_schema import models_json_schema  # noqa: E402
from src.v2.asset_api_contract import (  # noqa: E402
    AssetDeclaration,
    AssetRecord,
    AssetTransferReceipt,
    PackDeclaration,
    AppearanceQuota,
    AppearanceCatalogPage,
)
from src.v2.next_sync_contract import (  # noqa: E402
    NextSyncPushRequest,
    NextSyncPushResponse,
    NextSyncPullResponse,
    NextSyncBootstrapResponse,
    NextPlanNodePayload,
    NextMetricPayload,
    NextActivityEventPayload,
)

OUTPUT = ROOT / "contracts/next/openapi.json"
MODELS = (
    AssetDeclaration,
    AssetRecord,
    AssetTransferReceipt,
    PackDeclaration,
    AppearanceQuota,
    AppearanceCatalogPage,
    NextSyncPushRequest,
    NextSyncPushResponse,
    NextSyncPullResponse,
    NextSyncBootstrapResponse,
    NextPlanNodePayload,
    NextMetricPayload,
    NextActivityEventPayload,
)


def reference(model):
    return {"$ref": f"#/components/schemas/{model.__name__}"}


def parameter(name, where="query", *, required=True, schema=None):
    return {
        "name": name,
        "in": where,
        "required": required,
        "schema": schema or {"type": "string", "format": "uuid"},
    }


def operation(summary, response, *, request=None, parameters=(), binary=False):
    result = {
        "summary": summary,
        "security": [{"BearerAuth": []}, {"ApiTokenAuth": []}],
        "parameters": [
            parameter(
                "X-DayForge-Protocol", "header", schema={"type": "integer", "enum": [5]}
            ),
            *parameters,
        ],
        "responses": {
            "200": {
                "description": "Success after durable acknowledgement; immutable retries return the same identity",
                "content": {"application/json": {"schema": reference(response)}},
            },
            "401": {"description": "Authentication required"},
            "403": {"description": "Account or device capability denied"},
            "404": {
                "description": "Asset/pack not found in the authenticated account; other accounts are indistinguishable"
            },
            "409": {
                "description": "Identity/content conflict, stale server epoch, or pending content; inspect detail.code"
            },
            "413": {"description": "Streaming or account quota limit exceeded"},
            "422": {"description": "Invalid metadata, content or path binding"},
            "426": {"description": "Protocol v5 is not active/supported"},
            "503": {
                "description": "Transient failure; retain original identity and retry after backoff"
            },
        },
    }
    if request is not None:
        result["requestBody"] = {
            "required": True,
            "content": {"application/json": {"schema": reference(request)}},
        }
    if binary:
        result["requestBody"] = {
            "required": True,
            "content": {
                "application/octet-stream": {
                    "schema": {"type": "string", "format": "binary"}
                }
            },
        }
    return result


def rendered_contract():
    _, schemas = models_json_schema(
        [(model, "validation") for model in MODELS],
        ref_template="#/components/schemas/{model}",
    )
    scope = [
        parameter(key) for key in ("device_id", "server_instance_id", "sync_epoch")
    ]
    asset_path = [parameter("asset_id", "path")]
    variant_path = asset_path + [
        parameter(
            "variant", "path", schema={"type": "string", "enum": ["light", "dark"]}
        )
    ]
    pack_path = [
        parameter("pack_id", "path"),
        parameter(
            "revision",
            "path",
            schema={"type": "integer", "minimum": 1, "maximum": 2147483647},
        ),
    ]
    integer = {"type": "integer", "minimum": 0, "maximum": 9223372036854775807}
    paths = {
        "/api/v2/sync/push": {
            "post": operation(
                "v5 sync push; payload type is selected by entity_type, not client metadata",
                NextSyncPushResponse,
                request=NextSyncPushRequest,
            )
        },
        "/api/v2/sync/bootstrap": {
            "get": operation(
                "One snapshot of facts, task checkpoints and cursor",
                NextSyncBootstrapResponse,
                parameters=[parameter("device_id")],
            )
        },
        "/api/v2/sync/changes": {
            "get": operation(
                "Immutable incremental facts; task state is not a structural revision",
                NextSyncPullResponse,
                parameters=[
                    parameter("device_id"),
                    parameter("cursor", schema=integer),
                    parameter(
                        "limit",
                        required=False,
                        schema={"type": "integer", "minimum": 1, "maximum": 1000},
                    ),
                ],
            )
        },
        "/api/v2/appearance/assets/{asset_id}": {
            "put": operation(
                "Declare immutable asset metadata and reserve account quota; requires structure.write",
                AssetRecord,
                request=AssetDeclaration,
                parameters=asset_path,
            ),
            "get": operation(
                "Read owned metadata and advisory availability; requires sync.read",
                AssetRecord,
                parameters=asset_path + scope,
            ),
        },
        "/api/v2/appearance/packs/{pack_id}/versions/{revision}": {
            "put": operation(
                "Declare immutable pack version; all asset descriptors must exactly match owned declarations; requires structure.write",
                PackDeclaration,
                request=PackDeclaration,
                parameters=pack_path,
            ),
            "get": operation(
                "Read owned immutable pack version; requires sync.read",
                PackDeclaration,
                parameters=pack_path + scope,
            ),
        },
        "/api/v2/appearance/catalog": {
            "get": operation(
                "Append-only owned metadata, frozen through_sequence; never a cross-account hash lookup",
                AppearanceCatalogPage,
                parameters=scope
                + [
                    parameter(
                        "after", required=False, schema={**integer, "default": 0}
                    ),
                    parameter("through", required=False, schema=integer),
                    parameter(
                        "limit",
                        required=False,
                        schema={
                            "type": "integer",
                            "minimum": 1,
                            "maximum": 100,
                            "default": 100,
                        },
                    ),
                ],
            )
        },
        "/api/v2/appearance/quota": {
            "get": operation(
                "Account reservations include pending uploads; lowering limits does not erase existing metadata",
                AppearanceQuota,
                parameters=scope,
            )
        },
    }
    upload = operation(
        "Bounded validated bytes; requires structure.write; no database transaction across streaming/decode; reauthorize before finalization",
        AssetTransferReceipt,
        parameters=variant_path + scope,
        binary=True,
    )
    download = operation(
        "Owned asset variant only; never an arbitrary URL or bare hash; requires sync.read",
        AssetTransferReceipt,
        parameters=variant_path + scope,
    )
    download["responses"]["200"] = {
        "description": "Verified original bytes; private, no-store; declared media type, hash and length must match. Missing dark is 404, not an implicit light redirect.",
        "headers": {
            "Cache-Control": {
                "schema": {"type": "string", "const": "private, no-store"}
            },
            "X-Content-Type-Options": {
                "schema": {"type": "string", "const": "nosniff"}
            },
        },
        "content": {
            media: {"schema": {"type": "string", "format": "binary"}}
            for media in ("image/png", "image/svg+xml")
        },
    }
    paths["/api/v2/appearance/assets/{asset_id}/content/{variant}"] = {
        "put": upload,
        "get": download,
    }
    document = {
        "openapi": "3.1.0",
        "info": {
            "title": "DayForge v5 appearance and one-time delta (NOT ACTIVE)",
            "version": "5-planned",
            "description": "Activation requires coordinated backend/Android release and an explicitly confirmed empty baseline. Unchanged auth/device/timer payloads remain in ../openapi.json; v5 registration and every sync/timer/appearance request must satisfy the protocol gate described in APPEARANCE_CONTRACT.md. This document is a contract, not deployed routes.",
        },
        "x-activation-state": "planned",
        "paths": paths,
        "components": {
            "schemas": schemas["$defs"],
            "securitySchemes": {
                "BearerAuth": {"type": "http", "scheme": "bearer"},
                "ApiTokenAuth": {
                    "type": "apiKey",
                    "in": "header",
                    "name": "Authorization",
                    "description": "Token <account API token>",
                },
            },
        },
    }
    return json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    rendered = rendered_contract()
    if args.check:
        return 0 if OUTPUT.is_file() and OUTPUT.read_text() == rendered else 1
    OUTPUT.write_text(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
