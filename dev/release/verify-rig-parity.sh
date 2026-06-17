#!/usr/bin/env bash
# verify-rig-parity.sh — catch in-cluster bundle split-brain (#1041).
#
# Compares the index-<hash>.js asset hash served by the public rig's
# nginx-fronted titan-ui ingress against the index.html shipped inside
# the live titan-ui pod. A mismatch means the served bundle is stale
# (e.g. ingress is pointing at an old image, or the rollout never picked
# up the new tag — exactly the failure mode that hid the /profile#tokens
# fix in trunk for 6h before anyone noticed).
#
# Exit codes:
#   0  hashes match — rig is in parity with the deployed pod
#   1  hashes differ — split-brain detected (or fetch failures)
#   2  usage / environment error
#
# Env overrides (mostly for tests):
#   RIG_URL            default: https://titan.test.example.com/
#   KUBE_CONTEXT       default: $KUBE_CONTEXT or unset (kubectl's default)
#   KUBE_NAMESPACE     default: titan
#   KUBE_DEPLOYMENT    default: deploy/titan-ui
#   POD_INDEX_PATH     default: /usr/share/nginx/html/index.html
#   CURL_BIN           default: curl
#   KUBECTL_BIN        default: kubectl
set -euo pipefail

RIG_URL="${RIG_URL:-https://titan.test.example.com/}"
KUBE_NAMESPACE="${KUBE_NAMESPACE:-titan}"
KUBE_DEPLOYMENT="${KUBE_DEPLOYMENT:-deploy/titan-ui}"
POD_INDEX_PATH="${POD_INDEX_PATH:-/usr/share/nginx/html/index.html}"
CURL_BIN="${CURL_BIN:-curl}"
KUBECTL_BIN="${KUBECTL_BIN:-kubectl}"

KCTX_FLAG=()
if [[ -n "${KUBE_CONTEXT:-}" ]]; then
  KCTX_FLAG=(--context "${KUBE_CONTEXT}")
fi

log() { printf '%s\n' "$*" >&2; }

# Extract the first index-<hash>.js basename from an HTML stream.
# Works with both quoted (src="...") and unquoted href forms.
extract_bundle_hash() {
  # Match index-<hash>.js (hash = vite's content hash, alnum + maybe _-)
  # Print just the first match. No match => empty stdout.
  grep -oE 'index-[A-Za-z0-9_-]+\.js' | head -n1 || true
}

fetch_served_hash() {
  local body
  if ! body="$("${CURL_BIN}" -fsSL --max-time 15 "${RIG_URL}")"; then
    log "verify-rig-parity: ERROR — failed to fetch ${RIG_URL}"
    return 1
  fi
  printf '%s' "${body}" | extract_bundle_hash
}

fetch_pod_hash() {
  local body
  if ! body="$("${KUBECTL_BIN}" "${KCTX_FLAG[@]}" -n "${KUBE_NAMESPACE}" \
      exec "${KUBE_DEPLOYMENT}" -- cat "${POD_INDEX_PATH}" 2>/dev/null)"; then
    log "verify-rig-parity: ERROR — failed to read ${POD_INDEX_PATH} from ${KUBE_DEPLOYMENT}"
    return 1
  fi
  printf '%s' "${body}" | extract_bundle_hash
}

main() {
  local served pod
  served="$(fetch_served_hash || true)"
  pod="$(fetch_pod_hash || true)"

  if [[ -z "${served}" ]]; then
    log "verify-rig-parity: FAIL — could not extract bundle hash from served ${RIG_URL}"
    exit 1
  fi
  if [[ -z "${pod}" ]]; then
    log "verify-rig-parity: FAIL — could not extract bundle hash from pod index.html"
    exit 1
  fi

  if [[ "${served}" == "${pod}" ]]; then
    log "verify-rig-parity: OK — served and in-pod bundle agree (${served})"
    exit 0
  fi

  log "verify-rig-parity: MISMATCH — split-brain detected"
  log "  served by ingress  : ${served}"
  log "  in-pod /index.html : ${pod}"
  log "  rig URL            : ${RIG_URL}"
  log "  deployment         : ${KUBE_NAMESPACE}/${KUBE_DEPLOYMENT}"
  log "  remediation        : see docs/ops/runbooks/rig-parity.md"
  exit 1
}

main "$@"
