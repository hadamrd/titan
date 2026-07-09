#!/usr/bin/env bash
# Adversarial unit test for dev/rig-smoke/prewarm-worker.sh (#84).
#
# Acceptance criteria under test (issue #84):
#   - FRESH worker (StartedAt < 15min ago) → ONE throwaway build is fired via
#     the API, the script waits for a terminal status, and logs
#     '[rig-smoke] pre-warmed worker (Xs)'.
#   - OLD worker (StartedAt >= 15min ago) → NO pre-warm (adversarial: assert
#     the API was never touched — the curl call log stays empty).
#   - RIG_SMOKE_SKIP_PREWARM=1 → skipped entirely, docker never invoked.
#   - Warm-up build never terminal → loud warning, but STILL exit 0 (a
#     best-effort helper must never turn a smoke run red by itself).
#
# docker and curl are stubbed via RIG_SMOKE_DOCKER_CMD / RIG_SMOKE_CURL_CMD
# (same stub pattern as test_run_golden.sh) — no rig, no network. The curl
# stub is stateful (build status: RUNNING first, SUCCESS second) and records
# every invocation so the no-prewarm assertions have an independent oracle.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SCRIPT="$REPO_ROOT/dev/rig-smoke/prewarm-worker.sh"

if [ ! -x "$SCRIPT" ]; then
  echo "FAIL: $SCRIPT not executable" >&2
  exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "SKIP-FAIL: jq required for this test (and for the script under test)" >&2
  exit 1
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# ── Stub docker: ps → worker name; inspect → StartedAt from $TMP/started.txt ─
cat > "$TMP/docker-stub.sh" <<EOF
#!/usr/bin/env bash
echo "docker \$*" >> "$TMP/docker-calls.log"
case "\$1" in
  ps)      echo "local-titan-worker-1" ;;
  inspect) cat "$TMP/started.txt" ;;
  *)       exit 1 ;;
esac
EOF
chmod +x "$TMP/docker-stub.sh"

# ── Stub curl: dispatch on URL; record every call; stateful build status ────
# Endpoints handled (mirrors the real rig API surface):
#   POST …/protocol/openid-connect/token    → access_token JSON
#   POST …/api/v1/jobs                      → TriggerBuild flow: body + \n + http_code
#   GET  …/api/v1/jobs?search=…             → jobs page (used on 409 reuse)
#   POST …/api/v1/jobs/<id>/builds          → {"buildId":4242,…}
#   GET  …/api/v1/builds/4242               → RUNNING on 1st poll, then $TMP/final-status.txt
cat > "$TMP/curl-stub.sh" <<EOF
#!/usr/bin/env bash
echo "curl \$*" >> "$TMP/curl-calls.log"
args="\$*"
case "\$args" in
  *"/protocol/openid-connect/token"*)
    echo '{"access_token":"stub-bearer","token_type":"Bearer"}'
    ;;
  *"/api/v1/jobs?search="*)
    echo '{"items":[{"id":77,"fullName":"rig-smoke-prewarm"}],"total":1,"offset":0,"limit":50}'
    ;;
  *"/api/v1/jobs/77/builds"*)
    echo '{"buildId":4242,"buildNumber":1,"status":"QUEUED"}'
    ;;
  *"/api/v1/builds/4242"*)
    n=\$(cat "$TMP/poll-count.txt" 2>/dev/null || echo 0)
    echo \$((n + 1)) > "$TMP/poll-count.txt"
    if [ "\$n" -lt 1 ]; then
      echo '{"id":4242,"status":"RUNNING"}'
    else
      echo "{\"id\":4242,\"status\":\"\$(cat "$TMP/final-status.txt")\"}"
    fi
    ;;
  *"/api/v1/jobs"*)
    # job create — body + newline + http_code (the -w '\n%{http_code}' shape)
    printf '%s\n%s\n' "\$(cat "$TMP/create-body.txt")" "\$(cat "$TMP/create-code.txt")"
    ;;
  *)
    echo "curl-stub: unhandled args: \$args" >&2
    exit 1
    ;;
esac
EOF
chmod +x "$TMP/curl-stub.sh"

reset_state() {
  rm -f "$TMP/docker-calls.log" "$TMP/curl-calls.log" "$TMP/poll-count.txt"
  echo "SUCCESS" > "$TMP/final-status.txt"
  echo '{"id":77,"fullName":"rig-smoke-prewarm"}' > "$TMP/create-body.txt"
  echo "201" > "$TMP/create-code.txt"
}

run_prewarm() { # extra env via caller
  RIG_SMOKE_DOCKER_CMD="$TMP/docker-stub.sh" \
    RIG_SMOKE_CURL_CMD="$TMP/curl-stub.sh" \
    RIG_SMOKE_PREWARM_POLL_S=0 \
    bash "$SCRIPT"
}

