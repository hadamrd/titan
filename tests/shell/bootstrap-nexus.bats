#!/usr/bin/env bats
# Adversarial unit tests for rig/local/bootstrap-nexus.sh (#1241).
#
# The script idempotently creates the raw `titan-artifacts` repo on the local
# rig's Nexus after waiting for it to come up. These tests cover the pure parts
# (write-policy enum mapping + create payload) via --print-payload, and the
# network flow via a scriptable `curl` stub on PATH (no live Nexus). We hunt the
# sad paths: an unknown write policy, Nexus never ready, a repo that already
# exists (idempotent, no second create), and a failed create.

setup() {
  REPO_ROOT="$(cd "$(dirname "${BATS_TEST_FILENAME}")/../.." && pwd)"
  SCRIPT="${REPO_ROOT}/rig/local/bootstrap-nexus.sh"
  STUB_BIN="${BATS_TEST_TMPDIR}/bin"
  POST_LOG="${BATS_TEST_TMPDIR}/post.log"
  mkdir -p "${STUB_BIN}"
  # Scriptable curl: branch on argv; behaviour driven by STUB_* env.
  #   STUB_STATUS_READY (default 1)  -> /status exit 0 (ready) vs non-zero
  #   STUB_REPO_LIST    (default [])  -> body of GET /repositories
  #   STUB_POST_CODE    (default 201) -> http_code echoed by the create POST
  cat > "${STUB_BIN}/curl" <<EOF
#!/usr/bin/env bash
args="\$*"
if [[ "\$args" == *"/service/rest/v1/status"* ]]; then
  [ "\${STUB_STATUS_READY:-1}" = "1" ] && exit 0 || exit 7
fi
if [[ "\$args" == *"-X POST"* ]]; then
  printf '%s\n' "POST \$args" >> "${POST_LOG}"
  printf '%s' "\${STUB_POST_CODE:-201}"
  exit 0
fi
if [[ "\$args" == *"/service/rest/v1/repositories"* ]]; then
  printf '%s' "\${STUB_REPO_LIST:-[]}"
  exit 0
fi
exit 0
EOF
  chmod +x "${STUB_BIN}/curl"
  PATH="${STUB_BIN}:${PATH}"
  # Keep the not-ready test fast.
  export NEXUS_WAIT_SECONDS=2
  export NEXUS_REPOSITORY="titan-artifacts"
}

# ── pure: write-policy enum mapping (no network) ──────────────────────────────

@test "--print-payload maps allow -> ALLOW with the expected JSON body" {
  export NEXUS_WRITE_POLICY="allow"
  run bash "${SCRIPT}" --print-payload
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '^WRITE_ENUM=ALLOW$'
  echo "$output" | grep -q '"name":"titan-artifacts"'
  echo "$output" | grep -q '"writePolicy":"ALLOW"'
  echo "$output" | grep -q '"strictContentTypeValidation":false'
}

@test "--print-payload maps allow_once -> ALLOW_ONCE (case-insensitive)" {
  export NEXUS_WRITE_POLICY="Allow_Once"
  run bash "${SCRIPT}" --print-payload
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '^WRITE_ENUM=ALLOW_ONCE$'
}

@test "--print-payload maps deny -> DENY" {
  export NEXUS_WRITE_POLICY="deny"
  run bash "${SCRIPT}" --print-payload
  [ "$status" -eq 0 ]
  echo "$output" | grep -q '^WRITE_ENUM=DENY$'
}

# ── adversarial: an unknown write policy must fail LOUD (no silent ALLOW) ──────

@test "rejects an unknown NEXUS_WRITE_POLICY (fails loud, no default ALLOW)" {
  export NEXUS_WRITE_POLICY="readonly-typo"
  run bash "${SCRIPT}" --print-payload
  [ "$status" -ne 0 ]
  echo "$output" | grep -qi "not one of allow|allow_once|deny"
}

# ── network: happy create ─────────────────────────────────────────────────────

@test "creates the repo when Nexus is ready and the repo is absent" {
  export STUB_STATUS_READY=1 STUB_REPO_LIST='[]' STUB_POST_CODE=201
  run bash "${SCRIPT}"
  [ "$status" -eq 0 ]
  echo "$output" | grep -qi "created repo 'titan-artifacts'"
  # A POST actually happened.
  [ -f "${POST_LOG}" ]
  grep -q 'raw/hosted' "${POST_LOG}"
}

# ── idempotent: repo already present -> no POST ───────────────────────────────

@test "is idempotent — existing repo means no create POST" {
  export STUB_STATUS_READY=1
  export STUB_REPO_LIST='[{"name":"maven-central"},{"name":"titan-artifacts"},{"name":"npm"}]'
  run bash "${SCRIPT}"
  [ "$status" -eq 0 ]
  echo "$output" | grep -qi "already exists"
  # No create POST was issued.
  [ ! -f "${POST_LOG}" ]
}

# ── adversarial: Nexus never comes up -> fail loud ────────────────────────────

@test "fails loud when Nexus never becomes ready" {
  export STUB_STATUS_READY=0
  run bash "${SCRIPT}"
  [ "$status" -ne 0 ]
  echo "$output" | grep -qi "did not become ready"
}

# ── adversarial: create returns a hard error -> fail loud ─────────────────────

@test "fails loud when the create POST returns an unexpected status" {
  export STUB_STATUS_READY=1 STUB_REPO_LIST='[]' STUB_POST_CODE=500
  run bash "${SCRIPT}"
  [ "$status" -ne 0 ]
  echo "$output" | grep -qi "unexpected HTTP 500"
}
