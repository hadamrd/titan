#!/usr/bin/env bash
# run-golden.sh — run the @golden Playwright subset and append the smoke
# telemetry line to docs/operations/rig-smoke.jsonl.
#
# Why this is its own script (#31 — same pattern as rig-smoke-parse.sh, #1048):
#   go-task executes raw cmds under its built-in mvdan/sh interpreter, which
#   has no bash PIPESTATUS array. The old inline Taskfile step read
#   ${PIPESTATUS[0]} under `set -u` and aborted on EVERY run — green or red —
#   so the playwright exit code was never captured, no telemetry line was
#   ever appended, and the V1-shippable bar (3-consecutive-green) could never
#   count a run. Anything needing bash semantics lives in dev/rig-smoke/*.sh
#   and is invoked from the Taskfile as `bash dev/rig-smoke/<script>.sh`.
#
# Contract:
#   - Runs `playwright test --grep @golden --reporter=line` from e2e/.
#   - Tees the reporter output and measures wall-clock duration.
#   - Delegates parse + append + exit propagation to rig-smoke-parse.sh
#     (the unit-tested #1048 adversarial guard): the JSONL telemetry line
#     is ALWAYS appended — pass or fail — append-only, never truncated,
#     and this script exits with the playwright exit code.
#
# Env overrides (for tests — see tests/test_run_golden.sh):
#   RIG_SMOKE_PLAYWRIGHT_CMD  command prefix run instead of `pnpm exec playwright`
#   RIG_SMOKE_JSONL           telemetry file (default docs/operations/rig-smoke.jsonl)
#   RIG_SMOKE_TEE             tee capture path (default /tmp/rig-smoke-out.txt)
#   SPECS_DIR                 specs dir counted for the time budget (default e2e/specs)
#   TITAN_PW_WORKERS          playwright workers (default 2 — the sanctioned CI value)
#   TITAN_GLOBAL_TIMEOUT_MS   suite budget; if unset, derived from the @golden count
#   RIG_SMOKE_SKIP_FRESHNESS  =1 skips the #44 stale-rig probe (check-rig-freshness.sh)
#                             AND the #149 mount probe (family-wide hermetic knob)
#   RIG_SMOKE_SKIP_MOUNTS     =1 skips only the #149 bind-mount probe (check-rig-mounts.sh)
#   RIG_SMOKE_SKIP_PREWARM    =1 skips the #84 cold-worker pre-warm (prewarm-worker.sh)
set -euo pipefail

# Repo root is two levels up from dev/rig-smoke/ — resolves correctly no
# matter the caller's cwd (the task runs from repo root; tests run elsewhere).
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

PLAYWRIGHT_CMD="${RIG_SMOKE_PLAYWRIGHT_CMD:-pnpm exec playwright}"
JSONL="${RIG_SMOKE_JSONL:-$REPO_ROOT/docs/operations/rig-smoke.jsonl}"
TEE_FILE="${RIG_SMOKE_TEE:-/tmp/rig-smoke-out.txt}"

# ── Suite budget (#45) ──────────────────────────────────────────────────────
# playwright.config.ts defaults globalTimeout to 20 min, which amputated
# every smoke run once the @golden set grew past what fits in 20 min at
# workers=1 (43 specs, most driving REAL worker-executed builds of 30-60s+).
# The fix lives HERE, in the smoke harness, not in the config defaults:
# `task e2e` keeps its current behavior; rig:smoke exports its own budget.
#
#   workers  — 2, the sanctioned CI default ("keep workers modest").
#   budget   — @golden count × 90s per spec × 1.5 safety, floored at 45 min.
#              Scaling with the count preserves the wedge-detection property
#              (a hung compose still surfaces as a timeout) while never
#              amputating a healthy run as the golden surface grows.
export TITAN_PW_WORKERS="${TITAN_PW_WORKERS:-2}"

GOLDEN_COUNT=$(SPECS_DIR="${SPECS_DIR:-$REPO_ROOT/e2e/specs}" \
  bash "$REPO_ROOT/dev/rig-smoke/golden-count.sh")

if [ -z "${TITAN_GLOBAL_TIMEOUT_MS:-}" ]; then
  PER_SPEC_MS=90000
  BUDGET_MS=$((GOLDEN_COUNT * PER_SPEC_MS * 3 / 2)) # ×1.5 safety, integer math
  FLOOR_MS=$((45 * 60 * 1000))
  if [ "$BUDGET_MS" -lt "$FLOOR_MS" ]; then
    BUDGET_MS=$FLOOR_MS
  fi
  export TITAN_GLOBAL_TIMEOUT_MS="$BUDGET_MS"
