#!/usr/bin/env bash
# Integration-shaped unit test for the #44 stale-jar guard as wired through
# dev/rig-smoke/run-golden.sh.
#
# Acceptance criterion under test (issue #44, adversarial both ways):
#   - Reproduce the footgun: a titan-server image whose baked git SHA differs
#     from checkout HEAD → the smoke OUTPUT carries the loud STALE RIG line
#     AND the appended telemetry line carries "rigShaMismatch":true.
#   - Rebuild fresh (sha == HEAD) → the flag does NOT appear
#     ("rigShaMismatch":false) and no warning is printed.
#   - Parse-compat: the pre-#44 fields (passed/failed/did_not_run/durationMs)
#     survive unchanged so check-3-consecutive.sh keeps counting greens.
#
# docker is stubbed via RIG_SMOKE_DOCKER_CMD, playwright via
# RIG_SMOKE_PLAYWRIGHT_CMD (the test_run_golden.sh pattern); the oracle is the
# JSONL file content, independent of the script's echo output.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SCRIPT="$REPO_ROOT/dev/rig-smoke/run-golden.sh"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

JSONL="$TMP/rig-smoke.jsonl"
TEE="$TMP/tee-out.txt"

# Green playwright stub — freshness must be orthogonal to pass/fail.
cat > "$TMP/pw-pass.sh" <<'EOF'
#!/usr/bin/env bash
echo "Running 14 tests using 2 workers"
echo "  14 passed (90s)"
exit 0
EOF
chmod +x "$TMP/pw-pass.sh"

# docker stub: a titan-server container whose image label sha comes from
# $TMP/label.txt.
cat > "$TMP/docker-stub.sh" <<EOF
#!/usr/bin/env bash
case "\$1" in
  ps)      echo "local-titan-server-1" ;;
  inspect) cat "$TMP/label.txt" ;;
  *)       exit 1 ;;
esac
EOF
chmod +x "$TMP/docker-stub.sh"

run_smoke() { # <expected-sha>
  RIG_SMOKE_PLAYWRIGHT_CMD="$TMP/pw-pass.sh" \
    RIG_SMOKE_JSONL="$JSONL" \
    RIG_SMOKE_TEE="$TEE" \
    RIG_SMOKE_DOCKER_CMD="$TMP/docker-stub.sh" \
    RIG_SMOKE_EXPECTED_SHA="$1" \
    RIG_SMOKE_SKIP_PREWARM=1 \
    bash "$SCRIPT"
}

# ── Case 1: ADVERSARIAL — stale image → warning in output + telemetry flag ─
echo "0000000000000000000000000000000000000stale" > "$TMP/label.txt"
if ! run_smoke "1111111111111111111111111111111111111head" > "$TMP/stale.out" 2>&1; then
  echo "FAIL: stale rig must be a WARNING — the smoke run itself must stay green" >&2
  cat "$TMP/stale.out" >&2
  exit 1
fi
if ! grep -q 'STALE RIG' "$TMP/stale.out"; then
  echo "FAIL: stale image did not surface the STALE RIG warning in smoke output:" >&2
  cat "$TMP/stale.out" >&2
  exit 1
fi
LAST=$(tail -n 1 "$JSONL")
if ! printf '%s' "$LAST" | grep -q '"rigShaMismatch":true'; then
  echo "FAIL: stale run's telemetry line lacks rigShaMismatch=true: $LAST" >&2
  exit 1
fi
echo "ok: ADVERSARIAL — stale image → STALE RIG warning + rigShaMismatch=true (run stays green)"

# ── Case 2: fresh image → no warning, flag false ────────────────────────────
echo "2222222222222222222222222222222222222same" > "$TMP/label.txt"
run_smoke "2222222222222222222222222222222222222same" > "$TMP/fresh.out" 2>&1
if grep -q 'STALE RIG' "$TMP/fresh.out"; then
  echo "FAIL: fresh image must not warn:" >&2
  cat "$TMP/fresh.out" >&2
  exit 1
fi
LAST=$(tail -n 1 "$JSONL")
if ! printf '%s' "$LAST" | grep -q '"rigShaMismatch":false'; then
  echo "FAIL: fresh run's telemetry line lacks rigShaMismatch=false: $LAST" >&2
  exit 1
fi
echo "ok: fresh image → no warning, rigShaMismatch=false"

# ── Case 3: parse-compat — pre-#44 fields intact on both lines ──────────────
for key in '"passed":14' '"failed":0' '"did_not_run":0' '"durationMs":'; do
  if [ "$(grep -c "$key" "$JSONL")" -ne 2 ]; then
    echo "FAIL: telemetry field $key missing from a line — parse-compat broken:" >&2
    cat "$JSONL" >&2
    exit 1
  fi
done
# check-3-consecutive.sh must still parse these lines (needs a 3rd green).
tail -n 1 "$JSONL" >> "$JSONL"
if ! bash "$REPO_ROOT/dev/sprint-loop/check-3-consecutive.sh" "$JSONL" > /dev/null; then
  echo "FAIL: check-3-consecutive.sh cannot count runs carrying rigShaMismatch" >&2
  exit 1
fi
echo "ok: parse-compat — existing fields intact; check-3-consecutive.sh still counts greens"

echo ""
echo "PASS: all run-golden.sh freshness cases (#44 stale-jar guard end-to-end)"
