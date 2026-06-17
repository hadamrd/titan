#!/usr/bin/env bats
# Helm-template unit tests for the Infisical-secret e2e posture (#1242).
#
# Spec 46 (46-infisical-secret.spec.ts) only flips skip→green when the rig runs
# TITAN_SECRETS_BACKEND=infisical on BOTH the server and the worker, the worker
# carries an INFISICAL_TOKEN, and a secret is pre-seeded. These tests render the
# chart with `helm template` (no cluster) and assert that contract — plus the
# gate-off invariant that the DEFAULT (db-envelope) rig wires NO Infisical env,
# so spec 46 skips cleanly and stays green.
#
# Requires: helm 3 on PATH. Run via `task k3s:test:infisical` or `bats`.

setup() {
  REPO_ROOT="$(cd "$(dirname "${BATS_TEST_FILENAME}")/../.." && pwd)"
  CHART="${REPO_ROOT}/rig/k3s/helm/titan"
  VALUES="${REPO_ROOT}/rig/k3s/rig-values.yaml.example"
  OVERLAY="${REPO_ROOT}/rig/k3s/values-infisical-e2e.yaml"
  command -v helm >/dev/null 2>&1 || skip "helm not installed"
}

# Default rig — db-envelope, the gate-off posture.
render_default() {
  helm template titan "${CHART}" -f "${VALUES}"
}

# Opt-in e2e rig — the committed overlay + the out-of-band project id / token.
render_overlay() {
  helm template titan "${CHART}" -f "${VALUES}" -f "${OVERLAY}" \
    --set titanServer.infisical.projectId=proj-abc \
    --set titanWorker.infisical.projectId=proj-abc \
    --set secrets.infisicalToken=tok-e2e
}

# ── gate-off: the default db-envelope rig wires NO Infisical env (spec skips) ──

@test "default rig: server + worker resolve db-envelope" {
  run render_default
  [ "$status" -eq 0 ]
  # Both the server and the worker carry TITAN_SECRETS_BACKEND=db-envelope.
  count="$(echo "$output" | grep -c 'value: "db-envelope"')"
  [ "$count" -ge 2 ]
}

@test "default rig: NO INFISICAL_TOKEN rendered anywhere (gate-off)" {
  run render_default
  [ "$status" -eq 0 ]
  ! echo "$output" | grep -q 'INFISICAL_TOKEN'
}

@test "default rig: NO INFISICAL_* coordinate env (gate-off)" {
  run render_default
  [ "$status" -eq 0 ]
  ! echo "$output" | grep -q 'INFISICAL_PROJECT_ID'
  ! echo "$output" | grep -q 'INFISICAL_ENV'
}

# ── happy: the overlay flips BOTH server and worker to infisical ──────────────

@test "overlay: server + worker run TITAN_SECRETS_BACKEND=infisical" {
  run render_overlay
  [ "$status" -eq 0 ]
  count="$(echo "$output" | grep -c 'value: "infisical"')"
  [ "$count" -ge 2 ]
}

@test "overlay: INFISICAL_TOKEN is sourced from titan-secrets on BOTH pods" {
  run render_overlay
  [ "$status" -eq 0 ]
  # One secretKeyRef block per pod (server + worker) → at least two refs to the
  # INFISICAL_TOKEN key. This is the #1242 worker-wiring AC: the worker (not just
  # the server) must carry the token to resolve the bound secret into the build env.
  count="$(echo "$output" | grep -c 'key: INFISICAL_TOKEN')"
  [ "$count" -ge 2 ]
}

@test "overlay: titan-secrets materialises an INFISICAL_TOKEN key" {
  run render_overlay
  [ "$status" -eq 0 ]
  echo "$output" | grep -qE '^kind: Secret$'
  echo "$output" | grep -q 'INFISICAL_TOKEN: "tok-e2e"'
}

@test "overlay: Infisical coordinates wired with env=staging + the project id" {
  run render_overlay
  [ "$status" -eq 0 ]
  echo "$output" | grep -q 'name: INFISICAL_ENV'
  # env=staging appears for both server and worker.
  count="$(echo "$output" | grep -c 'value: "staging"')"
  [ "$count" -ge 2 ]
  echo "$output" | grep -q 'value: "proj-abc"'
}

# ── adversarial: an unknown backend fails LOUD, never silent-resolves-empty ───

@test "unknown secretsBackend on the server fails the render loud" {
  run helm template titan "${CHART}" -f "${VALUES}" --set titanServer.secretsBackend=bogus
  [ "$status" -ne 0 ]
  echo "$output" | grep -qi 'secretsBackend must be one of'
}

@test "unknown secretsBackend on the worker fails the render loud" {
  run helm template titan "${CHART}" -f "${VALUES}" --set titanWorker.secretsBackend=bogus
  [ "$status" -ne 0 ]
  echo "$output" | grep -qi 'secretsBackend must be one of'
}

# ── helm lint: chart valid both ways (default + overlay) ──────────────────────

@test "helm lint passes on the default (db-envelope) rig" {
  run helm lint "${CHART}" -f "${VALUES}"
  [ "$status" -eq 0 ]
}

@test "helm lint passes with the infisical e2e overlay" {
  run helm lint "${CHART}" -f "${VALUES}" -f "${OVERLAY}" \
    --set titanServer.infisical.projectId=proj-abc \
    --set titanWorker.infisical.projectId=proj-abc \
    --set secrets.infisicalToken=tok-e2e
  [ "$status" -eq 0 ]
}
