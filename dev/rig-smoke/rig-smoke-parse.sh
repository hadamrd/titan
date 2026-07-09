#!/usr/bin/env bash
# Parse a captured playwright "line" reporter tee'd file and emit the
# canonical one-line JSON appended to docs/operations/rig-smoke.jsonl.
#
# Why this is its own script (#1048):
#   The acceptance criterion for the V1-shippable bar requires that a
#   deliberately-broken spec MUST make `task rig:smoke` exit non-zero.
#   The parse+exit logic used to live inline in Taskfile.yml, which is
#   not directly unit-testable. Lifting it here lets the adversarial
#   guard test feed a synthetic "1 failed" tee file and assert non-zero
#   exit, proving the bar can detect real breakage without needing the
#   full rig + playwright runtime online.
#
# Usage:
#   rig-smoke-parse.sh <tee-file> <playwright-exit-code> <duration-ms> [jsonl-out]
#
# Env:
#   RIG_SMOKE_RIG_SHA_MISMATCH  "true" when the #44 stale-rig probe
#     (check-rig-freshness.sh) found the running titan-server image's baked
#     git SHA differs from checkout HEAD (or provenance is unprovable).
#     Recorded as the ADDITIVE `rigShaMismatch` field; anything other than
#     "true" (unset, empty, garbage) records false. Existing fields keep
#     their exact names/order — parse-compat with check-3-consecutive.sh.
#
# Side effects:
#   - Echoes the JSON line to stdout.
#   - If jsonl-out is given, appends the JSON line there.
#   - Exits with the playwright exit code (so callers propagate failure).
set -euo pipefail

if [ $# -lt 3 ]; then
  echo "usage: $0 <tee-file> <playwright-exit-code> <duration-ms> [jsonl-out]" >&2
  exit 2
fi

TEE="$1"
PW_EXIT="$2"
DUR="$3"
OUT="${4:-}"

if [ ! -f "$TEE" ]; then
  echo "[rig-smoke-parse] tee file not found: $TEE" >&2
  exit 2
fi

PASSED=$(grep -oE '[0-9]+ passed' "$TEE" | tail -n1 | grep -oE '[0-9]+' || echo 0)
FAILED=$(grep -oE '[0-9]+ failed' "$TEE" | tail -n1 | grep -oE '[0-9]+' || echo 0)
# "X did not run" is playwright's amputation signature (#45): the suite hit
# globalTimeout and never scheduled X specs. 0 when absent. Recording it in
# the telemetry makes an amputated run machine-visible so the 3-consecutive
# marker (check-3-consecutive.sh) can refuse to count it as green.
DID_NOT_RUN=$(grep -oE '[0-9]+ did not run' "$TEE" | tail -n1 | grep -oE '[0-9]+' || echo 0)
# #44 stale-rig telemetry — additive field, sanitized to a strict boolean so
# a caller typo can never inject arbitrary JSON into the line.
if [ "${RIG_SMOKE_RIG_SHA_MISMATCH:-false}" = "true" ]; then
  RIG_SHA_MISMATCH=true
else
  RIG_SHA_MISMATCH=false
fi
TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
LINE="{\"ts\":\"$TS\",\"passed\":$PASSED,\"failed\":$FAILED,\"did_not_run\":$DID_NOT_RUN,\"durationMs\":$DUR,\"rigShaMismatch\":$RIG_SHA_MISMATCH}"

echo "$LINE"
if [ -n "$OUT" ]; then
  mkdir -p "$(dirname "$OUT")"
  echo "$LINE" >> "$OUT"
fi

exit "$PW_EXIT"
