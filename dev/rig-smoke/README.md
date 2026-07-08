# dev/rig-smoke — shell helpers backing `task rig:smoke`

These two scripts run during `task rig:smoke` (and its golden-guard
sub-target). They used to live under the in-tree Python loop package
because that's where the loop's brief assembled them, but they're
rig-smoke pipeline pieces — they survive the retirement of the in-tree
loop (issue #1059) and are exercised by their two shell tests in this
directory.

| Script | Used by | What it does |
|---|---|---|
| `golden-count.sh` | `check-golden-count.sh`, `run-golden.sh` | Shared helper (#45): prints the number of `@golden`-tagged Playwright tests under `SPECS_DIR`. One counting implementation so the guard and the time budget can never disagree. |
| `check-golden-count.sh` | `task rig:smoke:golden-guard`, `task rig:smoke` | Refuses to run if fewer than `RIG_SMOKE_GOLDEN_MIN` (default 12) `@golden`-tagged Playwright specs are present. Guards the V1-shippable bar (CONSTITUTION §1) against a contributor silently dropping a tag. |
| `run-golden.sh` | `task rig:smoke` | Runs the `@golden` Playwright subset with a suite budget scaled to the golden count (#45): `TITAN_PW_WORKERS=2` and `TITAN_GLOBAL_TIMEOUT_MS = count × 90s × 1.5` (floor 45 min) unless explicitly overridden. `task e2e` / `playwright.config.ts` defaults are untouched. |
| `rig-smoke-parse.sh` | `task rig:smoke` | Parses Playwright's "line" reporter output and appends one canonical JSON line to `docs/operations/rig-smoke.jsonl` — including `did_not_run` (#45) so an amputated run is machine-visible and never counts toward 3-consecutive-green. Propagates Playwright's exit code so a real failure surfaces. |
| `golden-roundtrip.sh` | `task rig:smoke:golden-roundtrip` | Opt-in (`LAYER2_RIG_AVAILABLE`) gate for the golden-path PR round-trip demo (specs 55/40/50). **Fails closed** (exit 3) when a provisioning var is missing — a missing secret must never degrade to a green skip. `--check-only` validates env without a rig. |

## Running the tests

```sh
bash dev/rig-smoke/tests/test_check_golden_count.sh
bash dev/rig-smoke/tests/test_rig_smoke_parse.sh
bash dev/rig-smoke/tests/test_run_golden.sh
bash dev/rig-smoke/tests/test_run_golden_budget.sh
bash dev/rig-smoke/tests/test_golden_roundtrip.sh
```

All are exit-code tests — no test runner needed.
