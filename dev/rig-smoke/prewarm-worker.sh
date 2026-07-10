#!/usr/bin/env bash
# prewarm-worker.sh — warm the titan-worker before the smoke suite runs
# (#84 cold-container case, #153 idle-cold case).
#
# Why this exists:
#   #84:  After any titan-worker container recreation the npm cache inside the
#         container is empty; the first real build pays a cold `npm ci` and
#         blows the 30s trigger→FAILURE budget in golden-path-failure-triage
#         (observed 2026-07-09: red on the post-rebuild run, green on the warm
#         rerun). That guarantees run 1 of any post-rebuild 3-green sequence
#         is a false red.
#   #153: A LONG-RUNNING worker goes cold too. WSL2 reclaims the page cache
#         after idle periods, so the first `npm install` after a >2h gap walks
#         node_modules from cold storage: measured 18-24s for a NO-OP install
#         vs 2.5s warm (task_archive rows, builds 2642/2734 vs 2643/2729).
#         Both #152-telemetry triage misses (22:01 and 00:30, 2026-07-09/10)
#         followed a >2h idle gap; the back-to-back 19:14-19:44 runs were all
#         green. Container age is therefore NOT a proof of cache warmth — only
#         recent build activity is.
#   The 30s budget is a PRODUCT latency property — it stays; the harness
#   absorbs the cold start instead.
#
# What it does:
#   1. Decide whether the worker is presumed cold:
#      a. container younger than RIG_SMOKE_PREWARM_MAX_AGE_S (default 900s,
#         via docker inspect .State.StartedAt) → cold (#84), or
#      b. container older than that BUT the rig shows no build activity within
#         RIG_SMOKE_PREWARM_MAX_IDLE_S (default 1800s, via the newest build's
#         timestamps from GET /api/v1/builds?limit=1) → idle-cold (#153).
#         Spec teardowns cascade-delete their builds, so this UNDERESTIMATES
#         activity — which only ever errs toward an unnecessary ~10s warm-up,
#         never toward a cold canary. An indeterminate probe (API down, empty
#         page) also errs toward warming.
#   2. If cold, fire ONE throwaway warm-up build through the public API and
#      wait for it to reach a terminal status before returning. The warm-up
#      job (`rig-smoke-prewarm`) runs the SAME `npm ci` install step as the
#      node fixtures (e2e/pipelines/node-app-with-failing-test), so the
#      worker's npm/page cache is genuinely hot afterwards — not just the JVM.
#   3. Logs `[rig-smoke] pre-warmed worker (Xs)` on success.
#
# Contract:
#   - ALWAYS exits 0. Pre-warm is a best-effort harness helper; a wedged
#     worker will surface in the real specs with their real budgets.
#   - Job creation is idempotent (409 → resolve the existing job id).
#
# Env overrides (for tests — see tests/test_prewarm_worker.sh):
#   RIG_SMOKE_SKIP_PREWARM=1      skip entirely (debugging escape hatch)
#   RIG_SMOKE_PREWARM_MAX_AGE_S   worker age threshold (default 900)
#   RIG_SMOKE_PREWARM_MAX_IDLE_S  build-activity threshold (default 1800; #153)
#   RIG_SMOKE_PREWARM_TIMEOUT_S   terminal-wait budget (default 240)
#   RIG_SMOKE_PREWARM_POLL_S      status poll interval (default 2)
#   RIG_SMOKE_PREWARM_JOB         warm-up job full_name (default rig-smoke-prewarm)
#   RIG_SMOKE_DOCKER_CMD          command run instead of `docker`
#   RIG_SMOKE_CURL_CMD            command run instead of `curl`
#   TITAN_API_URL / KC_URL / KC_REALM / KC_CLIENT / KC_USER / KC_PASS
#                                 same defaults as rig/local/seed-data.sh
set -euo pipefail

DOCKER="${RIG_SMOKE_DOCKER_CMD:-docker}"
CURL="${RIG_SMOKE_CURL_CMD:-curl}"
MAX_AGE_S="${RIG_SMOKE_PREWARM_MAX_AGE_S:-900}"
MAX_IDLE_S="${RIG_SMOKE_PREWARM_MAX_IDLE_S:-1800}"
TIMEOUT_S="${RIG_SMOKE_PREWARM_TIMEOUT_S:-240}"
POLL_S="${RIG_SMOKE_PREWARM_POLL_S:-2}"
PREWARM_JOB="${RIG_SMOKE_PREWARM_JOB:-rig-smoke-prewarm}"

TITAN_API_URL="${TITAN_API_URL:-http://localhost:18080}"
KC_URL="${KC_URL:-http://localhost:8081}"
KC_REALM="${KC_REALM:-titan-dev}"
KC_CLIENT="${KC_CLIENT:-titan-e2e}"
KC_USER="${KC_USER:-dev}"
KC_PASS="${KC_PASS:-dev}"