fi
echo "[rig-smoke] budget: ${TITAN_GLOBAL_TIMEOUT_MS}ms for ${GOLDEN_COUNT} golden specs, workers=${TITAN_PW_WORKERS}"

# ── Failure-triage budget (#151) ────────────────────────────────────────────
# WHY 45000 here while the spec default stays 30000: PR #146 proved product
# latency healthy (engine overhead ~3s, verdict fold ~1.5s; timeline-proven),
# yet the triage spec still missed intermittently by seconds — the residue is
# npm/vitest EXECUTION time under suite-start CPU contention (2 PW workers +
# 5 containers on WSL2). 45s is exec-contention headroom for THIS smoke
# harness ONLY, and still catches the #106/#145-class 6-12s engine inflations
# at p95. Every non-smoke context keeps the strict 30s canary (spec default).
# Explicit operator overrides win, same as the other knobs above.
export TITAN_E2E_TRIAGE_BUDGET_MS="${TITAN_E2E_TRIAGE_BUDGET_MS:-45000}"

# ── Stale-rig probe (#44) ───────────────────────────────────────────────────
# `task dev:titan` bakes the checkout HEAD into the titan-server image
# (build-arg GIT_SHA → OCI revision label). The probe compares that label to
# HEAD: mismatch (or unprovable provenance) prints a LOUD warning on stderr
# and lands as `"rigShaMismatch":true` in the telemetry line — warning only,
# never a hard fail (intentional drift mid-bisect is legitimate). The helper
# echoes exactly `true`/`false` on stdout and always exits 0.
if ! RIG_SHA_MISMATCH=$(bash "$REPO_ROOT/dev/rig-smoke/check-rig-freshness.sh"); then
  RIG_SHA_MISMATCH=false
fi
case "$RIG_SHA_MISMATCH" in true|false) ;; *) RIG_SHA_MISMATCH=false ;; esac

# ── Dangling/foreign bind-mount probe (#149) ────────────────────────────────
# The #147 phantom P1: a rig composed from a since-deleted worktree keeps
# running, Docker recreates the bind source as an EMPTY dir, and the worker
# fixtures vanish while the image SHA still looks fresh. The sibling probe
# inspects every titan-* container's bind mounts for missing / empty-vs-
# checkout / outside-checkout sources. Same contract as the #44 probe:
# `true`/`false` on stdout, loud warning on stderr, always exit 0, lands as
# the ADDITIVE `rigMountIssue` telemetry field. Honors the same
# RIG_SMOKE_SKIP_FRESHNESS knob (plus RIG_SMOKE_SKIP_MOUNTS).
if ! RIG_MOUNT_ISSUE=$(bash "$REPO_ROOT/dev/rig-smoke/check-rig-mounts.sh"); then
  RIG_MOUNT_ISSUE=false
fi
case "$RIG_MOUNT_ISSUE" in true|false) ;; *) RIG_MOUNT_ISSUE=false ;; esac

# ── Cold-worker pre-warm (#84) ──────────────────────────────────────────────
# A titan-worker container younger than ~15min has a cold npm cache; the
# first real build pays a cold `npm ci` and blows the triage spec's 30s
# product-latency budget (false red on run 1 of every post-rebuild 3-green
# sequence). Fire one throwaway build and wait for it terminal BEFORE
# playwright starts. Best-effort: the helper always exits 0; skip with
# RIG_SMOKE_SKIP_PREWARM=1.
bash "$REPO_ROOT/dev/rig-smoke/prewarm-worker.sh" \
  || echo "[rig-smoke] pre-warm helper failed (non-fatal) — continuing" >&2

START_MS=$(date +%s%3N)
cd "$REPO_ROOT/e2e"
set +e
# shellcheck disable=SC2086  # deliberate word-splitting of the command prefix
$PLAYWRIGHT_CMD test --grep @golden --reporter=line | tee "$TEE_FILE"
EXIT=${PIPESTATUS[0]}
set -e
END_MS=$(date +%s%3N)
DUR=$((END_MS - START_MS))

# Parse + append + propagate exit through the unit-tested helper
# (#1048 adversarial guard — dev/rig-smoke/tests/test_rig_smoke_parse.sh).
# It appends the telemetry line first, then exits with the playwright exit
# code, so a red run still leaves its trace in the jsonl. The #44 probe
# result rides along as the additive `rigShaMismatch` field (existing fields
# are untouched — parse-compat for check-3-consecutive.sh and friends).
RIG_SMOKE_RIG_SHA_MISMATCH="$RIG_SHA_MISMATCH" \
  RIG_SMOKE_RIG_MOUNT_ISSUE="$RIG_MOUNT_ISSUE" \
  bash "$REPO_ROOT/dev/rig-smoke/rig-smoke-parse.sh" "$TEE_FILE" "$EXIT" "$DUR" "$JSONL"
