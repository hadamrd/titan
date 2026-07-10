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
| `rig-smoke-parse.sh` | `task rig:smoke` | Parses Playwright's "line" reporter output and appends one canonical JSON line to `docs/operations/rig-smoke.jsonl` — including `did_not_run` (#45) so an amputated run is machine-visible and never counts toward 3-consecutive-green, and `rigShaMismatch` (#44, additive field) so a stale rig leaves a machine-visible trace. Propagates Playwright's exit code so a real failure surfaces. |
| `check-rig-mounts.sh` | `task rig:smoke` (reuse-existing-rig path), `run-golden.sh` | Dangling/foreign bind-mount guard (#149). Root cause of the false P1 #147: a rig composed from a git worktree kept running after the worktree was deleted, and Docker silently recreated the bind source as an EMPTY dir — `/titan/fixtures` had no `package.json` and two smoke runs burned on a phantom engine regression while the image SHA still looked fresh. This probe `docker inspect`s every running titan-* container's bind mounts and prints a LOUD `RIG MOUNT ISSUE` warning (naming container + mount) when a source is missing, is an empty dir whose checkout counterpart is non-empty, or points outside the current checkout root (e.g. `/tmp/wt-*`); system paths (`/var/run`, `/var/lib/docker`, ... + `RIG_SMOKE_MOUNT_ALLOWLIST`) are exempt. Emits `true`/`false` (→ additive `rigMountIssue` telemetry field). Warning only, always exit 0. Skip: `RIG_SMOKE_SKIP_MOUNTS=1` (or the family-wide `RIG_SMOKE_SKIP_FRESHNESS=1`). |
| `check-rig-freshness.sh` | `task rig:smoke` (reuse-existing-rig path), `run-golden.sh` | Stale-jar guard (#44, worker parity #155). `task dev:titan` bakes the checkout HEAD into the titan-server AND titan-worker images (`GIT_SHA` build-arg → OCI label `org.opencontainers.image.revision` + `/app/TITAN_GIT_SHA`). This probe `docker inspect`s each label and compares it to `git rev-parse HEAD`: on mismatch — or when the SERVER label is missing/`unknown`, i.e. the image was built via a direct `docker compose up --build titan-server` — it prints a LOUD `STALE RIG` warning and emits `true` (→ `"rigShaMismatch":true` in the telemetry). An UNLABELED worker image (pre-#155 build) falls back to the #153/#154 image-created-time skew heuristic (`RIG_SMOKE_WORKER_SKEW_MAX_S`, default 3600s) — label-first because the skew heuristic false-positives on byte-identical jar cache-hits. Warning only, always exit 0: intentional drift mid-bisect is legitimate. Skip: `RIG_SMOKE_SKIP_FRESHNESS=1`. |
| `prewarm-worker.sh` | `run-golden.sh` | Cold-worker pre-warm (#84). If the titan-worker container is younger than `RIG_SMOKE_PREWARM_MAX_AGE_S` (default 900s) its npm cache is cold and the first real build blows the triage spec's 30s product budget. The script fires ONE throwaway build (job `rig-smoke-prewarm`, same `npm ci` install step as the node fixtures) via the public API and waits for it to reach a terminal status before Playwright starts, logging `[rig-smoke] pre-warmed worker (Xs)`. Best-effort: always exits 0. Skip: `RIG_SMOKE_SKIP_PREWARM=1`. |
| `golden-roundtrip.sh` | `task rig:smoke:golden-roundtrip` | Opt-in (`LAYER2_RIG_AVAILABLE`) gate for the golden-path PR round-trip demo (specs 55/40/50). **Fails closed** (exit 3) when a provisioning var is missing — a missing secret must never degrade to a green skip. `--check-only` validates env without a rig. |

## Running the tests

```sh
bash dev/rig-smoke/tests/test_check_golden_count.sh
bash dev/rig-smoke/tests/test_rig_smoke_parse.sh
bash dev/rig-smoke/tests/test_run_golden.sh
bash dev/rig-smoke/tests/test_run_golden_budget.sh
bash dev/rig-smoke/tests/test_golden_roundtrip.sh
bash dev/rig-smoke/tests/test_check_rig_freshness.sh
bash dev/rig-smoke/tests/test_check_rig_mounts.sh
bash dev/rig-smoke/tests/test_prewarm_worker.sh
bash dev/rig-smoke/tests/test_run_golden_freshness.sh
bash dev/rig-smoke/tests/test_run_golden_mounts.sh
```

All are exit-code tests — no test runner needed.
