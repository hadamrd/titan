#!/usr/bin/env bash
# check-3-consecutive.sh — V1-shippable-bar (#1048) marker emitter.
#
# The CONSTITUTION §1 V1-shippable bar requires `task rig:smoke` to land
# ≥RIG_SMOKE_GOLDEN_MIN passing @golden specs with zero failures across
# THREE consecutive runs. Without this helper, declaring the bar met is
# a manual eyeball of docs/ops/rig-smoke.jsonl, which is exactly the
# kind of step a loop tick under pressure skips.
#
# What this script does:
#   1. Reads the last 3 NON-MARKER lines of the jsonl (markers are
#      identified by the presence of a `"marker":` key — they record
#      *that* the bar was met, they don't contribute to the next sample).
#   2. Each must satisfy: passed >= RIG_SMOKE_GOLDEN_MIN (default 12)
#      AND failed == 0. Anything else => exit 1 with a diagnostic.
#   3. On success, appends ONE marker line of the form:
#        {"ts":"<utc>","marker":"3-consecutive-green target met YYYY-MM-DD"}
#      to the jsonl and prints it to stdout. Subsequent invocations are
#      idempotent: if the latest marker line already covers today's date
#      and the same 3 runs, no duplicate marker is appended.
#
# Why we count "passed >= MIN" rather than "passed == 14": the bar is
# "≥12/14", not "14/14". This matches the wording in CONSTITUTION §1 and
# the existing golden-guard floor.
#
# Usage:
#   check-3-consecutive.sh [jsonl-path]
#   RIG_SMOKE_GOLDEN_MIN=12 ./check-3-consecutive.sh docs/ops/rig-smoke.jsonl
#
# Exit codes:
#   0 — bar is met (marker appended or already present for today's run trio)
#   1 — bar NOT met (last 3 non-marker runs don't all satisfy the threshold)
#   2 — config error (jsonl missing, fewer than 3 non-marker entries, etc.)
set -euo pipefail

JSONL="${1:-docs/ops/rig-smoke.jsonl}"
MIN="${RIG_SMOKE_GOLDEN_MIN:-12}"

if [ ! -f "$JSONL" ]; then
  echo "[3-consecutive] jsonl not found: $JSONL" >&2
  exit 2
fi

# Strip marker lines (anything with `"marker":`) before taking the tail.
NON_MARKER=$(grep -v '"marker":' "$JSONL" || true)
COUNT=$(printf '%s\n' "$NON_MARKER" | grep -c '^{' || true)
if [ "$COUNT" -lt 3 ]; then
  echo "[3-consecutive] need 3 non-marker runs, found $COUNT" >&2
  exit 2
fi

LAST3=$(printf '%s\n' "$NON_MARKER" | grep '^{' | tail -n 3)

# Parse each line — no jq dependency (the loop runner host may not have it).
# Each line is a flat JSON object so a regex pull is safe.
parse_field() {
  local line="$1" key="$2"
  printf '%s' "$line" | grep -oE "\"$key\":[^,}]*" | head -n1 | sed -E "s/\"$key\"://"
}

BAR_MET=1
DIAG=""
i=0
while IFS= read -r line; do
  i=$((i + 1))
  passed=$(parse_field "$line" passed)
  failed=$(parse_field "$line" failed)
  if [ -z "$passed" ] || [ -z "$failed" ]; then
    BAR_MET=0
    DIAG="$DIAG\n  run #$i: malformed line, missing passed/failed: $line"
    continue
  fi
  if [ "$failed" -ne 0 ] || [ "$passed" -lt "$MIN" ]; then
    BAR_MET=0
    DIAG="$DIAG\n  run #$i: passed=$passed failed=$failed (need passed>=$MIN, failed==0)"
  fi
done <<< "$LAST3"

if [ "$BAR_MET" -ne 1 ]; then
  echo "[3-consecutive] FAIL: last 3 runs do not all meet the bar." >&2
  printf '%b\n' "$DIAG" >&2
  exit 1
fi

# Bar met. Idempotency: if the very last line of the file is already a
# marker emitted today, don't append a duplicate (keeps loop reruns from
# cluttering the log).
TODAY=$(date -u +%Y-%m-%d)
LAST_LINE=$(tail -n 1 "$JSONL")
if printf '%s' "$LAST_LINE" | grep -q "\"marker\":\"3-consecutive-green target met $TODAY\""; then
  echo "[3-consecutive] OK: bar already marked met for $TODAY (no-op)."
  exit 0
fi

TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
MARKER="{\"ts\":\"$TS\",\"marker\":\"3-consecutive-green target met $TODAY\"}"
echo "$MARKER" >> "$JSONL"
echo "$MARKER"
echo "[3-consecutive] OK: bar met; marker appended to $JSONL"
