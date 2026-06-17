#!/usr/bin/env bash
# Adversarial unit test for dev/sprint-loop/check-3-consecutive.sh (#1048).
#
# Acceptance criterion under test:
#   The V1-shippable bar (CONSTITUTION §1) requires three consecutive
#   green rig-smoke runs before declaring the bar met. This helper is
#   the gate that turns the jsonl log into an enforceable claim. If it
#   ever flips its decision under bad input — counts a marker line as a
#   real run, swallows a failed-row, or appends a duplicate marker — the
#   loop ships a lie. These tests pin every case we've seen in the wild.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SCRIPT="$REPO_ROOT/dev/sprint-loop/check-3-consecutive.sh"

if [ ! -f "$SCRIPT" ]; then
  echo "FAIL: $SCRIPT missing" >&2
  exit 1
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

green='{"ts":"2026-05-28T10:00:00Z","passed":13,"failed":0,"durationMs":120000}'
red='{"ts":"2026-05-28T10:05:00Z","passed":11,"failed":2,"durationMs":121000}'
under='{"ts":"2026-05-28T10:10:00Z","passed":11,"failed":0,"durationMs":121000}'

# ── Case 1: 3 greens → bar met, marker appended, exit 0 ──────────────────
printf '%s\n%s\n%s\n' "$green" "$green" "$green" > "$TMP/g.jsonl"
bash "$SCRIPT" "$TMP/g.jsonl" >/dev/null
if ! tail -n 1 "$TMP/g.jsonl" | grep -q '"marker":"3-consecutive-green target met'; then
  echo "FAIL: 3-greens did not append marker line. tail=$(tail -n1 $TMP/g.jsonl)" >&2
  exit 1
fi
echo "ok: 3 green runs → marker appended"

# ── Case 2: idempotency — re-running does NOT append a 2nd marker ────────
BEFORE=$(wc -l < "$TMP/g.jsonl" | tr -d ' ')
bash "$SCRIPT" "$TMP/g.jsonl" >/dev/null
AFTER=$(wc -l < "$TMP/g.jsonl" | tr -d ' ')
if [ "$BEFORE" -ne "$AFTER" ]; then
  echo "FAIL: re-run appended a duplicate marker (before=$BEFORE after=$AFTER)" >&2
  exit 1
fi
echo "ok: idempotent — duplicate marker not appended on re-run"

# ── Case 3: ADVERSARIAL — one failure in last 3 MUST refuse marker ───────
printf '%s\n%s\n%s\n' "$green" "$red" "$green" > "$TMP/r.jsonl"
set +e
bash "$SCRIPT" "$TMP/r.jsonl" >/dev/null 2>&1
RC=$?
set -e
if [ "$RC" -eq 0 ]; then
  echo "FAIL: a failed run in the trio must refuse to mark the bar met" >&2
  exit 1
fi
if grep -q '"marker":' "$TMP/r.jsonl"; then
  echo "FAIL: a marker was appended despite a failed run in the trio" >&2
  exit 1
fi
echo "ok: ADVERSARIAL — failed run in trio refuses marker (no false-green)"

# ── Case 4: ADVERSARIAL — passed<MIN MUST refuse marker (no shrink) ──────
printf '%s\n%s\n%s\n' "$green" "$green" "$under" > "$TMP/u.jsonl"
set +e
bash "$SCRIPT" "$TMP/u.jsonl" >/dev/null 2>&1
RC=$?
set -e
if [ "$RC" -eq 0 ]; then
  echo "FAIL: passed<MIN (11<12) should not mark the bar met" >&2
  exit 1
fi
echo "ok: ADVERSARIAL — passed<MIN refuses marker"

# ── Case 5: marker lines in history are IGNORED (don't count as a run) ───
# Three greens + one OLD marker — re-running must still consider only the
# three real runs (not "2 runs + 1 marker"). If the marker leaked into the
# "real run" count we'd see exit 2 ("need 3, found 2").
old_marker='{"ts":"2026-05-27T09:00:00Z","marker":"3-consecutive-green target met 2026-05-27"}'
printf '%s\n%s\n%s\n%s\n' "$old_marker" "$green" "$green" "$green" > "$TMP/m.jsonl"
bash "$SCRIPT" "$TMP/m.jsonl" >/dev/null
if ! tail -n 1 "$TMP/m.jsonl" | grep -q '"marker":"3-consecutive-green target met'; then
  echo "FAIL: marker lines in history should be skipped, not counted as runs" >&2
  exit 1
fi
echo "ok: historical marker lines do not pollute the 3-run window"

# ── Case 6: fewer than 3 non-marker runs → exit 2 (loud config error) ────
printf '%s\n%s\n' "$green" "$green" > "$TMP/short.jsonl"
set +e
bash "$SCRIPT" "$TMP/short.jsonl" >/dev/null 2>&1
RC=$?
set -e
if [ "$RC" -ne 2 ]; then
  echo "FAIL: <3 runs should exit 2, got $RC" >&2
  exit 1
fi
echo "ok: <3 runs exits 2 (config error)"

# ── Case 7: missing jsonl → exit 2 ───────────────────────────────────────
set +e
bash "$SCRIPT" "$TMP/does-not-exist.jsonl" >/dev/null 2>&1
RC=$?
set -e
if [ "$RC" -ne 2 ]; then
  echo "FAIL: missing jsonl should exit 2, got $RC" >&2
  exit 1
fi
echo "ok: missing jsonl exits 2"

echo ""
echo "PASS: all check-3-consecutive.sh cases (including adversarial false-green guards)"
