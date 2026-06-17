#!/usr/bin/env bash
# Post-deploy AUTH smoke: proves the deployed rig actually WORKS end-to-end —
# real Keycloak login → bearer token → authenticated API call → HTTP 200.
#
# WHY this exists: every rig outage in this repo's history was an integration /
# config break (missing realm, wrong OIDC issuer, namespace split-brain, stale
# DB password) that unit tests CANNOT catch — they mock auth + the DB. The loop
# shipped "green" for 8 days onto a rig that couldn't even log in. This smoke is
# the gate that makes "deployed" mean "works": if login or an authed API call
# fails, the deploy FAILS loudly instead of going quietly green.
#
# Run automatically by rig/k3s/deploy.sh. Exit non-zero ⇒ deploy aborts.
set -euo pipefail

RIG_DIR="$(cd "$(dirname "$0")" && pwd)"            # rig/k3s
VALUES="$RIG_DIR/rig-values.yaml"
deploy_val(){ awk -v k="$1" '/^deploy:/{d=1;next}/^[^[:space:]]/{d=0}d&&$1==k":"{sub(/^[^:]*:[[:space:]]*/,"");sub(/[[:space:]]*#.*/,"");gsub(/^["'\'']|["'\'']$/,"");print;exit}' "$VALUES"; }
ingress_host(){ awk '/^ingress:/{i=1;next}/^[^[:space:]]/{i=0}i&&$1=="host:"{sub(/^[^:]*:[[:space:]]*/,"");sub(/[[:space:]]*#.*/,"");gsub(/^["'\'']|["'\'']$/,"");print;exit}' "$VALUES"; }

HOST="${RIG_HOST:-$(ingress_host)}"
REALM="${SMOKE_REALM:-titan-dev}"
USER="${SMOKE_USER:-dev}"
PASS="${SMOKE_PASS:-dev}"
CLIENT="${SMOKE_CLIENT:-titan-e2e}"     # public client w/ directAccessGrants
: "${HOST:?ingress.host missing from rig-values}"
BASE="https://$HOST"
log(){ printf '\033[1;34m[smoke-auth]\033[0m %s\n' "$*"; }
fail(){ echo "[smoke-auth] ❌ $*" >&2; exit 1; }

# 1) login → token (retry; server/keycloak may still be settling post-rollout)
TOK=""
for i in $(seq 1 20); do
  resp="$(curl --max-time 10 -sS -X POST "$BASE/realms/$REALM/protocol/openid-connect/token" \
            -d "client_id=$CLIENT" -d "username=$USER" -d "password=$PASS" -d grant_type=password 2>/dev/null || true)"
  TOK="$(printf '%s' "$resp" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("access_token",""))' 2>/dev/null || true)"
  [[ -n "$TOK" ]] && break
  sleep 6
  [[ $i == 20 ]] && fail "login failed for $USER@$REALM (Keycloak realm missing/misconfigured?). last: $(printf '%s' "$resp" | head -c 160)"
done
log "login OK ($USER@$REALM)"

# 2) authenticated API call must return 200 (proves issuer/audience wiring)
for i in $(seq 1 15); do
  code="$(curl --max-time 10 -sS -o /tmp/smoke-api.json -w '%{http_code}' \
           -H "Authorization: Bearer $TOK" "$BASE/api/v1/builds?offset=0&limit=1" 2>/dev/null || echo 000)"
  [[ "$code" == 200 ]] && { log "authed API /api/v1/builds → 200 ✅  rig is WORKING"; exit 0; }
  sleep 6
  [[ $i == 15 ]] && fail "authed API returned $code (token rejected — OIDC issuer/audience mismatch? server env QUARKUS_OIDC_TOKEN_ISSUER vs token iss=$BASE/realms/$REALM)"
done