log() { echo "[rig-smoke] $*"; }

bail() { # non-fatal exit — pre-warm must never fail the smoke
  log "$*"
  exit 0
}

if [ "${RIG_SMOKE_SKIP_PREWARM:-0}" = "1" ]; then
  bail "pre-warm: skipped (RIG_SMOKE_SKIP_PREWARM=1)"
fi

# shellcheck disable=SC2086  # deliberate word-splitting of command prefixes
if ! command -v ${DOCKER%% *} >/dev/null 2>&1; then
  bail "pre-warm: '${DOCKER%% *}' not on PATH — skipping"
fi

# ── 1. Worker presence + age ──────────────────────────────────────────────────────
# shellcheck disable=SC2086
WORKER=$($DOCKER ps --filter name=titan-worker --format '{{.Names}}' 2>/dev/null | head -n 1 || true)
if [ -z "$WORKER" ]; then
  bail "pre-warm: no titan-worker container running — skipping"
fi

# shellcheck disable=SC2086
STARTED_AT=$($DOCKER inspect --format '{{.State.StartedAt}}' "$WORKER" 2>/dev/null || true)
STARTED_S=$(date -d "$STARTED_AT" +%s 2>/dev/null || true)
if [ -z "$STARTED_S" ]; then
  bail "pre-warm: cannot parse worker StartedAt '${STARTED_AT}' — skipping"
fi

AGE_S=$(( $(date +%s) - STARTED_S ))

if ! command -v jq >/dev/null 2>&1; then
  bail "pre-warm: WARNING — jq not on PATH, cannot drive the API; first real build may pay the cold cache"
fi

# ── 2. Bearer via Keycloak ROPC (same dev-only path as seed-data.sh) ────────
# Fetched before the cold-or-warm decision: the #153 idle probe needs the API
# just like the warm-up build does.
# shellcheck disable=SC2086
TOKEN_RESPONSE=$($CURL -fsS \
  -X POST "${KC_URL}/realms/${KC_REALM}/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "grant_type=password" \
  -d "client_id=${KC_CLIENT}" \
  -d "username=${KC_USER}" \
  -d "password=${KC_PASS}" \
  -d "scope=openid" 2>/dev/null) || bail "pre-warm: WARNING — Keycloak token fetch failed; skipping"
BEARER=$(printf '%s' "$TOKEN_RESPONSE" | jq -r '.access_token // empty')
[ -n "$BEARER" ] || bail "pre-warm: WARNING — no access_token in Keycloak response; skipping"

# ── 3. Cold-or-warm decision (#84 container age, #153 build-activity idle) ──
if [ "$AGE_S" -lt "$MAX_AGE_S" ]; then
  log "pre-warm: worker ${WORKER} is ${AGE_S}s old (< ${MAX_AGE_S}s) — npm cache likely cold, firing warm-up build (#84; skip with RIG_SMOKE_SKIP_PREWARM=1)"
else
  # An old container is NOT proof of a warm cache (#153): WSL2 reclaims the
  # page cache after idle, so ask the API when the rig last built anything.
  # Spec teardowns cascade-delete their builds, so the newest persisted build
  # UNDERESTIMATES activity — errs toward an unnecessary warm-up, never
  # toward a cold canary. Indeterminate (curl/jq failure, empty page) also
  # errs cold-side.
  # shellcheck disable=SC2086
  LATEST_TS=$($CURL -fsS "${TITAN_API_URL}/api/v1/builds?limit=1" \
    -H "Authorization: Bearer ${BEARER}" 2>/dev/null \
    | jq -r '.items[0] | (.finishedAt // .startedAt // .queuedAt) // empty' 2>/dev/null || true)
  LATEST_S=""
  if [ -n "$LATEST_TS" ]; then
    LATEST_S=$(date -d "$LATEST_TS" +%s 2>/dev/null || true)
  fi
  if [ -n "$LATEST_S" ]; then
    IDLE_S=$(( $(date +%s) - LATEST_S ))
    if [ "$IDLE_S" -lt "$MAX_IDLE_S" ]; then
      bail "pre-warm: worker ${WORKER} is ${AGE_S}s old and the rig built ${IDLE_S}s ago (< ${MAX_IDLE_S}s) — cache warm, no pre-warm needed"
    fi
    log "pre-warm: rig built nothing for ${IDLE_S}s (>= ${MAX_IDLE_S}s) — page cache likely reclaimed, firing warm-up build (#153; skip with RIG_SMOKE_SKIP_PREWARM=1)"
  else
    log "pre-warm: worker ${WORKER} is ${AGE_S}s old and last build activity is unknowable — erring cold, firing warm-up build (#153; skip with RIG_SMOKE_SKIP_PREWARM=1)"
  fi
fi

T0=$(date +%s)

# ── 4. Ensure the warm-up job exists (idempotent; 409 → reuse) ──────────────
# The pipeline mirrors the install stage of the node fixture the triage spec
# uses (e2e/pipelines/node-app-with-failing-test/titan-pipeline.yml), so the
# warm-up populates the exact npm cache the first real build needs.
PIPELINE='agent: linux
stages:
  - stage: warm
    steps:
      - sh: cd /titan/fixtures/node-app-with-failing-test && (npm ci --no-audit --no-fund || npm install --no-audit --no-fund --no-package-lock)
'
JOB_BODY=$(jq -nc \
  --arg fullName "$PREWARM_JOB" \
  --arg pipelineScript "$PIPELINE" \
  '{fullName:$fullName, displayName:"rig-smoke worker pre-warm (#84)", pipelineScript:$pipelineScript, enabled:true}')

# Body + status on one stream (stub-friendly — no -o tempfile juggling).
# shellcheck disable=SC2086
CREATE_RESP=$($CURL -sS -w '\n%{http_code}' \
  -X POST "${TITAN_API_URL}/api/v1/jobs" \
  -H "Authorization: Bearer ${BEARER}" \
  -H 'Content-Type: application/json' \
  --data-binary "$JOB_BODY" 2>/dev/null) || bail "pre-warm: WARNING — POST /api/v1/jobs failed; skipping"
CREATE_CODE=$(printf '%s' "$CREATE_RESP" | tail -n 1)
CREATE_BODY=$(printf '%s' "$CREATE_RESP" | sed '$d')

case "$CREATE_CODE" in
  201)
    JOB_ID=$(printf '%s' "$CREATE_BODY" | jq -r '.id // empty')
    ;;
  409)
    # Already seeded by a previous pre-warm — resolve the existing id.
    # shellcheck disable=SC2086
    JOB_ID=$($CURL -fsS "${TITAN_API_URL}/api/v1/jobs?search=${PREWARM_JOB}" \
      -H "Authorization: Bearer ${BEARER}" 2>/dev/null \
      | jq -r --arg n "$PREWARM_JOB" '.items[] | select(.fullName == $n) | .id' | head -n 1) || JOB_ID=""
    ;;
  *)
    bail "pre-warm: WARNING — POST /api/v1/jobs returned HTTP ${CREATE_CODE}; skipping"
    ;;
