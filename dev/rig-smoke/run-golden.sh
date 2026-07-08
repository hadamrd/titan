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
# code, so a red run still leaves its trace in the jsonl.
bash "$REPO_ROOT/dev/rig-smoke/rig-smoke-parse.sh" "$TEE_FILE" "$EXIT" "$DUR" "$JSONL"