iso_utc_ago() { # <seconds-ago> → docker-style RFC3339 timestamp
  date -u -d "@$(( $(date +%s) - $1 ))" +%Y-%m-%dT%H:%M:%S.000000000Z
}

# ── Case 1: fresh worker (2min old) → pre-warm fires + waits terminal ──────
reset_state
iso_utc_ago 120 > "$TMP/started.txt"
OUT=$(run_prewarm)
if ! echo "$OUT" | grep -qE '\[rig-smoke\] pre-warmed worker \([0-9]+s\)'; then
  echo "FAIL: fresh worker did not log '[rig-smoke] pre-warmed worker (Xs)':" >&2
  echo "$OUT" >&2
  exit 1
fi
if ! grep -q '/api/v1/jobs/77/builds' "$TMP/curl-calls.log"; then
  echo "FAIL: fresh worker did not trigger a build via the API:" >&2
  cat "$TMP/curl-calls.log" >&2
  exit 1
fi
# Independent oracle: the status endpoint was polled past RUNNING to terminal.
POLLS=$(cat "$TMP/poll-count.txt")
if [ "$POLLS" -lt 2 ]; then
  echo "FAIL: expected >=2 status polls (RUNNING then SUCCESS), got $POLLS" >&2
  exit 1
fi
echo "ok: fresh worker → warm-up build fired, waited terminal, logged pre-warmed worker (Xs)"

# ── Case 2: ADVERSARIAL — old worker (2h) → NO pre-warm, API untouched ─────
reset_state
iso_utc_ago 7200 > "$TMP/started.txt"
OUT=$(run_prewarm)
if ! echo "$OUT" | grep -q 'no pre-warm needed'; then
  echo "FAIL: old worker should log 'no pre-warm needed': $OUT" >&2
  exit 1
fi
if [ -f "$TMP/curl-calls.log" ]; then
  echo "FAIL: old worker must not touch the API; curl was called:" >&2
  cat "$TMP/curl-calls.log" >&2
  exit 1
fi
echo "ok: ADVERSARIAL — old worker (2h) → no pre-warm, zero API calls"

# ── Case 3: skip env → nothing happens, docker never invoked ────────────────
reset_state
iso_utc_ago 10 > "$TMP/started.txt"
OUT=$(RIG_SMOKE_SKIP_PREWARM=1 run_prewarm)
if ! echo "$OUT" | grep -q 'RIG_SMOKE_SKIP_PREWARM=1'; then
  echo "FAIL: skip case should log the skip reason: $OUT" >&2
  exit 1
fi
if [ -f "$TMP/docker-calls.log" ] || [ -f "$TMP/curl-calls.log" ]; then
  echo "FAIL: RIG_SMOKE_SKIP_PREWARM=1 must not invoke docker/curl" >&2
  exit 1
fi
echo "ok: RIG_SMOKE_SKIP_PREWARM=1 → fully skipped (no docker, no curl)"

# ── Case 4: 409 on job create → existing job id resolved, build still fired ─
reset_state
iso_utc_ago 60 > "$TMP/started.txt"
echo "409" > "$TMP/create-code.txt"
echo '{"title":"Conflict"}' > "$TMP/create-body.txt"
OUT=$(run_prewarm)
if ! echo "$OUT" | grep -qE 'pre-warmed worker \([0-9]+s\)'; then
  echo "FAIL: 409 (job already exists) should reuse the job and still pre-warm: $OUT" >&2
  exit 1
fi
if ! grep -q '/api/v1/jobs?search=' "$TMP/curl-calls.log"; then
  echo "FAIL: 409 path should resolve the existing job via ?search=:" >&2
  cat "$TMP/curl-calls.log" >&2
  exit 1
fi
echo "ok: idempotent — 409 on create resolves the existing warm-up job"

# ── Case 5: build never terminal → warning but exit 0 (never fails smoke) ──
reset_state
iso_utc_ago 60 > "$TMP/started.txt"
echo "RUNNING" > "$TMP/final-status.txt"
set +e
OUT=$(RIG_SMOKE_PREWARM_TIMEOUT_S=1 run_prewarm)
RC=$?
set -e
if [ "$RC" -ne 0 ]; then
  echo "FAIL: non-terminal warm-up build must NOT fail the smoke (exit $RC)" >&2
  exit 1
fi
if ! echo "$OUT" | grep -q 'WARNING'; then
  echo "FAIL: non-terminal warm-up should print a WARNING: $OUT" >&2
  exit 1
fi
echo "ok: wedged warm-up build → WARNING + exit 0 (best-effort, never blocks)"

echo ""
echo "PASS: all prewarm-worker.sh cases (including adversarial no-prewarm guard)"
