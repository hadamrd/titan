#!/usr/bin/env bats
# Helm-template unit tests for the postgres-password ExternalSecret (#1160).
#
# Strategy: render the titan chart with `helm template` (no cluster needed) and
# assert the mutual-exclusion contract + the Infisical sourcing. These are the
# "Unit (helm template / helm lint)" rows of the #1160 test matrix.
#
# Requires: helm 3 on PATH. Run via `task k3s:test:secrets` or `bats`.

setup() {
  REPO_ROOT="$(cd "$(dirname "${BATS_TEST_FILENAME}")/../.." && pwd)"
  CHART="${REPO_ROOT}/rig/k3s/helm/titan"
  VALUES="${REPO_ROOT}/rig/k3s/rig-values.yaml.example"
  command -v helm >/dev/null 2>&1 || skip "helm not installed"
}

render_default() {
  helm template titan "${CHART}" -f "${VALUES}"
}

render_eso() {
  helm template titan "${CHART}" -f "${VALUES}" \
    --set secrets.externalSecret.enabled=true
}

# ── back-compat: flag OFF renders the plaintext Secret, no ExternalSecret ────

@test "default path renders the plaintext titan-secrets Secret" {
  run render_default
  [ "$status" -eq 0 ]
  echo "$output" | grep -qE '^kind: Secret$'
  echo "$output" | grep -q 'name: titan-secrets'
  echo "$output" | grep -q 'POSTGRES_PASSWORD:'
}

@test "default path does NOT render an ExternalSecret" {
  run render_default
  [ "$status" -eq 0 ]
  ! echo "$output" | grep -qE '^kind: ExternalSecret$'
}

# ── ESO path: flag ON renders the ExternalSecret, suppresses the plaintext ───

@test "externalSecret path renders an ExternalSecret owning titan-secrets" {
  run render_eso
  [ "$status" -eq 0 ]
  echo "$output" | grep -qE '^kind: ExternalSecret$'
  echo "$output" | grep -q 'creationPolicy: Owner'
}

@test "externalSecret path does NOT also render the plaintext Secret (single owner)" {
  run render_eso
  [ "$status" -eq 0 ]
  # No bare `kind: Secret` — only the ExternalSecret may claim titan-secrets.
  ! echo "$output" | grep -qE '^kind: Secret$'
}

@test "externalSecret sources POSTGRES_PASSWORD from the Infisical remoteKey" {
  run render_eso
  [ "$status" -eq 0 ]
  # ESO fetches the remote key into secretKey `postgresPassword`...
  echo "$output" | grep -q 'secretKey: postgresPassword'
  echo "$output" | grep -q 'key: POSTGRES_PASSWORD'
  # ...and the target template wires it into titan-secrets/POSTGRES_PASSWORD
  # via ESO's own (helm-escaped) template expression.
  echo "$output" | grep -qF 'POSTGRES_PASSWORD: "{{ .postgresPassword }}"'
}

@test "rendered POSTGRES_PASSWORD is never the CHANGE-ME placeholder on the ESO path" {
  run render_eso
  [ "$status" -eq 0 ]
  ! echo "$output" | grep -q 'CHANGE-ME-postgres-password'
}

# ── both paths: refs stay stable on key POSTGRES_PASSWORD ────────────────────

@test "postgres + titan-server secretKeyRef titan-secrets/POSTGRES_PASSWORD (default)" {
  run render_default
  [ "$status" -eq 0 ]
  # at least two secretKeyRef blocks pointing at the same key (postgres init +
  # titan-server TITAN_DB_PASSWORD).
  count="$(echo "$output" | grep -c 'key: POSTGRES_PASSWORD')"
  [ "$count" -ge 2 ]
  echo "$output" | grep -q 'name: titan-secrets'
}

@test "postgres + titan-server secretKeyRef key name unchanged on ESO path" {
  run render_eso
  [ "$status" -eq 0 ]
  # the consumers still reference key POSTGRES_PASSWORD (key name unchanged AC).
  count="$(echo "$output" | grep -c 'key: POSTGRES_PASSWORD')"
  [ "$count" -ge 2 ]
}

# ── helm lint: the chart must be valid both ways (#1160 matrix "helm lint passes") ──
# Previously this gate was run manually and only described in the PR body; encode it
# so it runs on every CI/bats invocation (critic #1162 sev2).

@test "helm lint passes with externalSecret.enabled=false (default)" {
  run helm lint "${CHART}" -f "${VALUES}"
  [ "$status" -eq 0 ]
}

@test "helm lint passes with externalSecret.enabled=true" {
  run helm lint "${CHART}" -f "${VALUES}" --set secrets.externalSecret.enabled=true
  [ "$status" -eq 0 ]
}
