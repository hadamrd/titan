#!/usr/bin/env bats
# Adversarial unit tests for rig/k3s/seed-infisical-e2e-secret.sh (#1242).
#
# The seed script is the documented, idempotent step that pre-seeds the ONE
# Infisical secret spec 46 binds. These tests stub the `infisical` CLI on PATH
# (no live Infisical) and hunt the sad paths: a cred id without '/', a missing
# token / project id, and the upsert idempotency + value-generation contract.

setup() {
  REPO_ROOT="$(cd "$(dirname "${BATS_TEST_FILENAME}")/../.." && pwd)"
  SCRIPT="${REPO_ROOT}/rig/k3s/seed-infisical-e2e-secret.sh"
  # Per-test scratch dir. NOT BATS_TEST_TMPDIR — that var only exists in
  # Bats >= 1.4.0; on the rig's Bats 1.2.1 it is empty, which collapsed
  # STUB_BIN to "/bin" and made every seed test error out (Permission
  # denied writing /bin/infisical). mktemp under BATS_TMPDIR is portable
  # back to old Bats and isolated per test.
  TEST_TMP="$(mktemp -d "${BATS_TMPDIR:-/tmp}/seed-infisical.XXXXXX")"
  STUB_BIN="${TEST_TMP}/bin"
  STUB_LOG="${TEST_TMP}/infisical-args.log"
  mkdir -p "${STUB_BIN}"
  # Stub `infisical`: record argv (one invocation per line), succeed.
  cat > "${STUB_BIN}/infisical" <<EOF
#!/usr/bin/env bash
printf '%s\n' "\$*" >> "${STUB_LOG}"
exit 0
EOF
  chmod +x "${STUB_BIN}/infisical"
  PATH="${STUB_BIN}:${PATH}"
  # A baseline-valid environment; individual tests unset pieces to attack them.
  export INFISICAL_TOKEN="tok-xyz"
  export INFISICAL_PROJECT_ID="proj-abc"
  export E2E_INFISICAL_CRED="titan-e2e/E2E_INFISICAL_SECRET"
  export E2E_INFISICAL_VALUE="hunter2-known-plaintext"
}

teardown() {
  [ -n "${TEST_TMP:-}" ] && rm -rf "${TEST_TMP}"
}

# ── adversarial: cred id must be a scope/key ──────────────────────────────────

@test "rejects a cred id with no '/' (must be scope/key)" {
  export E2E_INFISICAL_CRED="no-slash-here"
  run bash "${SCRIPT}"
  [ "$status" -ne 0 ]
  echo "$output" | grep -qi "must be a scope/key"
  # The bad input never reached Infisical.
  [ ! -f "${STUB_LOG}" ]
}

@test "rejects a cred id with an empty key part (trailing slash)" {
  export E2E_INFISICAL_CRED="titan-e2e/"
  run bash "${SCRIPT}"
  [ "$status" -ne 0 ]
  echo "$output" | grep -qi "empty key part"
}

# ── adversarial: missing external-dependency config ───────────────────────────

@test "fails loud when INFISICAL_TOKEN is missing" {
  unset INFISICAL_TOKEN
  run bash "${SCRIPT}"
  [ "$status" -ne 0 ]
  echo "$output" | grep -qi "INFISICAL_TOKEN is not set"
}

@test "fails loud when INFISICAL_PROJECT_ID is missing" {
  unset INFISICAL_PROJECT_ID
  run bash "${SCRIPT}"
  [ "$status" -ne 0 ]
  echo "$output" | grep -qi "INFISICAL_PROJECT_ID is not set"
}

# ── happy: upserts the KEY part (not the scope/key) into env staging ──────────

@test "seeds the Infisical secret NAME = the key part of scope/key" {
  run bash "${SCRIPT}"
  [ "$status" -eq 0 ]
  # The secret name passed to `infisical secrets set` is the KEY part only.
  grep -q 'secrets set E2E_INFISICAL_SECRET=hunter2-known-plaintext' "${STUB_LOG}"
  # NOT the full scope/key.
  ! grep -q 'titan-e2e/E2E_INFISICAL_SECRET=' "${STUB_LOG}"
  # Seeded into the staging env.
  grep -q -- '--env staging' "${STUB_LOG}"
}

@test "is idempotent — re-running upserts the same secret, exit 0 both times" {
  run bash "${SCRIPT}"
  [ "$status" -eq 0 ]
  run bash "${SCRIPT}"
  [ "$status" -eq 0 ]
  # Two upsert invocations, identical args (upsert == idempotent).
  count="$(grep -c 'secrets set E2E_INFISICAL_SECRET=' "${STUB_LOG}")"
  [ "$count" -eq 2 ]
}

@test "generates a value when E2E_INFISICAL_VALUE is unset (never empty, never hard-coded)" {
  unset E2E_INFISICAL_VALUE
  run bash "${SCRIPT}"
  [ "$status" -eq 0 ]
  # A generated `e2e-<hex>` value was upserted — not an empty assignment.
  grep -qE 'secrets set E2E_INFISICAL_SECRET=e2e-[0-9a-f]{32}' "${STUB_LOG}"
  ! grep -qE 'secrets set E2E_INFISICAL_SECRET= ' "${STUB_LOG}"
}

@test "honours INFISICAL_ENV override" {
  export INFISICAL_ENV="dev"
  run bash "${SCRIPT}"
  [ "$status" -eq 0 ]
  grep -q -- '--env dev' "${STUB_LOG}"
}
