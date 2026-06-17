#!/usr/bin/env bats
# Tests for dev/release/verify-rig-parity.sh (#1041).
#
# Strategy: shim curl + kubectl via tiny fake binaries on PATH so we can
# drive the script's two inputs (served HTML, in-pod HTML) deterministically.

setup() {
  REPO_ROOT="$(cd "$(dirname "${BATS_TEST_FILENAME}")/../.." && pwd)"
  SCRIPT="${REPO_ROOT}/dev/release/verify-rig-parity.sh"
  FAKE_BIN="$(mktemp -d)"
  export FAKE_BIN
  export PATH="${FAKE_BIN}:${PATH}"

  # Both fakes echo the content of an env-var the test sets per-case.
  cat >"${FAKE_BIN}/fake-curl" <<'EOF'
#!/usr/bin/env bash
# Mimic curl -fsSL: write CURL_OUT, exit CURL_RC.
if [[ "${CURL_RC:-0}" -ne 0 ]]; then exit "${CURL_RC}"; fi
printf '%s' "${CURL_OUT:-}"
EOF
  cat >"${FAKE_BIN}/fake-kubectl" <<'EOF'
#!/usr/bin/env bash
# Mimic `kubectl ... exec ... -- cat ...`: write KUBECTL_OUT, exit KUBECTL_RC.
if [[ "${KUBECTL_RC:-0}" -ne 0 ]]; then exit "${KUBECTL_RC}"; fi
printf '%s' "${KUBECTL_OUT:-}"
EOF
  chmod +x "${FAKE_BIN}/fake-curl" "${FAKE_BIN}/fake-kubectl"

  export CURL_BIN="${FAKE_BIN}/fake-curl"
  export KUBECTL_BIN="${FAKE_BIN}/fake-kubectl"
  export KUBE_CONTEXT=""
  unset CURL_RC KUBECTL_RC CURL_OUT KUBECTL_OUT
}

teardown() {
  rm -rf "${FAKE_BIN}"
}

html_with() {
  printf '<!doctype html><html><body><script type="module" crossorigin src="/assets/%s"></script></body></html>' "$1"
}

@test "happy path: matching hashes exits 0" {
  export CURL_OUT="$(html_with index-abc123.js)"
  export KUBECTL_OUT="$(html_with index-abc123.js)"
  run bash "${SCRIPT}"
  [ "${status}" -eq 0 ]
  [[ "${output}" == *"OK"* ]]
  [[ "${output}" == *"index-abc123.js"* ]]
}

@test "mismatch: different hashes exits 1 with clear message" {
  export CURL_OUT="$(html_with index-OLD000.js)"
  export KUBECTL_OUT="$(html_with index-NEW999.js)"
  run bash "${SCRIPT}"
  [ "${status}" -eq 1 ]
  [[ "${output}" == *"MISMATCH"* ]]
  [[ "${output}" == *"index-OLD000.js"* ]]
  [[ "${output}" == *"index-NEW999.js"* ]]
  [[ "${output}" == *"rig-parity.md"* ]]
}

@test "served HTML missing index hash exits 1" {
  export CURL_OUT="<html><body>no bundle here</body></html>"
  export KUBECTL_OUT="$(html_with index-abc123.js)"
  run bash "${SCRIPT}"
  [ "${status}" -eq 1 ]
  [[ "${output}" == *"could not extract bundle hash from served"* ]]
}

@test "pod HTML missing index hash exits 1" {
  export CURL_OUT="$(html_with index-abc123.js)"
  export KUBECTL_OUT="<html><body>no bundle here</body></html>"
  run bash "${SCRIPT}"
  [ "${status}" -eq 1 ]
  [[ "${output}" == *"could not extract bundle hash from pod"* ]]
}

@test "curl failure (e.g. ingress 503) exits 1" {
  export CURL_RC=22
  export KUBECTL_OUT="$(html_with index-abc123.js)"
  run bash "${SCRIPT}"
  [ "${status}" -eq 1 ]
  [[ "${output}" == *"failed to fetch"* ]]
}

@test "kubectl failure (e.g. rollout paused / pod NotReady) exits 1" {
  export CURL_OUT="$(html_with index-abc123.js)"
  export KUBECTL_RC=1
  run bash "${SCRIPT}"
  [ "${status}" -eq 1 ]
  [[ "${output}" == *"failed to read"* ]]
}

@test "uses first hash when html has multiple matches (idempotent extraction)" {
  export CURL_OUT='<html><script src="/assets/index-FIRST.js"></script><script src="/assets/index-SECOND.js"></script></html>'
  export KUBECTL_OUT='<html><script src="/assets/index-FIRST.js"></script></html>'
  run bash "${SCRIPT}"
  [ "${status}" -eq 0 ]
  [[ "${output}" == *"index-FIRST.js"* ]]
}
