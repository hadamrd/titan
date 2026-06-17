#!/usr/bin/env bash
# bootstrap-nexus.sh — idempotently provision the raw hosted repo that the rig
# worker (booted with TITAN_ARTIFACT_STORE=nexus) publishes archived artifacts
# into, after waiting for Nexus to come up. The Nexus half of #1241 / spec 49
# (49-nexus-publish.spec.ts).
#
# Nexus 3 has no declarative repo seeding, so this is the one-time REST bootstrap
# the docker-compose.nexus.yml header documents — made idempotent + repeatable
# here so `task e2e:nexus` can call it every run safely.
#
# Credentials/endpoint are env-sourced (mirroring how R2 creds are handled — no
# plaintext baked in); the defaults are the dev-only Nexus admin (admin/admin123,
# pinned by NEXUS_SECURITY_RANDOMPASSWORD=false in the compose file).
#
#   NEXUS_URL          default http://localhost:8085
#   NEXUS_USERNAME     default admin
#   NEXUS_PASSWORD     default admin123
#   NEXUS_REPOSITORY   default titan-artifacts   (the raw hosted repo name)
#   NEXUS_WRITE_POLICY default allow             (allow | allow_once | deny)
#   NEXUS_WAIT_SECONDS default 180               (Nexus first-boot can be slow)
#
# Flags:
#   --print-payload   print the resolved write-policy enum + the create JSON
#                     body and exit 0 (no network) — used by the bats unit tests.
set -euo pipefail

log() { printf '[bootstrap-nexus] %s\n' "$*" >&2; }
die() { printf '[bootstrap-nexus] ERROR: %s\n' "$*" >&2; exit 1; }

NEXUS_URL="${NEXUS_URL:-http://localhost:8085}"
NEXUS_URL="${NEXUS_URL%/}"
NEXUS_USERNAME="${NEXUS_USERNAME:-admin}"
NEXUS_PASSWORD="${NEXUS_PASSWORD:-admin123}"
NEXUS_REPOSITORY="${NEXUS_REPOSITORY:-titan-artifacts}"
NEXUS_WRITE_POLICY="${NEXUS_WRITE_POLICY:-allow}"
NEXUS_WAIT_SECONDS="${NEXUS_WAIT_SECONDS:-180}"

# Map the lower-case e2e policy name (the same vocabulary spec 49 reads) onto
# Nexus's upper-case writePolicy enum. An unknown value is a hard error, not a
# silent fallback — a typo'd policy must fail LOUD, not quietly publish ALLOW.
nexus_write_enum() {
  case "$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" in
    allow) printf 'ALLOW' ;;
    allow_once) printf 'ALLOW_ONCE' ;;
    deny) printf 'DENY' ;;
    *) return 1 ;;
  esac
}

repo_payload() {
  local name="$1" enum="$2"
  printf '{"name":"%s","online":true,"storage":{"blobStoreName":"default","strictContentTypeValidation":false,"writePolicy":"%s"}}' \
    "$name" "$enum"
}

WRITE_ENUM="$(nexus_write_enum "$NEXUS_WRITE_POLICY")" \
  || die "NEXUS_WRITE_POLICY='$NEXUS_WRITE_POLICY' is not one of allow|allow_once|deny."
PAYLOAD="$(repo_payload "$NEXUS_REPOSITORY" "$WRITE_ENUM")"

# ── dry mode for unit tests (no network) ──────────────────────────────────────
if [ "${1:-}" = "--print-payload" ]; then
  printf 'WRITE_ENUM=%s\n' "$WRITE_ENUM"
  printf 'PAYLOAD=%s\n' "$PAYLOAD"
  exit 0
fi

# ── wait for Nexus to answer its status endpoint ──────────────────────────────
log "waiting up to ${NEXUS_WAIT_SECONDS}s for Nexus at ${NEXUS_URL} ..."
ready=0
for _ in $(seq 1 "$NEXUS_WAIT_SECONDS"); do
  if curl -fsS --max-time 5 -o /dev/null "${NEXUS_URL}/service/rest/v1/status"; then
    ready=1
    break
  fi
  sleep 1
done
[ "$ready" -eq 1 ] || die "Nexus did not become ready at ${NEXUS_URL} within ${NEXUS_WAIT_SECONDS}s (is the compose up?)."
log "Nexus is up."

# ── idempotent repo create ────────────────────────────────────────────────────
# List all repos and check by name. The list endpoint is stable across Nexus 3
# minors; per-repo GET is not, so we grep the list (closes #1241 idempotency).
existing="$(curl -fsS --max-time 10 -u "${NEXUS_USERNAME}:${NEXUS_PASSWORD}" \
  "${NEXUS_URL}/service/rest/v1/repositories" 2>/dev/null || true)"
if printf '%s' "$existing" | grep -q "\"name\"[[:space:]]*:[[:space:]]*\"${NEXUS_REPOSITORY}\""; then
  log "repo '${NEXUS_REPOSITORY}' already exists — nothing to do (idempotent)."
  exit 0
fi

log "creating raw hosted repo '${NEXUS_REPOSITORY}' (writePolicy=${WRITE_ENUM}) ..."
status="$(curl -sS --max-time 15 -o /dev/null -w '%{http_code}' \
  -u "${NEXUS_USERNAME}:${NEXUS_PASSWORD}" \
  -X POST -H 'Content-Type: application/json' \
  "${NEXUS_URL}/service/rest/v1/repositories/raw/hosted" \
  -d "${PAYLOAD}")"
# Nexus returns 201 Created on success. A late 4xx that means "already exists"
# is benign (idempotent re-run racing another bootstrap).
case "$status" in
  201) log "created repo '${NEXUS_REPOSITORY}'." ;;
  400 | 409)
    if printf '%s' "$(curl -fsS --max-time 10 -u "${NEXUS_USERNAME}:${NEXUS_PASSWORD}" \
        "${NEXUS_URL}/service/rest/v1/repositories" 2>/dev/null || true)" \
        | grep -q "\"name\"[[:space:]]*:[[:space:]]*\"${NEXUS_REPOSITORY}\""; then
      log "repo '${NEXUS_REPOSITORY}' present after a ${status} — treating as created (idempotent)."
    else
      die "repo create returned HTTP ${status} and the repo is still absent."
    fi
    ;;
  *) die "repo create returned unexpected HTTP ${status}." ;;
esac
