#!/usr/bin/env bash
# Adversarial unit test for dev/rig-smoke/golden-roundtrip.sh (#1239).
#
# Acceptance criterion under test:
#   The golden-path round-trip gate must FAIL CLOSED when a provisioning env
#   var is missing — otherwise the specs silently degrade to test.skip() and
#   the gate "passes" while proving nothing about the headline experience.
#
# We exercise the `--check-only` path so these cases need neither the rig nor
# playwright nor gh — only the env preflight, which is the part that has
# historically lied.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SCRIPT="$REPO_ROOT/dev/rig-smoke/golden-roundtrip.sh"

if [ ! -x "$SCRIPT" ]; then
  echo "FAIL: $SCRIPT not executable" >&2
  exit 1
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# Point the env-file loader at an empty file so a developer's real
# ~/e2e/.env.golden-roundtrip can never leak into these assertions.
EMPTY_ENV="$TMP/empty.env"
: > "$EMPTY_ENV"

ALL_VARS=(
  "TITAN_E2E_GH_APP_WEBHOOK_SECRET=shh"
  "TITAN_E2E_GH_INSTALLATION_ID=12345"
  "TITAN_E2E_GH_REPO_ID=67890"
)

run_check() {
  # Run the script in --check-only with a controlled environment.
  # Args are VAR=VALUE pairs to export; everything else is scrubbed.
  env -i PATH="$PATH" HOME="$HOME" GOLDEN_ROUNDTRIP_ENV_FILE="$EMPTY_ENV" \
    "$@" bash "$SCRIPT" --check-only
}

# ── Case 1: opted out (LAYER2 unset) → graceful skip, exit 0 ──────────────
set +e
run_check >/dev/null 2>&1
RC=$?
set -e
if [ "$RC" -ne 0 ]; then
  echo "FAIL: opted-out run should exit 0 (graceful skip), got $RC" >&2
  exit 1
fi
echo "ok: opted-out (LAYER2_RIG_AVAILABLE unset) → graceful skip exit 0"

# ── Case 2: ADVERSARIAL — opted in, webhook secret missing → exit 3 ───────
set +e
run_check LAYER2_RIG_AVAILABLE=1 \
  TITAN_E2E_GH_INSTALLATION_ID=12345 \
  TITAN_E2E_GH_REPO_ID=67890 \
  > "$TMP/c2.out" 2>&1
RC=$?
set -e
if [ "$RC" -ne 3 ]; then
  echo "FAIL: missing webhook secret should fail closed (exit 3), got $RC" >&2
  cat "$TMP/c2.out" >&2
  exit 1
fi
if ! grep -q "TITAN_E2E_GH_APP_WEBHOOK_SECRET" "$TMP/c2.out"; then
  echo "FAIL: error did not name the missing var: $(cat "$TMP/c2.out")" >&2
  exit 1
fi
echo "ok: ADVERSARIAL — missing webhook secret fails closed (exit 3) + names it"

# ── Case 3: ADVERSARIAL — opted in, install + repo ids missing → exit 3 ───
set +e
run_check LAYER2_RIG_AVAILABLE=1 \
  TITAN_E2E_GH_APP_WEBHOOK_SECRET=shh \
  > "$TMP/c3.out" 2>&1
RC=$?
set -e
if [ "$RC" -ne 3 ]; then
  echo "FAIL: missing install/repo ids should exit 3, got $RC" >&2
  exit 1
fi
if ! grep -q "TITAN_E2E_GH_INSTALLATION_ID" "$TMP/c3.out" \
  || ! grep -q "TITAN_E2E_GH_REPO_ID" "$TMP/c3.out"; then
  echo "FAIL: error did not name BOTH missing ids: $(cat "$TMP/c3.out")" >&2
  exit 1
fi
echo "ok: ADVERSARIAL — both missing ids reported together (exit 3)"

# ── Case 4: opted in + all vars present → preflight passes (exit 0) ────────
set +e
run_check LAYER2_RIG_AVAILABLE=1 "${ALL_VARS[@]}" > "$TMP/c4.out" 2>&1
RC=$?
set -e
if [ "$RC" -ne 0 ]; then
  echo "FAIL: all vars present should pass preflight (exit 0), got $RC" >&2
  cat "$TMP/c4.out" >&2
  exit 1
fi
if ! grep -q "preflight OK" "$TMP/c4.out"; then
  echo "FAIL: healthy preflight did not announce success: $(cat "$TMP/c4.out")" >&2
  exit 1
fi
echo "ok: all provisioning vars present → preflight passes (exit 0)"

# ── Case 5: vars supplied via the env FILE (not the environment) → exit 0 ─
FILLED_ENV="$TMP/filled.env"
cat > "$FILLED_ENV" <<'EOF'
LAYER2_RIG_AVAILABLE=1
TITAN_E2E_GH_APP_WEBHOOK_SECRET=from-file
TITAN_E2E_GH_INSTALLATION_ID=111
TITAN_E2E_GH_REPO_ID=222
EOF
set +e
env -i PATH="$PATH" HOME="$HOME" GOLDEN_ROUNDTRIP_ENV_FILE="$FILLED_ENV" \
  bash "$SCRIPT" --check-only > "$TMP/c5.out" 2>&1
RC=$?
set -e
if [ "$RC" -ne 0 ]; then
  echo "FAIL: env-file-sourced vars should pass preflight (exit 0), got $RC" >&2
  cat "$TMP/c5.out" >&2
  exit 1
fi
if ! grep -q "sourcing env from" "$TMP/c5.out"; then
  echo "FAIL: env file was not sourced: $(cat "$TMP/c5.out")" >&2
  exit 1
fi
echo "ok: provisioning vars sourced from the env file pass preflight"

echo ""
echo "PASS: all golden-roundtrip.sh preflight cases (including fail-closed guards)"
