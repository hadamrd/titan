#!/usr/bin/env bash
# Deploy the standalone Titan k3s rig — ONE Helm chart, ONE `helm upgrade`.
#
# The chart (rig/k3s/helm/titan/) deploys the same 5-container contract as the
# local rig (rig/local/docker-compose.yml):
#
#   postgres        — Titan engine database
#   keycloak        — OIDC issuer (optional; disable for external IDP)
#   titan-server    — Quarkus HTTP API + engine
#   titan-ui        — React SPA served by nginx
#   titan-worker    — pull-based execution agent
#
# All site config lives in rig/k3s/rig-values.yaml. On first run this script
# scaffolds it from rig-values.yaml.example and stops so you can fill it in.
#
# Optional env:
#   KUBE_CONTEXT  - overrides deploy.kubeContext from the values file
#   NAMESPACE     - overrides deploy.namespace from the values file
set -euo pipefail

# git-bash on Windows rewrites unix paths in argv; harmless no-ops on Linux.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

RIG_DIR="$(cd "$(dirname "$0")" && pwd)"
CHART_DIR="$RIG_DIR/helm/titan"

log() { printf '\033[1;34m[deploy]\033[0m %s\n' "$*"; }

# ---- rig-values.yaml: the single site-config file --------------------------
VALUES="$RIG_DIR/rig-values.yaml"
if [[ ! -f "$VALUES" ]]; then
  cp "$RIG_DIR/rig-values.yaml.example" "$VALUES"
  echo "Created rig/k3s/rig-values.yaml from rig-values.yaml.example."
  echo "Fill in your cluster + secrets (every key is documented in the file),"
  echo "then re-run. rig-values.yaml is gitignored — never commit real secrets."
  exit 1
fi

