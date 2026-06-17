#!/usr/bin/env bash
# Unit test for dev/rig-smoke/check-golden-count.sh — proves the V1
# shippable-bar guard (#1048) fails closed when @golden coverage shrinks
# and passes when the bar is met.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SCRIPT="$REPO_ROOT/dev/rig-smoke/check-golden-count.sh"

if [ ! -x "$SCRIPT" ]; then
  echo "FAIL: $SCRIPT not executable" >&2
  exit 1
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# ── Case 1: empty specs dir → MUST fail (count 0 < default min 12) ────────
mkdir -p "$TMP/empty/specs"
if SPECS_DIR="$TMP/empty/specs" bash "$SCRIPT" >/dev/null 2>&1; then
  echo "FAIL: empty specs dir should have exited non-zero" >&2
  exit 1
fi
echo "ok: empty specs dir fails the guard"

# ── Case 2: 5 @golden tests, min=12 → MUST fail (below floor) ─────────────
mkdir -p "$TMP/low/specs"
for i in 1 2 3 4 5; do
  cat > "$TMP/low/specs/spec$i.spec.ts" <<EOF
test('@golden case $i works', async () => {});
EOF
done
if SPECS_DIR="$TMP/low/specs" RIG_SMOKE_GOLDEN_MIN=12 bash "$SCRIPT" >/dev/null 2>&1; then
  echo "FAIL: 5 golden tests with min=12 should have exited non-zero" >&2
  exit 1
fi
echo "ok: shrunk @golden surface (5 < 12) fails the guard"

# ── Case 3: 15 @golden tests, min=12 → MUST pass ──────────────────────────
mkdir -p "$TMP/ok/specs"
for i in $(seq 1 15); do
  cat > "$TMP/ok/specs/spec$i.spec.ts" <<EOF
test('@golden case $i works', async () => {});
EOF
done
if ! SPECS_DIR="$TMP/ok/specs" RIG_SMOKE_GOLDEN_MIN=12 bash "$SCRIPT" >/dev/null 2>&1; then
  echo "FAIL: 15 golden tests with min=12 should have exited zero" >&2
  exit 1
fi
echo "ok: healthy @golden surface (15 >= 12) passes the guard"

# ── Case 4: nested describe block counts too ──────────────────────────────
mkdir -p "$TMP/nested/specs"
cat > "$TMP/nested/specs/d.spec.ts" <<'EOF'
test.describe('@golden suite', () => {
  test('alpha', async () => {});
});
EOF
if ! SPECS_DIR="$TMP/nested/specs" RIG_SMOKE_GOLDEN_MIN=1 bash "$SCRIPT" >/dev/null 2>&1; then
  echo "FAIL: test.describe(@golden) should be counted" >&2
  exit 1
fi
echo "ok: test.describe(@golden) counts toward the bar"

# ── Case 5: non-existent specs dir → exit 2 (loud config error) ───────────
set +e
SPECS_DIR="$TMP/does-not-exist" bash "$SCRIPT" >/dev/null 2>&1
RC=$?
set -e
if [ "$RC" -ne 2 ]; then
  echo "FAIL: missing specs dir should exit 2, got $RC" >&2
  exit 1
fi
echo "ok: missing specs dir exits 2"

echo ""
echo "PASS: all check-golden-count.sh cases"
