# Sync V2 contract fixtures

These JSON files are executable protocol examples shared by Android and the backend.
They are deliberately free of credentials and use reserved UUIDs and fixed timestamps.

- `client/` contains requests an interactive client can send.
- `server/` contains responses a protocol-v4 server can return.
- `invalid/` contains requests that every server must reject before domain mutation.

When a wire field changes, update the fixture, both contract test suites, OpenAPI, and
`docs/SYNC_PROTOCOL.md` in the same pull request. Fixtures must remain deterministic:
do not add generated UUIDs, the current time, account IDs, access tokens, or database IDs.

The behavioral matrix in `backend/tests/test_sync_contract_matrix.py` replays these
requests to cover lost responses, conflicts, tombstones, account isolation, time-zone
boundaries, and ordered timer commands. Android separately decodes every server fixture
and round-trips every valid client fixture.
