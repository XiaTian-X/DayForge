# Offline Android profile ledger

This tool is deliberately an **offline inventory and raw-result checker**. It does not execute
`tools/verify`, build, install, discover, schedule work, waive performance assertions, generate
`complete.json`, or produce P1 admission. Existing Gate, strict `read_cases`, runner and P1 loader
are unchanged. A zero CLI exit means the ledger inputs were structurally valid; even when all
XML passes, `is_full_gate_success` and `is_p1_admission` remain false.

The first useful slice is reviewer-declared complete discovery → explicit whole-class/profile units →
original XML states. This identifies missing/misassigned units before a long run and prevents
reported failures from being dropped when recording different execution profiles. It does not
make today's default full gate capable of running calibration-only Worker tests.

## Actual prerequisites, not guessed inventory

Before a real plan is admitted, the owner must supply:

1. The final complete source freeze with nonempty `files` mapping; its digest is pinned.
2. Each declared profile's exact APK hashes, runner, variant, coverage and complete build/runtime
   configuration record. Required config keys are `runner`, `variant`, `coverage`, `apk_sha256`
   (exactly two entries). Include real build/runner arguments as additional fields; all bytes are
   pinned. The checker does not attest that these declarations came from a physical execution.
3. Real completed **whole-suite** log-only discovery for every profile. Each capture sidecar
   binds the source manifest, profile config, discovery-log digests and `scope="whole-suite"`.
   Use the existing owner/Gate capture process. No current plan is supplied with this package:
   5604 static declarations and old 2947 identities are not current discovery.
4. A reviewed list of complete-class execution units. Every discovered class has exactly one
   primary unit. Optional extra units are explicit complete-class repeats, e.g. C04's eight
   classes/44 in one instrumentation process. Classification is never inferred from file names.
5. For results, original whole-unit discovery, all `TEST-*.xml` in each result directory, and a
   reviewed capture binding actual source/profile plus exit, cleanup and process IDs. Captures
   are trusted reviewer inputs, not cryptographic proof of a runtime. Link real raw provenance
   through extra capture fields; the checker does not replace independent evidence review.

C25 must be explicitly assigned its real Worker profile (including existing `dayforge.widgetRefresh=real`
argument and full ordered class); long diagnostics need explicit phase/batch budgets and their
existing prerequisites. The classifier cannot silently turn a safety class into a waived diagnostic.
There is intentionally no default, wildcard class selection, method filter or auto-detected `Cost`
category. All assigned methods come from actual discovery, including parameterized identities.

## Input interface

All artifact pins are `{"path":"relative/path","sha256":"64 lowercase hex"}` beneath `--root`.
Absolute paths, traversal, symlinks, changed or empty artifacts and duplicate JSON keys are rejected.
Use a separate, already archived evidence root; no app credentials are needed.

The plan JSON has:

- `format: 1`, `source_manifest: PIN`.
- `profiles: {PROFILE_ID: CONFIG_PIN}`.
- `discoveries: [{profile: PROFILE_ID, scope: "whole-suite", log: PIN, capture: PIN}]`.
  Discovery capture fields are `scope`, `source_manifest_sha256`, `profile_config_sha256`,
  `discovery_sha256`.
- `units: [{id, profile, kind, primary, same_process, reason, classes, budgets}]`.
  `kind` is explicitly `safety` or `diagnostic`; this is a label only, **not a waiver**.
  `primary`/`same_process` are booleans, `classes` is a nonempty full-class list, and `budgets`
  maps meaningful phase/batch names to positive integer seconds. Numbers are recorded, not enforced
  by this offline tool. Diagnostic prerequisites and rationale belong in the reviewed reason.

Optional result collection JSON has `format: 1`, `plan_sha256`, and
`runs: [{unit, run_id, capture: PIN, discovery: PIN, reports: [XML_PIN, ...]}]`.
The run capture binds the exact `run_id`, `unit`, `discovery` pin and full `reports` pin list,
then requires `source_manifest_sha256`, `profile_config_sha256`, integer `exit_code`,
boolean `cleanup_completed`, and nonempty unique positive integer `process_ids`. Only one attempt
per unit is accepted in a collection; retain failed/retried collections separately. Do not rewrite
an old collection into a success or select only passing reports.

A missing run is `NOT_RUN`. Missing result methods are `NOT_REPORTED` with an incomplete-unit
issue. Unexpected methods, malformed counters or negative/nonfinite durations are rejected. Every
original FAIL/ERROR/SKIPPED remains such; failure text stays in its pinned original XML and only its
hash is copied into the ledger. All-pass XML is additionally checked by the existing strict reader.
Data with failed XML cannot be promoted by a zero process exit or a diagnostic label.

## Example command shape (not an executable admission)

```text
python3 -m tools.check_android_profile_ledger \
  --root /absolute/path/to/archived-evidence \
  --plan reviewed-plan.json --plan-sha256 ACTUAL_REVIEWED_SHA \
  --results original-results.json --results-sha256 ACTUAL_RESULTS_SHA
```

Omit both result arguments to inspect a prepared mapping; every unit stays NOT_RUN. Output goes to
stdout only. It records `all_declared_primary_identities_reported` separately from `all_xml_pass`: an identity
with a failure/skip is reported but does not establish its complete functional tail. Nonzero exit,
unknown cleanup or a multi-process result for a same-process unit also keeps `all_xml_pass` false.
The output is **not** compatible with P1 `ordinary_results`; do not adapt it into such a record.

## Validation and limits

`tools/tests/test_android_profile_ledger.py` uses synthetic temporary metadata/XML/discovery strings
for host-only schema/counterexample checks. These are not Android discovery or execution evidence.
The author has not run them. After review, the existing root unittest discovery can run the complete
module; no Android suite rerun is needed solely to validate this non-executing checker.

A real multi-profile full-success contract, runtime provenance attestation, coverage calibration,
performance adjudication, current discovery, profile execution and genuine P1 successful evidence
remain outside this first slice. If those records are absent, keep the corresponding acceptance open;
do not invent current methods or full-suite success. Ordinary full's calibration incompatibility is
not fixed by this checker; the separate valid execution units must still actually run.

## Scope and capture limits

`scope="whole-suite"` is a reviewer declaration, not proof that a filtered command was never run.
The checker cannot distinguish a self-consistent, incorrectly narrowed declaration from a true
whole-suite capture. Output therefore says `declared_discovered_methods` and
`all_declared_primary_identities_reported`, and always sets
`discovery_scope_independently_verified=false`. Independent review must inspect the actual capture
command/runner/APK provenance before using the declared universe. No current full discovery is
certified by this tool.

Run identity plus exact discovery/report pins prevents pairing a current failure's capture with old
PASS XML from another same-source/profile run. It does not stop a false newly fabricated capture: the
trusted capture/provenance boundary remains explicit. Failed attempts remain separately pinned.
