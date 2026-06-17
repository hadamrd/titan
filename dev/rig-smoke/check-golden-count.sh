#!/usr/bin/env bash
# Guard the V1-shippable-bar (#1048): refuse to run rig:smoke if the @golden
# tagged test count has silently shrunk below the contract minimum.
#
# Why this exists:
#   The CONSTITUTION §1 V1-shippable-bar requires `task rig:smoke` to keep
#   ≥12/14 @golden specs green for 3 consecutive ticks. Without this guard,
#   a contributor under time pressure can quietly delete `@golden` from a
#   flaky spec, the smoke run goes "100% green", and the bar erodes.
#
# Behavior:
#   - Counts `test(...)` and `test.describe(...)` titles that contain `@golden`
#     under e2e/specs/.
#   - Fails (exit 1) when the count is less than RIG_SMOKE_GOLDEN_MIN
#     (default 12). Override via env if intentionally tightening.
#
# Override the dir for unit-testing this script via SPECS_DIR.
set -euo pipefail

SPECS_DIR="${SPECS_DIR:-e2e/specs}"
MIN="${RIG_SMOKE_GOLDEN_MIN:-12}"

if [ ! -d "$SPECS_DIR" ]; then
  echo "[golden-guard] specs dir not found: $SPECS_DIR" >&2
  exit 2
fi

# Count: lines matching `test(...@golden...)` or `test.describe(...@golden...)`.
# We count test invocations, not files, because one file can hold many @golden
# tests and the bar is a per-test count.
COUNT=$(grep -rhE '(test|test\.describe|describe)\([^)]*@golden' "$SPECS_DIR" \
  | wc -l | tr -d ' ')

if [ "$COUNT" -lt "$MIN" ]; then
  echo "[golden-guard] FAIL: only $COUNT @golden tests found in $SPECS_DIR (minimum $MIN)" >&2
  echo "[golden-guard] V1-shippable-bar (CONSTITUTION §1) requires the @golden surface stays ≥$MIN." >&2
  echo "[golden-guard] If you intentionally removed coverage, file a follow-up issue and update the spec body — do not just delete the tag." >&2
  exit 1
fi

echo "[golden-guard] OK: $COUNT @golden tests in $SPECS_DIR (minimum $MIN)"
