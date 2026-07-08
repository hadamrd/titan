#!/usr/bin/env bash
# Golden-path PR round-trip demo gate (#1239).
#
# Runs the three specs that encode the headline experience we sell —
# signed pull_request webhook → build dispatched ≤15s → SSE log delta in
# the UI → `success` commit status on the PR HEAD SHA → target_url resolving
# to /builds/:id:
#
#   - specs/v3/55-golden-path-pr-roundtrip.spec.ts  (the seam guard; `happy`)
#   - specs/v3/40-github-app-golden-path.spec.ts     (SUCCESS + ci/titan legs)
#   - specs/v3/50-github-pr-roundtrip.spec.ts        (real `gh pr create`)
#
# Why this is its own script (mirrors rig-smoke-parse.sh, #1048):
#   The specs are CORRECT but never run green because their provisioning env
#   vars are unset and nobody wires the chain together. The fiddly part is the
#   env preflight: the secret + installation/repo ids come from the App
#   provisioning step, and a missing one silently degrades the specs to
#   `test.skip()` — so a demo gate "passes" while proving nothing. Lifting the
#   preflight here makes it loud (fail-closed on a missing var) AND unit
#   testable without the rig (`--check-only`), so the gate can't lie.
#
# Usage:
#   golden-roundtrip.sh            # preflight + run the specs (needs rig + gh)
#   golden-roundtrip.sh --check-only   # validate env only; no rig, no playwright
#
# Env contract (see e2e/.env.golden-roundtrip.example):
#   LAYER2_RIG_AVAILABLE            opt-in switch. Unset → graceful skip (exit 0).
#   TITAN_E2E_GH_APP_WEBHOOK_SECRET required (from provisioning) — sign webhooks.
#   TITAN_E2E_GH_INSTALLATION_ID    required (from provisioning) — linked install.
#   TITAN_E2E_GH_REPO_ID            required (from provisioning) — linked repo.
#   GOLDEN_ROUNDTRIP_ENV_FILE       optional path to an env file to source first
#                                   (default: e2e/.env.golden-roundtrip).
#   TITAN_RIG_URL                   rig under test (defaults handled by fixtures).
#
# Exit codes:
#   0  opted out (LAYER2_RIG_AVAILABLE unset) OR preflight/run succeeded.
#   1  gh CLI not authenticated, or the playwright run failed.
#   3  a required provisioning var is missing while opted in (fail-closed).
set -euo pipefail

CHECK_ONLY=0
if [ "${1:-}" = "--check-only" ]; then
  CHECK_ONLY=1
fi

# Repo root is two levels up from dev/rig-smoke/.
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE="${GOLDEN_ROUNDTRIP_ENV_FILE:-$REPO_ROOT/e2e/.env.golden-roundtrip}"

# Source an optional gitignored env file so operators can drop the
# provisioning secrets in one place instead of exporting four vars by hand.
if [ -f "$ENV_FILE" ]; then
  echo "[golden-roundtrip] sourcing env from $ENV_FILE"
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
fi

# ── opt-in gate (mirrors task rig:smoke:real) ─────────────────────────────
if [ -z "${LAYER2_RIG_AVAILABLE:-}" ]; then
  echo "[golden-roundtrip] LAYER2_RIG_AVAILABLE not set — refusing to run."
  echo "[golden-roundtrip] This gate drives a signed pull_request webhook against"
  echo "[golden-roundtrip] the rig and polls real GitHub commit statuses on"
  echo "[golden-roundtrip] the fixture repo. Set LAYER2_RIG_AVAILABLE=1 to opt in."
  exit 0
fi

# ── fail-closed on missing provisioning vars ──────────────────────────────
MISSING=()
for var in \
  TITAN_E2E_GH_APP_WEBHOOK_SECRET \
  TITAN_E2E_GH_INSTALLATION_ID \
  TITAN_E2E_GH_REPO_ID; do
  if [ -z "${!var:-}" ]; then
    MISSING+=("$var")
  fi
done

if [ "${#MISSING[@]}" -gt 0 ]; then
  echo "[golden-roundtrip] FAIL: missing provisioning env var(s): ${MISSING[*]}" >&2
  echo "[golden-roundtrip] These come from registering + installing the GitHub App" >&2
  echo "[golden-roundtrip] on the rig. See e2e/.env.golden-roundtrip.example for the" >&2
  echo "[golden-roundtrip] expected names and where each value comes from." >&2
  exit 3
fi

echo "[golden-roundtrip] preflight OK — all provisioning vars present."

if [ "$CHECK_ONLY" -eq 1 ]; then
  echo "[golden-roundtrip] --check-only: env validated, not running specs."
  exit 0
fi

# ── live run: needs gh + a reachable rig ──────────────────────────────────
if ! gh auth status >/dev/null 2>&1; then
  echo "[golden-roundtrip] gh CLI not authenticated — spec 50 cuts a real PR" >&2
  echo "[golden-roundtrip] and specs poll real GitHub statuses. Run 'gh auth login'." >&2
  exit 1
fi

cd "$REPO_ROOT/e2e"

# Spec 40's SUCCESS leg is gated behind this flag (AC #3); without it the spec
# only proves the build dispatched, not that it reached terminal SUCCESS.
export TITAN_E2E_ASSERT_BUILD_SUCCESS=1

# Real GitHub App webhook + (k3s) scheduling is materially slower than the
# local rig; give each test the same budget rig:smoke:real uses.
export TITAN_TEST_TIMEOUT_MS="${TITAN_TEST_TIMEOUT_MS:-1200000}"

# Run the three green round-trip legs. --grep-invert "adversarial" excludes
# spec 55's red-check leg and spec 40's @adversarial HMAC cases — both are
# out of scope for this gate (separate ticket) and would otherwise make a
# demo-readiness check fail for reasons unrelated to the headline path.
# NB: tagging is inconsistent across specs — spec 55's red leg is titled
# "adversarial:" (no @tag) while spec 40 uses "@adversarial", so this is a
# deliberate *substring* filter (not a tag filter) to catch both. Trade-off:
# a future GREEN leg whose title contains "adversarial" would be silently
# excluded — rename that leg or revisit this filter if one lands.
echo "[golden-roundtrip] running golden-path round-trip specs against ${TITAN_RIG_URL:-<default rig>}"
pnpm exec playwright test \
  specs/v3/55-golden-path-pr-roundtrip.spec.ts \
  specs/v3/40-github-app-golden-path.spec.ts \
  specs/v3/50-github-pr-roundtrip.spec.ts \
  --grep-invert "adversarial" \
  --reporter=line
