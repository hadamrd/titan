#!/usr/bin/env bash
# Adversarial unit test for dev/rig-smoke/rig-smoke-parse.sh (#1048).
#
# Acceptance criterion under test:
#   "a deliberately-broken pipelineScript in one fixture spec MUST cause
#    the smoke run to fail (proves the bar can detect real breakage)"
#
# The full pipelineScript path requires the rig + playwright + a real
# build; what we lock in here is the bar's *exit-code propagation*
# logic. If a playwright run returns non-zero, rig-smoke-parse.sh MUST
# return non-zero — otherwise the task can silently swallow a failure
# and the V1-shippable bar (CONSTITUTION §1) becomes a lie.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SCRIPT="$REPO_ROOT/dev/rig-smoke/rig-smoke-parse.sh"

if [ ! -x "$SCRIPT" ]; then
  echo "FAIL: $SCRIPT not executable" >&2
  exit 1
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# ── Case 1: synthetic "all green" tee → exit 0 + passed parsed ────────────
cat > "$TMP/green.txt" <<'EOF'
Running 14 tests using 4 workers
  14 passed (90s)
EOF
OUT=$(bash "$SCRIPT" "$TMP/green.txt" 0 12345 "$TMP/jsonl")
if ! echo "$OUT" | grep -q '"passed":14'; then
  echo "FAIL: green case did not parse passed=14: $OUT" >&2
  exit 1
fi
if ! echo "$OUT" | grep -q '"failed":0'; then
  echo "FAIL: green case did not parse failed=0: $OUT" >&2
  exit 1
fi
echo "ok: green run parsed correctly"

# ── Case 2: ADVERSARIAL — broken spec → playwright exit 1 MUST propagate ──
cat > "$TMP/red.txt" <<'EOF'
Running 14 tests using 4 workers
  1) [chromium] › specs/v3/00-smoke.spec.ts:42:5 › @golden broken pipeline
     Error: deliberately broken pipelineScript — should fail the bar
  1 failed
  13 passed (95s)
EOF
set +e
bash "$SCRIPT" "$TMP/red.txt" 1 54321 "$TMP/jsonl" > "$TMP/red.out"
RC=$?
set -e
if [ "$RC" -eq 0 ]; then
  echo "FAIL: broken-spec case returned 0; the V1 bar would silently swallow a real failure" >&2
  exit 1
fi
if ! grep -q '"failed":1' "$TMP/red.out"; then
  echo "FAIL: broken-spec case did not parse failed=1: $(cat $TMP/red.out)" >&2
  exit 1
fi
if ! grep -q '"passed":13' "$TMP/red.out"; then
  echo "FAIL: broken-spec case did not parse passed=13: $(cat $TMP/red.out)" >&2
  exit 1
fi
echo "ok: ADVERSARIAL — broken-spec exit code (1) propagates through parser"

# ── Case 3: jsonl append actually wrote the line ─────────────────────────
if ! [ -f "$TMP/jsonl" ]; then
  echo "FAIL: jsonl file was not created" >&2
  exit 1
fi
LINES=$(wc -l < "$TMP/jsonl" | tr -d ' ')
if [ "$LINES" -ne 2 ]; then
  echo "FAIL: expected 2 lines in jsonl, got $LINES" >&2
  exit 1
fi
echo "ok: jsonl append wrote both runs"

# ── Case 4: missing tee file → exit 2 (config error, distinct from test fail)
set +e
bash "$SCRIPT" "$TMP/does-not-exist" 0 1 >/dev/null 2>&1
RC=$?
set -e
if [ "$RC" -ne 2 ]; then
  echo "FAIL: missing tee file should exit 2, got $RC" >&2
  exit 1
fi
echo "ok: missing tee file exits 2"

# ── Case 5: malformed tee (no passed/failed lines) → zeros, propagates exit
echo "garbage" > "$TMP/junk.txt"
set +e
OUT=$(bash "$SCRIPT" "$TMP/junk.txt" 7 1)
RC=$?
set -e
if [ "$RC" -ne 7 ]; then
  echo "FAIL: malformed tee should still propagate playwright exit code, got $RC" >&2
  exit 1
