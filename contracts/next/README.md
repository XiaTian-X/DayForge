# Forward contracts — not active protocol v4

These synthetic vectors freeze the first part of #181. They are not requests
that the current server accepts, not image files, and not an installable pack.
The repeated hashes describe fictional blobs; actual installation must validate
bytes independently. No owner IDs, credentials or device identifiers are here.

- `one-time-transitions.json`: state/intent/result or exact domain error. The
  reducer deliberately does not implement authentication, storage or operation replay.
- `icon-pack.json`: valid immutable catalog metadata with both color modes and a dark variant.
- `icon-references.json`: role/pinned references, purpose restrictions and unresolved assets.
- `invalid.json`: a base vector plus a typed path replacement; each must be rejected.

Consumed by `backend/tests/test_next_contracts.py` and Android instrumentation
`NextContractTest`. Do not copy vectors into either module or generate their
expected results from the implementation. New vectors must be consumed by both.

See [the target contract and activation gates](../../docs/APPEARANCE_CONTRACT.md).
Themes, config packages and the future protocol envelope need additional fixtures
before #181 is complete. Existing `sync-v2/` remains the live v4 contract.