# Pull a scalar from the `deploy:` block of the values file (flat key: value).
deploy_val() {
  awk -v key="$1" '
    /^deploy:/        { in_d=1; next }
    /^[^[:space:]]/   { in_d=0 }
    in_d && $1 == key":" {
      sub(/^[[:space:]]*[^:]+:[[:space:]]*/, "")
      sub(/[[:space:]]*#.*$/, "")
      gsub(/^["'\'']|["'\'']$/, "")
      print; exit
    }
  ' "$VALUES"
}

NS="${NAMESPACE:-$(deploy_val namespace)}"
CTX="${KUBE_CONTEXT:-$(deploy_val kubeContext)}"
[[ -n "$NS"  ]] || { echo "deploy.namespace missing from rig-values.yaml";   exit 1; }
[[ -n "$CTX" ]] || { echo "deploy.kubeContext missing from rig-values.yaml"; exit 1; }

# ---- namespace allowlist guard (#1042) -------------------------------------
# Two parallel `titan` helm releases (titan/ and titan-staging/) created a
# split-brain: every `task deploy:k3s:trunk` was landing in titan-staging/ while
# the public ingress kept serving the stale titan/ release. To stop the next
# regression at the door, the canonical rig namespace is `titan` and any other
# target must be opted into explicitly. See
# docs/ops/runbooks/rig-namespaces.md for the rationale and recovery flow.
ALLOWED="${TITAN_ALLOWED_NAMESPACES:-titan}"
ns_allowed=0
IFS=',' read -ra _allowed_arr <<< "$ALLOWED"
for _ns in "${_allowed_arr[@]}"; do
  # trim whitespace
  _ns="${_ns#"${_ns%%[![:space:]]*}"}"; _ns="${_ns%"${_ns##*[![:space:]]}"}"
  if [[ "$_ns" == "$NS" ]]; then ns_allowed=1; break; fi
done
if [[ "$ns_allowed" -ne 1 ]]; then
  cat >&2 <<EOF
[deploy] ABORT: target namespace '$NS' is not in TITAN_ALLOWED_NAMESPACES ('$ALLOWED').

This guard exists to stop a second 'titan' release from being created in a
non-canonical namespace (the split-brain that #1042 fixed). The canonical
namespace on the public test rig is 'titan'.

To proceed:
  - Edit rig/k3s/rig-values.yaml so deploy.namespace = titan, OR
  - Set TITAN_ALLOWED_NAMESPACES explicitly (e.g. for a brand-new rig).

See docs/ops/runbooks/rig-namespaces.md for the full recovery flow.
EOF
  exit 2
fi

# ---- preflight --------------------------------------------------------------
command -v helm >/dev/null 2>&1 || { echo "helm not found — install Helm 3"; exit 1; }
if ! kubectl config get-contexts "$CTX" >/dev/null 2>&1; then
  echo "kube context '$CTX' missing — run: task k3s:kubeconfig" >&2
  exit 1
fi
if ! kubectl --context "$CTX" cluster-info >/dev/null 2>&1; then
  echo "cluster '$CTX' unreachable — is the SSH tunnel up? run: task k3s:kubeconfig" >&2
  exit 1
fi

# ---- the one deploy step ----------------------------------------------------
log "helm upgrade --install titan -n $NS"
helm --kube-context "$CTX" upgrade --install titan \
    "$CHART_DIR" \
    --namespace "$NS" --create-namespace \
    -f "$VALUES" \
    --timeout 5m

# ---- wait for ExternalSecret → titan-secrets to materialise (#1160) ---------
# When secrets.externalSecret.enabled=true the chart ships an ExternalSecret
# (External-Secrets Operator) as the SOLE owner of `titan-secrets`; ESO syncs
# POSTGRES_PASSWORD from Infisical asynchronously AFTER helm applies the CR. If
# titan-server / postgres roll before the Secret exists they sit in
# CreateContainerConfigError until ESO catches up. Block on the sync here so the
# rollout below starts against a materialised Secret and a non-placeholder
# password. Best-effort + guarded: on the plaintext path there is no
# ExternalSecret resource, so this is a no-op.
if kubectl --context "$CTX" -n "$NS" get externalsecret titan-secrets >/dev/null 2>&1; then
  log "waiting for ExternalSecret titan-secrets to sync from Infisical"
  kubectl --context "$CTX" -n "$NS" wait --for=condition=Ready \
      externalsecret/titan-secrets --timeout=2m \
    || echo "[deploy] WARN: ExternalSecret titan-secrets not Ready yet — ESO may still be syncing; titan-server will retry once the Secret lands. See docs/ops/runbooks/k3s-postgres-password.md" >&2
fi

# ---- force a pod roll on same-tag-different-digest --------------------------
# With `image.tag: latest-trunk` (default since #921), helm-upgrade is a no-op
# whenever only the digest behind the floating tag changed — the rendered
# Deployment spec is byte-identical, so k8s sees nothing to reconcile and pods
# keep running the old image. `rollout restart` forces a fresh ReplicaSet,
# which (paired with `imagePullPolicy: Always`) re-pulls and rolls.
# Workloads pinned to an immutable tag (`trunk-<sha>` or `0.1.0`) also benefit:
# the restart is cheap and ensures every redeploy actually exercises the image.
log "rollout restart titan-{server,ui,worker} (pull fresh digest under floating tag)"
kubectl --context "$CTX" -n "$NS" rollout restart \
    deploy/titan-server deploy/titan-ui deploy/titan-worker || true

# ---- wait for rollout, then verify ingress↔pod bundle parity (#1042) ------
# A successful `helm upgrade` is not the same as users seeing the new code:
# a split-brain (wrong namespace, stale image, cached layer, paused rollout)
# can leave the ingress serving yesterday's bundle while the new release sits
# quietly in another namespace or another pod. We block on rollout-status here
# and then run the same parity check the 15-min cron alert uses, so the deploy
# command itself fails loudly when it lands in the wrong place.
log "waiting for rollouts to settle"
kubectl --context "$CTX" -n "$NS" rollout status deploy/titan-ui     --timeout=3m || true
kubectl --context "$CTX" -n "$NS" rollout status deploy/titan-server --timeout=3m || true

# ---- seed the Keycloak realm (idempotent; no-op if it already exists) -------
# Done via the Admin REST API against the running Keycloak — NOT Keycloak's
# `--import-realm` (which re-augments on every boot and crashlooped this rig).
SEED_REALM="$RIG_DIR/keycloak/seed-realm.sh"
if [[ -x "$SEED_REALM" ]]; then
  TITAN_KUBE_CONTEXT="$CTX" TITAN_NAMESPACE="$NS" "$SEED_REALM" \
    || echo "[deploy] WARN: realm seed failed — login may be unavailable until fixed (see $SEED_REALM)"
fi

PARITY_SCRIPT="$(cd "$RIG_DIR/../.." && pwd)/dev/release/verify-rig-parity.sh"
if [[ "${SKIP_PARITY_CHECK:-0}" != "1" && -x "$PARITY_SCRIPT" ]]; then
  log "post-deploy parity check (verify-rig-parity.sh)"
  KUBE_CONTEXT="$CTX" KUBE_NAMESPACE="$NS" "$PARITY_SCRIPT" \
    || { echo "[deploy] ABORT: ingress↔pod bundle parity mismatch — see docs/ops/runbooks/rig-parity.md" >&2; exit 3; }
fi

# ---- auth smoke: prove the rig actually WORKS (login → authed API → 200) ----
# Hard gate: catches realm/OIDC-issuer/audience/DB-auth breaks that unit tests
# can't. A deploy that can't log in + serve an authed API call is NOT a success.
SMOKE="$RIG_DIR/smoke-auth.sh"
if [[ "${SKIP_AUTH_SMOKE:-0}" != "1" && -x "$SMOKE" ]]; then
  log "post-deploy auth smoke (login → authed API)"
  RIG_HOST="$(awk '/^ingress:/{i=1;next}/^[^[:space:]]/{i=0}i&&$1=="host:"{sub(/^[^:]*:[[:space:]]*/,"");sub(/[[:space:]]*#.*/,"");gsub(/^["'\'']|["'\'']$/,"");print;exit}' "$VALUES")" \
    "$SMOKE" || { echo "[deploy] ABORT: rig auth smoke failed — login or authed API is broken (realm/OIDC issuer/audience). See $SMOKE" >&2; exit 4; }
fi

# ---- summary ---------------------------------------------------------------
echo
log "stack deployed (namespace: $NS)."
HOST="$(awk '/^ingress:/{in_i=1;next} /^[^[:space:]]/{in_i=0} in_i && $1=="host:"{sub(/^[[:space:]]*host:[[:space:]]*/,"");sub(/[[:space:]]*#.*$/,"");gsub(/^["'\'']|["'\'']$/,"");print;exit}' "$VALUES" || true)"
if [[ -n "$HOST" ]]; then
  echo "  UI:           https://$HOST/"
  echo "  Smoke check:  curl -fsS https://$HOST/api/v1/stats"
fi
echo
log "Pod status:    kubectl --context $CTX -n $NS get pods"
log "titan-server:  kubectl --context $CTX -n $NS logs -f deploy/titan-server"
