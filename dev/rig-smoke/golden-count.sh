#!/usr/bin/env bash
# golden-count.sh — print the number of @golden-tagged Playwright tests.
#
# Shared helper (#45): the count used to live inline in
# check-golden-count.sh, but run-golden.sh now needs the same number to
# derive a suite time budget (TITAN_GLOBAL_TIMEOUT_MS) that scales with
# the golden surface instead of a magic 20-minute constant. One counting
# implementation, two consumers — the guard and the budget can never
# disagree about what "the golden set" is.
#
# Counts `test(...)` / `test.describe(...)` / `describe(...)` titles that
# contain `@golden` under SPECS_DIR (default e2e/specs). We count test
# invocations, not files, because one file can hold many @golden tests.
#
# Output: the count (a bare integer) on stdout.
# Exit codes: 0 on success, 2 if SPECS_DIR does not exist.
set -euo pipefail

SPECS_DIR="${SPECS_DIR:-e2e/specs}"

if [ ! -d "$SPECS_DIR" ]; then
  echo "[golden-count] specs dir not found: $SPECS_DIR" >&2
  exit 2
fi

# grep exits 1 on zero matches; under pipefail that would abort the whole
# script instead of printing 0, so neutralize it inside the subshell.
COUNT=$( (grep -rhE '(test|test\.describe|describe)\([^)]*@golden' "$SPECS_DIR" || true) \
  | wc -l | tr -d ' ')

echo "$COUNT"
