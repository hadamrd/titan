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
set -euo pipefail

# Repo root is two levels up from dev/rig-smoke/ — resolves correctly no
# matter the caller's cwd (the task runs from repo root; tests run elsewhere).
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

PLAYWRIGHT_CMD="${RIG_SMOKE_PLAYWRIGHT_CMD:-pnpm exec playwright}"
JSONL="${RIG_SMOKE_JSONL:-$REPO_ROOT/docs/operations/rig-smoke.jsonl}"
TEE_FILE="${RIG_SMOKE_TEE:-/tmp/rig-smoke-out.txt}"

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
