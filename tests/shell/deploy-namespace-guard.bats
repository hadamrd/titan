#!/usr/bin/env bats
# Tests for the namespace-allowlist guard in rig/k3s/deploy.sh (#1042).
#
# Strategy: stub out helm + kubectl on PATH so deploy.sh never touches a real
# cluster, point it at a temp rig-values.yaml, and assert the guard's
# pre-helm exit behavior across allowed / disallowed / overridden cases.

setup() {
  REPO_ROOT="$(cd "$(dirname "${BATS_TEST_FILENAME}")/../.." && pwd)"
  SCRIPT="${REPO_ROOT}/rig/k3s/deploy.sh"

  TMPDIR_RIG="$(mktemp -d)"
  export RIG_TMP="${TMPDIR_RIG}"

  # Stub helm + kubectl: succeed silently on every invocation, including the
  # `kubectl config get-contexts` / `kubectl cluster-info` preflights.
  FAKE_BIN="$(mktemp -d)"
  export FAKE_BIN
  cat >"${FAKE_BIN}/helm" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  cat >"${FAKE_BIN}/kubectl" <<'EOF'
#!/usr/bin/env bash
# Make every subcommand a quiet success — the guard fires BEFORE the
# script reaches anything cluster-touching, so this just keeps the
# preflight path from erroring if execution gets that far.
exit 0
EOF
  chmod +x "${FAKE_BIN}/helm" "${FAKE_BIN}/kubectl"
  export PATH="${FAKE_BIN}:${PATH}"
}

teardown() {
  rm -rf "${TMPDIR_RIG}" "${FAKE_BIN}"
}

# Write a minimal rig-values.yaml at a custom location, then run deploy.sh
# with RIG_DIR pointed at that temp dir. deploy.sh derives RIG_DIR from its
# own location, so we copy the script into our temp dir to control RIG_DIR.
run_deploy_with_namespace() {
  local ns="$1"
  cp "${SCRIPT}" "${TMPDIR_RIG}/deploy.sh"
  cat >"${TMPDIR_RIG}/rig-values.yaml" <<EOF
deploy:
  namespace: ${ns}
  kubeContext: titan-k3s
EOF
  # rig-values.yaml.example must exist for the bootstrap branch (we don't
  # hit it because rig-values.yaml exists, but defensive).
  : >"${TMPDIR_RIG}/rig-values.yaml.example"
  bash "${TMPDIR_RIG}/deploy.sh"
}

@test "guard: aborts with exit 2 when namespace is titan-staging (default allowlist)" {
  run run_deploy_with_namespace "titan-staging"
  [ "$status" -eq 2 ]
  [[ "$output" == *"namespace 'titan-staging' is not in TITAN_ALLOWED_NAMESPACES"* ]]
  [[ "$output" == *"rig-namespaces.md"* ]]
}

@test "guard: aborts on arbitrary non-canonical namespace (default allowlist)" {
  run run_deploy_with_namespace "random-ns"
  [ "$status" -eq 2 ]
  [[ "$output" == *"'random-ns'"* ]]
}

@test "guard: allows canonical namespace 'titan' through" {
  # Past the guard, the stubs return 0, but the script then runs
  # `verify-rig-parity.sh` which will fail (no real cluster). Skip that
  # part — we only care that the guard didn't abort with exit 2.
  export SKIP_PARITY_CHECK=1
  run run_deploy_with_namespace "titan"
  [ "$status" -ne 2 ]
  # Should not contain the guard's abort message.
  [[ "$output" != *"is not in TITAN_ALLOWED_NAMESPACES"* ]]
}

@test "guard: TITAN_ALLOWED_NAMESPACES env override permits extra namespaces" {
  export TITAN_ALLOWED_NAMESPACES="titan,titan-staging"
  export SKIP_PARITY_CHECK=1
  run run_deploy_with_namespace "titan-staging"
  [ "$status" -ne 2 ]
  [[ "$output" != *"is not in TITAN_ALLOWED_NAMESPACES"* ]]
}

@test "guard: TITAN_ALLOWED_NAMESPACES with whitespace is parsed correctly" {
  export TITAN_ALLOWED_NAMESPACES="titan, titan-edge "
  export SKIP_PARITY_CHECK=1
  run run_deploy_with_namespace "titan-edge"
  [ "$status" -ne 2 ]
}

@test "guard: NAMESPACE env var also goes through the guard" {
  export NAMESPACE="titan-staging"
  run run_deploy_with_namespace "titan"  # values file says titan, env overrides
  [ "$status" -eq 2 ]
  [[ "$output" == *"'titan-staging'"* ]]
}
