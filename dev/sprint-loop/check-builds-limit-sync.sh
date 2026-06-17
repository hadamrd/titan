#!/usr/bin/env bash
# check-builds-limit-sync.sh — cross-package page-size parity guard (#1200).
#
# Why this exists:
#   The auth golden-path canary (e2e/specs/v3/56-golden-path-auth.spec.ts) calls
#   GET /api/v1/builds with the SAME page size the SPA itself requests, so the
#   `total` it reads is a faithful mirror of the real SPA request. The SPA's page
#   size is `const PAGE_SIZE` in titan-ui/src/routes/builds/index.tsx; the spec
#   pins its own default to that value. But e2e/ is a SEPARATE pnpm workspace
#   from titan-ui/, so the spec can't `import` the constant — it can only copy the
#   number behind a comment. A comment-as-contract silently DRIFTS the day someone
#   changes PAGE_SIZE and not the spec: the canary then probes a different page
#   than the SPA, and the comment lies. (The sev3 review thread on #1204.)
#
#   This script makes the parity ENFORCED instead of hoped-for: it greps the
#   value out of BOTH files and fails the PR gate (task verify / ci:verify) if
#   they disagree, naming both sides. Change one, the gate makes you change the
#   other.
#
# What it does:
#   1. Extracts the integer from `const PAGE_SIZE = <n>` in builds/index.tsx.
#   2. Extracts the integer default from the spec's
#      `Number(process.env.TITAN_BUILDS_LIMIT ?? '<n>')`.
#   3. Exits 1 (naming both) if they differ or either can't be parsed.
#
# Usage:   check-builds-limit-sync.sh
# Exit:    0 — values match;  1 — mismatch / parse failure
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

UI_FILE="$REPO_ROOT/titan-ui/src/routes/builds/index.tsx"
SPEC_FILE="$REPO_ROOT/e2e/specs/v3/56-golden-path-auth.spec.ts"

for f in "$UI_FILE" "$SPEC_FILE"; do
  if [ ! -f "$f" ]; then
    echo "[builds-limit-sync] FAIL: expected file not found: $f" >&2
    echo "[builds-limit-sync] (did builds/index.tsx or the auth spec move? update this check.)" >&2
    exit 1
  fi
done

# SPA source of truth: `const PAGE_SIZE = 100`
UI_VAL="$(sed -nE 's/^[[:space:]]*const[[:space:]]+PAGE_SIZE[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' "$UI_FILE" | head -n1)"
# Spec default: `Number(process.env.TITAN_BUILDS_LIMIT ?? '100')`
SPEC_VAL="$(sed -nE "s/.*TITAN_BUILDS_LIMIT[[:space:]]*\?\?[[:space:]]*'([0-9]+)'.*/\1/p" "$SPEC_FILE" | head -n1)"

if [ -z "$UI_VAL" ]; then
  echo "[builds-limit-sync] FAIL: could not parse 'const PAGE_SIZE = <n>' from $UI_FILE" >&2
  echo "[builds-limit-sync] (was the constant renamed or its form changed? update this check.)" >&2
  exit 1
fi
if [ -z "$SPEC_VAL" ]; then
  echo "[builds-limit-sync] FAIL: could not parse the TITAN_BUILDS_LIMIT default from $SPEC_FILE" >&2
  echo "[builds-limit-sync] (expected: Number(process.env.TITAN_BUILDS_LIMIT ?? '<n>'))" >&2
  exit 1
fi

if [ "$UI_VAL" != "$SPEC_VAL" ]; then
  echo "[builds-limit-sync] FAIL: SPA page size and the auth-spec default DIVERGED." >&2
  echo "  titan-ui PAGE_SIZE           = $UI_VAL   ($UI_FILE)" >&2
  echo "  spec TITAN_BUILDS_LIMIT def. = $SPEC_VAL   ($SPEC_FILE)" >&2
  echo "[builds-limit-sync] The auth canary must probe the SAME page the SPA does (#1200)." >&2
  echo "[builds-limit-sync] Update the spec default to match PAGE_SIZE (or vice-versa)." >&2
  exit 1
fi

echo "[builds-limit-sync] OK: SPA PAGE_SIZE == auth-spec default == $UI_VAL."
exit 0
