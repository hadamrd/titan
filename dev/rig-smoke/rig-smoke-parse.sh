#!/usr/bin/env bash
# Parse a captured playwright "line" reporter tee'd file and emit the
# canonical one-line JSON appended to docs/ops/rig-smoke.jsonl.
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
TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
LINE="{\"ts\":\"$TS\",\"passed\":$PASSED,\"failed\":$FAILED,\"durationMs\":$DUR}"

echo "$LINE"
if [ -n "$OUT" ]; then
  mkdir -p "$(dirname "$OUT")"
  echo "$LINE" >> "$OUT"
fi

exit "$PW_EXIT"
