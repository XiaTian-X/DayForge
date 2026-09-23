# Forward contracts — not active protocol v4

These synthetic vectors freeze incremental parts of #181. They are not requests
that the current server accepts, not image files, and not an installable pack.
The repeated hashes describe fictional blobs; actual installation must validate
bytes independently. All identities are synthetic; no real account/device identifiers or credentials are here.

- `one-time-transitions.json`: state/intent/result or exact domain error. The
  reducer deliberately does not implement authentication, storage or operation replay.
- `icon-pack.json`: valid immutable catalog metadata with both color modes and a dark variant.
- `icon-references.json`: role/pinned references, purpose restrictions and unresolved assets.
- `invalid.json`: a base vector plus a typed path replacement; each must be rejected.
- `theme.json`: complete resolved light/dark palettes, with 36 Material, 12 status
  and 4 chart roles each. Stored colors do not change with generator/library upgrades.
- `config.json`: portable metadata covering goals, all habit modes, one-time items,
  every schedule, metric settings, links, captured icons and optional themes. Package
  identities must be remapped on import; no completion history is included.
- `theme-config-invalid.json`: additional typed mutations rejected on both platforms,
  including primitive coercion, incomplete palettes, graph errors and runtime history.
- `one-time-sync.json`: request/event binding, immutable state-after proofs, monotonic
  projection merge, complete-history recovery and pending causal-chain views. A lost
  reply requires original-ID replay, not an inferred rejection or a rewritten intent.
- `api.json`: future sync envelopes, explicit item policy/appearance, complete bootstrap
  checkpoints, separate rejection context and authenticated asset transport values.
  Wrong server/device/epoch/content acknowledgements are rejected by binding helpers;
  helpers are not authorization or durable storage.
- `openapi.json`: reproducible **planned**, not mounted, v5 delta API. Generate with
  `cd backend && uv run --frozen python scripts/export_next_openapi.py`; `--check` is
  included in the backend suite. Unchanged live endpoints remain in `../openapi.json`.

Consumed by `backend/tests/test_next_contracts.py`, `test_theme_config_contract.py`
and `backend/tests/test_one_time_sync_contract.py`, `test_next_api_contract.py`; Android instrumentation
`NextContractTest`, `ThemeConfigContractTest`, `OneTimeSyncContractTest`, `NextApiContractTest`.
Do not copy vectors into either module or generate their
expected results from the implementation. New vectors must be consumed by both.

See [the target contract and activation gates](../../docs/APPEARANCE_CONTRACT.md).
The one-time portion of future event snapshots is executable; current v4 HTTP does
not accept the new intent. Protocol/API activation, persistence and authenticated
asset transport still have separate implementation and release gates.
Existing `sync-v2/` remains the live v4 contract.
