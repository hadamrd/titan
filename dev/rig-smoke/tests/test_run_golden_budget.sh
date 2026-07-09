#!/usr/bin/env bash
# Unit test for the #45 suite-budget derivation in dev/rig-smoke/run-golden.sh.
#
# Acceptance criterion under test (issue #45):
#   The 20-min playwright globalTimeout amputated every rig:smoke run — 43
#   @golden specs cannot fit. run-golden.sh must export a budget scaled to
#   the golden count (count × 90s × 1.5 safety, floor 45 min) and workers=2,
#   WITHOUT touching playwright.config.ts defaults (`task e2e` keeps its
#   current behavior). Explicit operator overrides must always win.
#
# The playwright binary is stubbed via RIG_SMOKE_PLAYWRIGHT_CMD with a fake
# that captures the env it received — the oracle is what the child process
# actually SAW, not what the script printed.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SCRIPT="$REPO_ROOT/dev/rig-smoke/run-golden.sh"
COUNTER="$REPO_ROOT/dev/rig-smoke/golden-count.sh"

if [ ! -x "$SCRIPT" ]; then
  echo "FAIL: $SCRIPT not executable" >&2
  exit 1
fi
if [ ! -x "$COUNTER" ]; then
  echo "FAIL: shared helper $COUNTER not executable" >&2
  exit 1
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

JSONL="$TMP/rig-smoke.jsonl"
TEE="$TMP/tee-out.txt"
ENV_CAPTURE="$TMP/env-capture.txt"

# Stub playwright: record the env it inherited, emit a green reporter line.
cat > "$TMP/pw-capture.sh" <<EOF
#!/usr/bin/env bash
echo "workers=\${TITAN_PW_WORKERS:-unset} budget=\${TITAN_GLOBAL_TIMEOUT_MS:-unset} triage=\${TITAN_E2E_TRIAGE_BUDGET_MS:-unset}" > "$ENV_CAPTURE"
echo "  14 passed (90s)"
exit 0
EOF
chmod +x "$TMP/pw-capture.sh"

# Synthetic specs dirs — reuses the exact fixture shape of
# test_check_golden_count.sh so the shared counter sees real @golden titles.
make_specs() { # <dir> <count>
  mkdir -p "$1"
  for i in $(seq 1 "$2"); do
    cat > "$1/spec$i.spec.ts" <<SPEC
test('@golden case $i works', async () => {});
SPEC
  done
}
make_specs "$TMP/specs43" 43
make_specs "$TMP/specs5" 5

run_golden() { # extra env via caller's `env ... run_golden` — args: none
  # #44/#84 probes skipped — hermetic (no docker/rig); covered by their own tests.
  RIG_SMOKE_SKIP_FRESHNESS=1 \
    RIG_SMOKE_SKIP_PREWARM=1 \
    RIG_SMOKE_PLAYWRIGHT_CMD="$TMP/pw-capture.sh" \
    RIG_SMOKE_JSONL="$JSONL" \
    RIG_SMOKE_TEE="$TEE" \
    bash "$SCRIPT"
}

# ── Case 1: 43 golden specs → 43 × 90000 × 1.5 = 5,805,000ms (above floor) ─
SPECS_DIR="$TMP/specs43" run_golden > "$TMP/c1.out" 2>&1
if ! grep -q 'budget=5805000' "$ENV_CAPTURE"; then
  echo "FAIL: 43 specs should derive budget 5805000ms, got: $(cat "$ENV_CAPTURE")" >&2
  exit 1
fi
if ! grep -q 'workers=2' "$ENV_CAPTURE"; then
  echo "FAIL: default workers should be 2, got: $(cat "$ENV_CAPTURE")" >&2
  exit 1
fi
echo "ok: 43 specs → budget 5805000ms (count × 90s × 1.5), workers=2"

# ── Case 2: budget log line is emitted in the documented shape ────────────
if ! grep -q '\[rig-smoke\] budget: 5805000ms for 43 golden specs, workers=2' "$TMP/c1.out"; then
  echo "FAIL: budget log line missing/malformed:" >&2
  cat "$TMP/c1.out" >&2
  exit 1
fi
echo "ok: budget log line emitted (Nms for K golden specs, workers=W)"