esac
[ -n "${JOB_ID:-}" ] || bail "pre-warm: WARNING — could not resolve warm-up job id; skipping"

# ── 5. Fire the throwaway build ──────────────────────────────────────────────
# shellcheck disable=SC2086
TRIGGER_RESP=$($CURL -fsS \
  -X POST "${TITAN_API_URL}/api/v1/jobs/${JOB_ID}/builds" \
  -H "Authorization: Bearer ${BEARER}" \
  -H 'Content-Type: application/json' \
  -d '{"triggeredBy":"rig-smoke-prewarm"}' 2>/dev/null) || bail "pre-warm: WARNING — build trigger failed; skipping"
BUILD_ID=$(printf '%s' "$TRIGGER_RESP" | jq -r '.buildId // empty')
[ -n "$BUILD_ID" ] || bail "pre-warm: WARNING — no buildId in trigger response; skipping"

log "pre-warm: warm-up build ${BUILD_ID} (job ${PREWARM_JOB}) fired — waiting for terminal status (max ${TIMEOUT_S}s)"

# ── 6. Wait terminal ─────────────────────────────────────────────────────────
DEADLINE=$(( $(date +%s) + TIMEOUT_S ))
STATUS=""
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  # shellcheck disable=SC2086
  STATUS=$($CURL -fsS "${TITAN_API_URL}/api/v1/builds/${BUILD_ID}" \
    -H "Authorization: Bearer ${BEARER}" 2>/dev/null | jq -r '.status // empty' || true)
  case "$STATUS" in
    SUCCESS|FAILED|FAILURE|ABORTED|CANCELLED|UNSTABLE|ERROR)
      ELAPSED=$(( $(date +%s) - T0 ))
      log "pre-warmed worker (${ELAPSED}s) — warm-up build ${BUILD_ID} terminal: ${STATUS}"
      exit 0
      ;;
  esac
  sleep "$POLL_S"
done

log "pre-warm: WARNING — build ${BUILD_ID} not terminal after ${TIMEOUT_S}s (last=${STATUS:-unknown}); continuing — real specs will surface a wedged worker"
exit 0
