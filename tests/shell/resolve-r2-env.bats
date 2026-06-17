#!/usr/bin/env bats
# Adversarial unit tests for e2e/resolve-r2-env.sh (#1241).
#
# The resolver fills the TITAN_R2_* env spec 54 reads — from the caller's env
# first, then from Infisical — and execs Playwright with LAYER2_RIG_AVAILABLE=1.
# These tests stub `infisical` on PATH (no live Infisical) and exec `env` instead
# of Playwright, so we can assert exactly what the spec would have seen. We hunt
# the sad paths: creds totally absent (must still skip CLEANLY, never RED), the
# endpoint built from R2_ACCOUNT_ID, and caller-env precedence over Infisical.

setup() {
  REPO_ROOT="$(cd "$(dirname "${BATS_TEST_FILENAME}")/../.." && pwd)"
  SCRIPT="${REPO_ROOT}/e2e/resolve-r2-env.sh"
  STUB_BIN="${BATS_TEST_TMPDIR}/bin"
  mkdir -p "${STUB_BIN}"
  # A fake Infisical: `infisical secrets get <KEY> ...` -> echoes a deterministic
  # value per key. Keys not in the table echo nothing (absent secret).
  cat > "${STUB_BIN}/infisical" <<'EOF'
#!/usr/bin/env bash
# argv: secrets get <KEY> --token ... --plain
key="$3"
case "$key" in
  R2_ACCESS_KEY_ID)     echo "AKIA-from-infisical" ;;
  R2_SECRET_ACCESS_KEY) echo "secret-from-infisical" ;;
  R2_ACCOUNT_ID)        echo "abc123deadbeef" ;;
  *) : ;;
esac
EOF
  chmod +x "${STUB_BIN}/infisical"
  PATH="${STUB_BIN}:${PATH}"
  # Clean slate — individual tests opt in to creds.
  unset TITAN_R2_ENDPOINT TITAN_R2_ACCESS_KEY TITAN_R2_SECRET_KEY TITAN_R2_BUCKET TITAN_R2_REGION
  unset INFISICAL_TOKEN INFISICAL_PROJECT_ID
}

# ── happy: resolve from Infisical, build endpoint from account id ─────────────

@test "resolves TITAN_R2_* from Infisical and builds the endpoint from R2_ACCOUNT_ID" {
  export INFISICAL_TOKEN="tok" INFISICAL_PROJECT_ID="proj"
  run bash "${SCRIPT}" -- env
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '^LAYER2_RIG_AVAILABLE=1$'
  echo "$output" | grep -q '^TITAN_R2_ACCESS_KEY=AKIA-from-infisical$'
  echo "$output" | grep -q '^TITAN_R2_SECRET_KEY=secret-from-infisical$'
  echo "$output" | grep -q '^TITAN_R2_ENDPOINT=https://abc123deadbeef.r2.cloudflarestorage.com$'
  echo "$output" | grep -q '^TITAN_R2_BUCKET=titan-artifacts$'
  echo "$output" | grep -q '^TITAN_R2_REGION=auto$'
}

# ── precedence: caller env wins over Infisical ────────────────────────────────

@test "caller-exported TITAN_R2_* take precedence over Infisical" {
  export INFISICAL_TOKEN="tok" INFISICAL_PROJECT_ID="proj"
  export TITAN_R2_ENDPOINT="https://explicit.example.com"
  export TITAN_R2_ACCESS_KEY="explicit-access"
  export TITAN_R2_SECRET_KEY="explicit-secret"
  run bash "${SCRIPT}" -- env
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '^TITAN_R2_ENDPOINT=https://explicit.example.com$'
  echo "$output" | grep -q '^TITAN_R2_ACCESS_KEY=explicit-access$'
  echo "$output" | grep -q '^TITAN_R2_SECRET_KEY=explicit-secret$'
  # Did NOT fall back to the Infisical value.
  ! echo "$output" | grep -q 'AKIA-from-infisical'
}

