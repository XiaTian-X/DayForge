# Forward contracts — not active protocol v4

These synthetic vectors freeze incremental parts of #181. They are not requests
that the current server accepts, not image files, and not an installable pack.
The repeated hashes describe fictional blobs; actual installation must validate
bytes independently. No owner IDs, credentials or device identifiers are here.

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

Consumed by `backend/tests/test_next_contracts.py`, `test_theme_config_contract.py`
and Android instrumentation `NextContractTest`, `ThemeConfigContractTest`.
Do not copy vectors into either module or generate their
expected results from the implementation. New vectors must be consumed by both.

See [the target contract and activation gates](../../docs/APPEARANCE_CONTRACT.md).
The future protocol envelope and projection transport still need executable
contracts before #181 is complete. Existing `sync-v2/` remains the live v4 contract.