# ── Case 3: tiny golden set → floor of 45 min wins (wedge detection kept) ─
# 5 × 90000 × 1.5 = 675,000ms < 2,700,000ms floor.
SPECS_DIR="$TMP/specs5" run_golden > "$TMP/c3.out" 2>&1
if ! grep -q 'budget=2700000' "$ENV_CAPTURE"; then
  echo "FAIL: 5 specs should floor the budget at 2700000ms, got: $(cat "$ENV_CAPTURE")" >&2
  exit 1
fi
echo "ok: small golden set floors at 45 min (2700000ms)"

# ── Case 4: explicit TITAN_GLOBAL_TIMEOUT_MS override always wins ─────────
SPECS_DIR="$TMP/specs43" TITAN_GLOBAL_TIMEOUT_MS=123456 run_golden > "$TMP/c4.out" 2>&1
if ! grep -q 'budget=123456' "$ENV_CAPTURE"; then
  echo "FAIL: explicit TITAN_GLOBAL_TIMEOUT_MS was clobbered, got: $(cat "$ENV_CAPTURE")" >&2
  exit 1
fi
echo "ok: explicit TITAN_GLOBAL_TIMEOUT_MS override respected"

# ── Case 5: explicit TITAN_PW_WORKERS override always wins ────────────────
SPECS_DIR="$TMP/specs43" TITAN_PW_WORKERS=1 run_golden > "$TMP/c5.out" 2>&1
if ! grep -q 'workers=1' "$ENV_CAPTURE"; then
  echo "FAIL: explicit TITAN_PW_WORKERS was clobbered, got: $(cat "$ENV_CAPTURE")" >&2
  exit 1
fi
echo "ok: explicit TITAN_PW_WORKERS override respected"

# ── Case 6: playwright.config.ts defaults were NOT touched (#45 hard rule) ─
# The budget belongs to the smoke harness; `task e2e` keeps 20 min / IS_CI.
if ! grep -q 'TITAN_GLOBAL_TIMEOUT_MS ?? 20 \* 60 \* 1000' "$REPO_ROOT/e2e/playwright.config.ts"; then
  echo "FAIL: playwright.config.ts globalTimeout default changed — the budget must live in run-golden.sh only" >&2
  exit 1
fi
echo "ok: playwright.config.ts defaults untouched (budget lives in the harness)"

# ── Case 7: #151 — smoke exports the 45s triage budget to playwright ──────
# The spec's DEFAULT stays 30s (the strict canary for non-smoke contexts);
# the smoke harness alone widens it to 45s for exec-contention headroom.
SPECS_DIR="$TMP/specs43" run_golden > "$TMP/c7.out" 2>&1
if ! grep -q 'triage=45000' "$ENV_CAPTURE"; then
  echo "FAIL: run-golden.sh should export TITAN_E2E_TRIAGE_BUDGET_MS=45000, got: $(cat "$ENV_CAPTURE")" >&2
  exit 1
fi
echo "ok: #151 — smoke exports TITAN_E2E_TRIAGE_BUDGET_MS=45000"

# ── Case 8: #151 — explicit TITAN_E2E_TRIAGE_BUDGET_MS override always wins ─
SPECS_DIR="$TMP/specs43" TITAN_E2E_TRIAGE_BUDGET_MS=31000 run_golden > "$TMP/c8.out" 2>&1
if ! grep -q 'triage=31000' "$ENV_CAPTURE"; then
  echo "FAIL: explicit TITAN_E2E_TRIAGE_BUDGET_MS was clobbered, got: $(cat "$ENV_CAPTURE")" >&2
  exit 1
fi
echo "ok: #151 — explicit TITAN_E2E_TRIAGE_BUDGET_MS override respected"

# ── Case 9: #151 — the spec's default budget literal stays 30_000 ─────────
# Guard the non-smoke canary: the widened budget must live in run-golden.sh
# ONLY, never as a weakened default inside the spec.
if ! grep -q '?? 30_000' "$REPO_ROOT/e2e/specs/golden-path-failure-triage.spec.ts"; then
  echo "FAIL: triage spec default budget is no longer 30_000 — the strict canary was weakened" >&2
  exit 1
fi
echo "ok: #151 — spec default budget literal is still 30_000 (canary intact)"

echo ""
echo "PASS: all run-golden.sh budget-derivation cases (#45)"
