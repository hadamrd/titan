#!/usr/bin/env bash
# Wire the adaptiq k3s cluster into the operator's standard kube config.
#
# Two jobs, both idempotent:
#   1. Ensure the SSH tunnel is up — port 6443 is firewalled on Hetzner, so
#      the API server is only reachable through:
#          ssh -fN -L 6443:127.0.0.1:6443 adaptiq
#   2. Merge the cluster into ~/.kube/config as a named context `titan-k3s`,
#      with the API server rewritten to https://127.0.0.1:6443 (the tunnel).
#
# After this, every tool just uses `kubectl --context titan-k3s` — no
# KUBECONFIG env juggling, no throwaway /tmp file to thread through.
#
# /etc/rancher/k3s/k3s.yaml is mode 0644 on the node, so no sudo needed.
set -euo pipefail

RIG_DIR="$(cd "$(dirname "$0")" && pwd)"
VALUES="$RIG_DIR/rig-values.yaml"

# Read the SSH node alias / context name from the `deploy:` block of
# rig-values.yaml — the same single site-config file deploy.sh uses. Env vars
# still override (SSH_NODE / TITAN_KUBE_CONTEXT) for one-off runs. If the
# values file is absent (first-ever run, before deploy.sh scaffolds it), fall
# back to the example file so this script still works standalone.
deploy_val() {
  local f="$VALUES"; [[ -f "$f" ]] || f="$RIG_DIR/rig-values.example.yaml"
  awk -v key="$1" '
    /^deploy:/        { in_d=1; next }
    /^[^[:space:]]/   { in_d=0 }
    in_d && $1 == key":" {
      sub(/^[[:space:]]*[^:]+:[[:space:]]*/, "")
      sub(/[[:space:]]*#.*$/, "")
      gsub(/^["'\'']|["'\'']$/, "")
      print; exit
    }
  ' "$f"
}

SSH_NODE="${SSH_NODE:-$(deploy_val sshNode)}"
CONTEXT="${TITAN_KUBE_CONTEXT:-$(deploy_val kubeContext)}"
KUBE_CONFIG="${HOME}/.kube/config"
[[ -n "$SSH_NODE" ]] || { echo "deploy.sshNode missing — set it in rig-values.yaml or pass SSH_NODE"; exit 1; }
[[ -n "$CONTEXT"  ]] || { echo "deploy.kubeContext missing — set it in rig-values.yaml or pass TITAN_KUBE_CONTEXT"; exit 1; }

log() { printf '\033[1;34m[kubeconfig]\033[0m %s\n' "$*"; }

# ---- 1. SSH tunnel ----------------------------------------------------------
if ss -tln 2>/dev/null | grep -q '127.0.0.1:6443'; then
  log "SSH tunnel already up (localhost:6443)"
else
  log "opening SSH tunnel: localhost:6443 -> ${SSH_NODE}:6443"
  ssh -fN -L 6443:127.0.0.1:6443 "$SSH_NODE"
fi

# ---- 2. Merge into ~/.kube/config ------------------------------------------
# Fetch the raw k3s kubeconfig to a temp file; its cluster/user/context are
# all named `default`. Rename them to titan-k3s and rewrite the server URL,
# then merge into ~/.kube/config so the operator's other contexts survive.
TMP_RAW="$(mktemp)"
TMP_NAMED="$(mktemp)"
trap 'rm -f "$TMP_RAW" "$TMP_NAMED"' EXIT

log "fetching k3s kubeconfig from ${SSH_NODE}"
ssh "$SSH_NODE" 'cat /etc/rancher/k3s/k3s.yaml' > "$TMP_RAW"

# k3s names its cluster/user/context all `default`. Rename every reference
# to `titan-k3s` and point the server at the tunnel. Plain text rewrite is
# the only reliable way — kubectl has no `rename-cluster`/`rename-user`.
sed -e 's/\bdefault\b/'"$CONTEXT"'/g' \
    -e 's#server: https://127.0.0.1:6443#server: https://127.0.0.1:6443#' \
    "$TMP_RAW" > "$TMP_NAMED"
# Ensure the server points at the tunnel regardless of what k3s emitted.
KUBECONFIG="$TMP_NAMED" kubectl config set-cluster "$CONTEXT" \
  --server="https://127.0.0.1:6443" >/dev/null

mkdir -p "$(dirname "$KUBE_CONFIG")"
touch "$KUBE_CONFIG"
chmod 600 "$KUBE_CONFIG"

# Merge: flatten the existing config + the named one into a single file.
MERGED="$(KUBECONFIG="${KUBE_CONFIG}:${TMP_NAMED}" kubectl config view --flatten --raw)"
printf '%s\n' "$MERGED" > "$KUBE_CONFIG"
chmod 600 "$KUBE_CONFIG"

log "merged context '${CONTEXT}' into ${KUBE_CONFIG}"
log "verifying cluster reachability"
kubectl --context "$CONTEXT" get nodes 2>&1 | head -5

echo
log "ready — use:  kubectl --context ${CONTEXT} ..."
