#!/usr/bin/env bats
# Tests for the rig-values guard in rig/k3s/fetch-kubeconfig.sh (#143).
#
# Fresh-clone reality bar: when neither rig-values.yaml nor
# rig-values.yaml.example exists, the script must exit 1 with an instructive
# message — not a raw `awk: fatal: cannot open file`. With files present the
# precedence is unchanged: rig-values.yaml wins, the committed example is the
# fallback, and SSH_NODE / TITAN_KUBE_CONTEXT env vars bypass the file.
#
# Strategy (same as deploy-namespace-guard.bats): copy the script into a temp
# dir to control RIG_DIR, stub ssh/ss/kubectl on PATH so no tunnel or cluster
# is touched, and sandbox HOME so ~/.kube/config is never written.

setup() {
  REPO_ROOT="$(cd "$(dirname "${BATS_TEST_FILENAME}")/../.." && pwd)"
  SCRIPT="${REPO_ROOT}/rig/k3s/fetch-kubeconfig.sh"

  TMPDIR_RIG="$(mktemp -d)"
  cp "${SCRIPT}" "${TMPDIR_RIG}/fetch-kubeconfig.sh"

  # Sandbox HOME — the merge step writes $HOME/.kube/config.
  FAKE_HOME="$(mktemp -d)"
  export HOME="${FAKE_HOME}"

  # Stubs: `ss` reports no tunnel (empty), `ssh` succeeds silently (both the
  # -fN tunnel and the `cat k3s.yaml` fetch), `kubectl` succeeds silently.
  FAKE_BIN="$(mktemp -d)"
  cat >"${FAKE_BIN}/ss" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  cat >"${FAKE_BIN}/ssh" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  cat >"${FAKE_BIN}/kubectl" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod +x "${FAKE_BIN}/ss" "${FAKE_BIN}/ssh" "${FAKE_BIN}/kubectl"
  export PATH="${FAKE_BIN}:${PATH}"
}

teardown() {
  rm -rf "${TMPDIR_RIG}" "${FAKE_BIN}" "${FAKE_HOME}"
}

run_script() {
  bash "${TMPDIR_RIG}/fetch-kubeconfig.sh"
}

@test "guard: neither values file present → exit 1 with instructive message, no raw awk error" {
  run run_script
  [ "$status" -eq 1 ]
  [[ "$output" == *"rig/k3s/rig-values(.yaml.example) not found"* ]]
  [[ "$output" == *"rig/k3s/README.md"* ]]
  [[ "$output" == *"cp rig/k3s/rig-values.yaml.example rig/k3s/rig-values.yaml"* ]]
  [[ "$output" != *"awk: fatal"* ]]
}

@test "fallback: example file alone is read (no awk fatal) — parse succeeds, sshNode-missing message fires" {
  # Mirrors the committed example: deploy block WITHOUT sshNode. Getting the
  # 'deploy.sshNode missing' message proves the parse got past the file open
  # and the example fallback was actually used.
  cat >"${TMPDIR_RIG}/rig-values.yaml.example" <<'EOF'
deploy:
  namespace: titan
  kubeContext: titan-k3s
EOF
  run run_script
  [ "$status" -eq 1 ]
  [[ "$output" == *"deploy.sshNode missing"* ]]
  [[ "$output" != *"awk: fatal"* ]]
  [[ "$output" != *"not found"* ]]
}

@test "precedence: rig-values.yaml wins over the example when both exist" {
  cat >"${TMPDIR_RIG}/rig-values.yaml" <<'EOF'
deploy:
  kubeContext: ctx-from-real
  sshNode: node-from-real
EOF
  cat >"${TMPDIR_RIG}/rig-values.yaml.example" <<'EOF'
deploy:
  kubeContext: ctx-from-example
  sshNode: node-from-example
EOF
  run run_script
  [ "$status" -eq 0 ]
  [[ "$output" == *"node-from-real"* ]]
  [[ "$output" != *"node-from-example"* ]]
  [[ "$output" == *"merged context 'ctx-from-real'"* ]]
}

@test "happy path: rig-values.yaml with sshNode + kubeContext runs to completion (stubs)" {
  cat >"${TMPDIR_RIG}/rig-values.yaml" <<'EOF'
deploy:
  namespace: titan
  kubeContext: titan-k3s
  sshNode: my-k3s-node
EOF
  run run_script
  [ "$status" -eq 0 ]
  [[ "$output" == *"opening SSH tunnel: localhost:6443 -> my-k3s-node:6443"* ]]
  [[ "$output" == *"use:  kubectl --context titan-k3s"* ]]
}

@test "env override: SSH_NODE + TITAN_KUBE_CONTEXT work with NO values file at all" {
  export SSH_NODE="env-node"
  export TITAN_KUBE_CONTEXT="env-ctx"
  run run_script
  [ "$status" -eq 0 ]
  [[ "$output" != *"not found"* ]]
  [[ "$output" == *"env-node"* ]]
  [[ "$output" == *"merged context 'env-ctx'"* ]]
}
