#!/usr/bin/env bash
# Adversarial unit test for dev/rig-smoke/run-golden.sh (#31).
#
# Acceptance criterion under test (the #1048 guarantee, now for the whole
# run+capture+parse step):
#   - a failing playwright run MUST make run-golden.sh exit non-zero AND
#     still append the telemetry line (red runs leave a trace),
#   - a passing run MUST exit zero and append its line too.
#
# The real playwright binary is stubbed via RIG_SMOKE_PLAYWRIGHT_CMD (a fake
# script emitting canned reporter output + a chosen exit code), so this test
# needs no rig, no node_modules, no browser. The oracle is INDEPENDENT of the
# script's stdout: assertions read the actual JSONL file content.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SCRIPT="$REPO_ROOT/dev/rig-smoke/run-golden.sh"

if [ ! -x "$SCRIPT" ]; then
  echo "FAIL: $SCRIPT not executable" >&2
  exit 1
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

JSONL="$TMP/rig-smoke.jsonl"
TEE="$TMP/tee-out.txt"

# ── Stub 1: failing playwright — '1 failed' in output, exit 1 ─────────────
cat > "$TMP/pw-fail.sh" <<'EOF'
#!/usr/bin/env bash
echo "Running 14 tests using 4 workers"
echo "  1) [chromium] › specs/v3/00-smoke.spec.ts:42:5 › @golden broken pipeline"
echo "  1 failed"
echo "  13 passed (95s)"
exit 1
EOF
chmod +x "$TMP/pw-fail.sh"

# ── Stub 2: passing playwright — 'N passed', exit 0 ───────────────────────
cat > "$TMP/pw-pass.sh" <<'EOF'
#!/usr/bin/env bash
echo "Running 14 tests using 4 workers"
echo "  14 passed (90s)"
exit 0
EOF
chmod +x "$TMP/pw-pass.sh"

# Pre-seed the jsonl with an existing loop-written line to prove append
# semantics (the script must NEVER truncate prior telemetry).
PRESEED='{"ts":"2026-07-01T00:00:00Z","passed":14,"failed":0,"durationMs":1}'
echo "$PRESEED" > "$JSONL"

# The #44 freshness probe and #84 pre-warm are skipped here to keep this test
# hermetic (no docker/rig dependency) — they have their own dedicated tests:
# test_check_rig_freshness.sh / test_prewarm_worker.sh / test_run_golden_freshness.sh.
export RIG_SMOKE_SKIP_FRESHNESS=1
export RIG_SMOKE_SKIP_PREWARM=1

# ── Case 1: ADVERSARIAL — failing run → non-zero exit AND line appended ───
set +e
RIG_SMOKE_PLAYWRIGHT_CMD="$TMP/pw-fail.sh" \
  RIG_SMOKE_JSONL="$JSONL" \
  RIG_SMOKE_TEE="$TEE" \
  bash "$SCRIPT" > "$TMP/fail.out" 2>&1
RC=$?
set -e
if [ "$RC" -eq 0 ]; then
  echo "FAIL: failing playwright run returned 0; a red run would count as green" >&2
  exit 1
fi
# Independent oracle: the JSONL file itself, not the script's echo output.
LINES=$(wc -l < "$JSONL" | tr -d ' ')
if [ "$LINES" -ne 2 ]; then
  echo "FAIL: expected 2 jsonl lines after failing run (preseed + new), got $LINES" >&2
  cat "$JSONL" >&2
  exit 1
fi
LAST=$(tail -n 1 "$JSONL")
if ! printf '%s' "$LAST" | grep -q '"failed":1'; then
  echo "FAIL: failing run's jsonl line does not record failed=1: $LAST" >&2
  exit 1
fi
if ! printf '%s' "$LAST" | grep -q '"passed":13'; then
  echo "FAIL: failing run's jsonl line does not record passed=13: $LAST" >&2
  exit 1
fi
echo "ok: ADVERSARIAL — failing run exits non-zero ($RC) AND appends telemetry"

# ── Case 2: passing run → exit 0 AND line appended ────────────────────────
if ! RIG_SMOKE_PLAYWRIGHT_CMD="$TMP/pw-pass.sh" \
    RIG_SMOKE_JSONL="$JSONL" \
    RIG_SMOKE_TEE="$TEE" \
    bash "$SCRIPT" > "$TMP/pass.out" 2>&1; then
  echo "FAIL: passing playwright run returned non-zero" >&2
  cat "$TMP/pass.out" >&2
  exit 1
fi
LINES=$(wc -l < "$JSONL" | tr -d ' ')
if [ "$LINES" -ne 3 ]; then
  echo "FAIL: expected 3 jsonl lines after passing run, got $LINES" >&2
  cat "$JSONL" >&2
  exit 1
fi
LAST=$(tail -n 1 "$JSONL")
if ! printf '%s' "$LAST" | grep -q '"passed":14'; then
  echo "FAIL: passing run's jsonl line does not record passed=14: $LAST" >&2
  exit 1
fi
if ! printf '%s' "$LAST" | grep -q '"failed":0'; then
  echo "FAIL: passing run's jsonl line does not record failed=0: $LAST" >&2
  exit 1
fi
echo "ok: passing run exits zero AND appends telemetry"

# ── Case 3: append-only — the pre-seeded loop line survived both runs ─────
if [ "$(head -n 1 "$JSONL")" != "$PRESEED" ]; then
  echo "FAIL: pre-existing telemetry line was truncated/rewritten" >&2
  cat "$JSONL" >&2
  exit 1
fi
echo "ok: pre-existing telemetry preserved (append-only, never truncate)"

# ── Case 4: durationMs is a real measured integer ─────────────────────────
if ! printf '%s' "$LAST" | grep -qE '"durationMs":[0-9]+'; then
  echo "FAIL: durationMs missing or non-numeric: $LAST" >&2
  exit 1
fi
echo "ok: durationMs recorded as an integer"

echo ""
echo "PASS: all run-golden.sh cases (including adversarial red-run guard)"