fi
if ! echo "$OUT" | grep -q '"passed":0'; then
  echo "FAIL: malformed tee should default passed=0: $OUT" >&2
  exit 1
fi
echo "ok: malformed tee defaults zeros + propagates exit code"

# ── Case 6: absent 'did not run' → did_not_run:0 in the JSON ──────────────
OUT=$(bash "$SCRIPT" "$TMP/green.txt" 0 12345)
if ! echo "$OUT" | grep -q '"did_not_run":0'; then
  echo "FAIL: healthy tee should record did_not_run=0: $OUT" >&2
  exit 1
fi
echo "ok: healthy tee records did_not_run=0"

# ── Case 7: ADVERSARIAL — amputated suite ('23 did not run') is machine-visible
# The #45 failure mode: globalTimeout kills the suite, playwright reports
# 'X did not run', yet passed/failed alone can look green. The JSON MUST
# carry the amputation count so check-3-consecutive.sh can refuse the run.
cat > "$TMP/amputated.txt" <<'EOF'
Running 43 tests using 2 workers
  14 passed (20.0m)
  23 did not run
Timed out waiting 1200s for the test suite to run
EOF
OUT=$(bash "$SCRIPT" "$TMP/amputated.txt" 0 1200000 "$TMP/jsonl")
if ! echo "$OUT" | grep -q '"did_not_run":23'; then
  echo "FAIL: amputated tee did not record did_not_run=23: $OUT" >&2
  exit 1
fi
if ! echo "$OUT" | grep -q '"passed":14'; then
  echo "FAIL: amputated tee did not parse passed=14: $OUT" >&2
  exit 1
fi
# The appended jsonl line must carry the same field (independent oracle).
if ! tail -n 1 "$TMP/jsonl" | grep -q '"did_not_run":23'; then
  echo "FAIL: appended jsonl line lost did_not_run: $(tail -n1 "$TMP/jsonl")" >&2
  exit 1
fi
echo "ok: ADVERSARIAL — amputated run ('23 did not run') recorded as did_not_run=23"

# ── Case 8: #44 — rigShaMismatch defaults to false when env unset ─────────
OUT=$(bash "$SCRIPT" "$TMP/green.txt" 0 12345)
if ! echo "$OUT" | grep -q '"rigShaMismatch":false'; then
  echo "FAIL: unset RIG_SMOKE_RIG_SHA_MISMATCH should record rigShaMismatch=false: $OUT" >&2
  exit 1
fi
echo "ok: rigShaMismatch defaults to false"

# ── Case 9: #44 — env 'true' records the stale-rig flag in the telemetry ──
OUT=$(RIG_SMOKE_RIG_SHA_MISMATCH=true bash "$SCRIPT" "$TMP/green.txt" 0 12345 "$TMP/jsonl")
if ! echo "$OUT" | grep -q '"rigShaMismatch":true'; then
  echo "FAIL: RIG_SMOKE_RIG_SHA_MISMATCH=true not recorded: $OUT" >&2
  exit 1
fi
if ! tail -n 1 "$TMP/jsonl" | grep -q '"rigShaMismatch":true'; then
  echo "FAIL: appended jsonl line lost rigShaMismatch: $(tail -n1 "$TMP/jsonl")" >&2
  exit 1
fi
echo "ok: stale-rig flag (rigShaMismatch=true) recorded in output + jsonl"

# ── Case 10: ADVERSARIAL — garbage env can never inject JSON into the line ─
OUT=$(RIG_SMOKE_RIG_SHA_MISMATCH='},"passed":999,"x":{' bash "$SCRIPT" "$TMP/green.txt" 0 12345)
if ! echo "$OUT" | grep -q '"rigShaMismatch":false'; then
  echo "FAIL: garbage RIG_SMOKE_RIG_SHA_MISMATCH must sanitize to false: $OUT" >&2
  exit 1
fi
if echo "$OUT" | grep -q '"passed":999'; then
  echo "FAIL: env value injected JSON into the telemetry line: $OUT" >&2
  exit 1
fi
echo "ok: ADVERSARIAL — non-boolean env sanitized (no JSON injection)"

echo ""
echo "PASS: all rig-smoke-parse.sh cases (including adversarial breakage guard)"