@test "honours TITAN_R2_BUCKET / TITAN_R2_REGION overrides" {
  export TITAN_R2_ENDPOINT="https://e" TITAN_R2_ACCESS_KEY="a" TITAN_R2_SECRET_KEY="s"
  export TITAN_R2_BUCKET="other-bucket" TITAN_R2_REGION="us-east-1"
  run bash "${SCRIPT}" -- env
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '^TITAN_R2_BUCKET=other-bucket$'
  echo "$output" | grep -q '^TITAN_R2_REGION=us-east-1$'
}

# ── SKIP-STILL-CLEAN: creds absent must NOT fail (no false RED) ────────────────

@test "no creds + no Infisical token: still exits 0, sets LAYER2 flag, exports NO TITAN_R2_* (spec skips clean)" {
  # No INFISICAL_TOKEN → the Infisical path is skipped entirely.
  run bash "${SCRIPT}" -- env
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '^LAYER2_RIG_AVAILABLE=1$'
  # The spec's r2Env() returns null when these are unset → test.skip(), not fail.
  ! echo "$output" | grep -q '^TITAN_R2_ENDPOINT='
  ! echo "$output" | grep -q '^TITAN_R2_ACCESS_KEY='
  ! echo "$output" | grep -q '^TITAN_R2_SECRET_KEY='
}

@test "Infisical present but secrets empty: exits 0, no TITAN_R2_* exported (skip clean)" {
  export INFISICAL_TOKEN="tok" INFISICAL_PROJECT_ID="proj"
  # Override the stub to return nothing for every key (secrets unseeded).
  cat > "${STUB_BIN}/infisical" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod +x "${STUB_BIN}/infisical"
  run bash "${SCRIPT}" -- env
  [ "$status" -eq 0 ]
  ! echo "$output" | grep -q '^TITAN_R2_ACCESS_KEY='
}

@test "missing infisical CLI is tolerated (skip clean, exit 0)" {
  export INFISICAL_TOKEN="tok" INFISICAL_PROJECT_ID="proj"
  rm -f "${STUB_BIN}/infisical"   # CLI not on PATH
  run bash "${SCRIPT}" -- env
  [ "$status" -eq 0 ]
  ! echo "$output" | grep -q '^TITAN_R2_ACCESS_KEY='
}

# ── --check diagnostics never leak the secret value, run no command ──────────

@test "--check prints lengths only (never the secret) and runs no command" {
  export TITAN_R2_ENDPOINT="https://e" TITAN_R2_ACCESS_KEY="topsecretaccess" TITAN_R2_SECRET_KEY="topsecretsecret"
  run bash "${SCRIPT}" --check -- env SHOULD_NOT_RUN=1
  [ "$status" -eq 0 ]
  # The raw secret never appears in output.
  ! echo "$output" | grep -q 'topsecretaccess'
  ! echo "$output" | grep -q 'topsecretsecret'
  # The trailing command did not run (env would have printed it).
  ! echo "$output" | grep -q 'SHOULD_NOT_RUN'
  # It reports lengths so the operator can sanity-check.
  echo "$output" | grep -qi 'access-key-len='
}

# ── partial creds (endpoint only) still skip clean ────────────────────────────

@test "partial creds (endpoint without keys) export no keys — all-or-nothing, spec skips" {
  export TITAN_R2_ENDPOINT="https://e"   # but no access/secret, no Infisical token
  run bash "${SCRIPT}" -- env
  [ "$status" -eq 0 ]
  # All-or-nothing: with the keys unresolvable the script must not synthesise
  # them. The spec's r2Env() needs endpoint AND access AND secret → it skips.
  ! echo "$output" | grep -q '^TITAN_R2_ACCESS_KEY='
  ! echo "$output" | grep -q '^TITAN_R2_SECRET_KEY='
}

@test "exits non-zero when no command is given after '--'" {
  export TITAN_R2_ENDPOINT="https://e" TITAN_R2_ACCESS_KEY="a" TITAN_R2_SECRET_KEY="s"
  run bash "${SCRIPT}"
  [ "$status" -eq 2 ]
}
