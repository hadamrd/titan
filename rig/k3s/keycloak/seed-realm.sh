#!/usr/bin/env bash
# Idempotently seed the Keycloak `titan-dev` realm via the Admin REST API.
#
# WHY this and not Keycloak's `--import-realm`:
#   --import-realm forces a Quarkus re-augmentation on every Keycloak boot and,
#   on this rig, crashlooped the pod (slow boot tripped the probes). The REST
#   approach runs once, post-deploy, against the already-running Keycloak, and
#   is a no-op if the realm already exists. No pod restart, no crashloop.
#
# Run automatically by rig/k3s/deploy.sh after the Helm upgrade. Safe to re-run.
#
# Inputs (all derived, nothing to pass):
#   - kube context + namespace + ingress host : from rig/k3s/rig-values.yaml
#   - Keycloak admin password                 : k8s secret titan-secrets/KEYCLOAK_ADMIN_PASSWORD
#   - realm definition                         : rig/k3s/keycloak/realm-titan-dev.json
set -euo pipefail

RIG_DIR="$(cd "$(dirname "$0")/.." && pwd)"          # rig/k3s
REALM_JSON="$(dirname "$0")/realm-titan-dev.json"
VALUES="$RIG_DIR/rig-values.yaml"
[[ -f "$REALM_JSON" ]] || { echo "[seed-realm] no realm json at $REALM_JSON — skipping"; exit 0; }

deploy_val() { awk -v k="$1" '/^deploy:/{d=1;next}/^[^[:space:]]/{d=0}d&&$1==k":"{sub(/^[^:]*:[[:space:]]*/,"");sub(/[[:space:]]*#.*/,"");gsub(/^["'\'']|["'\'']$/,"");print;exit}' "$VALUES"; }
ingress_host() { awk '/^ingress:/{i=1;next}/^[^[:space:]]/{i=0}i&&$1=="host:"{sub(/^[^:]*:[[:space:]]*/,"");sub(/[[:space:]]*#.*/,"");gsub(/^["'\'']|["'\'']$/,"");print;exit}' "$VALUES"; }

CTX="${TITAN_KUBE_CONTEXT:-$(deploy_val kubeContext)}"
NS="${TITAN_NAMESPACE:-$(deploy_val namespace)}"
HOST="${RIG_HOST:-$(ingress_host)}"
REALM=titan-dev
: "${CTX:?deploy.kubeContext missing}" "${NS:?deploy.namespace missing}" "${HOST:?ingress.host missing}"
BASE="https://$HOST"
log(){ printf '\033[1;34m[seed-realm]\033[0m %s\n' "$*"; }

PW="$(kubectl --context "$CTX" -n "$NS" get secret titan-secrets -o jsonpath='{.data.KEYCLOAK_ADMIN_PASSWORD}' | base64 -d)"
[[ -n "$PW" ]] || { echo "[seed-realm] KEYCLOAK_ADMIN_PASSWORD empty — abort"; exit 1; }

# Wait for Keycloak (master realm) to answer — it may be mid-rollout.
log "waiting for Keycloak at $BASE ..."
for i in $(seq 1 30); do
  curl --max-time 8 -fsS -o /dev/null "$BASE/realms/master/.well-known/openid-configuration" 2>/dev/null && break
  sleep 4
  [[ $i == 30 ]] && { echo "[seed-realm] Keycloak never became ready"; exit 1; }
done

TOK="$(curl --max-time 12 -fsS -X POST "$BASE/realms/master/protocol/openid-connect/token" \
        -d client_id=admin-cli -d username=admin -d "password=$PW" -d grant_type=password \
        | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')"

# Idempotent: skip if the realm already exists.
if curl --max-time 12 -fsS -o /dev/null -H "Authorization: Bearer $TOK" "$BASE/admin/realms/$REALM" 2>/dev/null; then
  log "realm '$REALM' already present — nothing to do."
  exit 0
fi

# Build the request body: strip non-standard _comment* fields (Keycloak's strict
# deserializer rejects unknown properties) and point redirect/CORS at this host.
BODY="$(python3 - "$REALM_JSON" "$BASE" <<'PY'
import json,sys
src,host=sys.argv[1],sys.argv[2]
d=json.load(open(src))
def clean(o):
    if isinstance(o,dict): return {k:clean(v) for k,v in o.items() if not k.startswith('_comment')}
    if isinstance(o,list): return [clean(x) for x in o]
    return o
d=clean(d)
for c in d.get('clients',[]):
    if c.get('redirectUris'): c['redirectUris']=[host+'/*']
    if c.get('webOrigins'):   c['webOrigins']=[host]
    if c.get('rootUrl'):      c['rootUrl']=host
    a=c.get('attributes') or {}
    if 'post.logout.redirect.uris' in a: a['post.logout.redirect.uris']=host+'/*'
print(json.dumps(d))
PY
)"

log "creating realm '$REALM' ..."
code="$(curl --max-time 25 -sS -o /tmp/seed-realm-resp.json -w '%{http_code}' \
  -X POST "$BASE/admin/realms" -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/json" --data-binary "$BODY")"
if [[ "$code" == 201 ]]; then
  log "realm '$REALM' created ✅  (login at $BASE)"
else
  echo "[seed-realm] FAILED: HTTP $code $(head -c 200 /tmp/seed-realm-resp.json)"; exit 1
fi
