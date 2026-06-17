#!/usr/bin/env bash
# resolve-r2-env.sh — resolve the rig's Cloudflare R2 connection into the
# TITAN_R2_* env that e2e spec 54 (54-r2-independent-verify.spec.ts) reads, set
# LAYER2_RIG_AVAILABLE=1, then `exec` the given command (Playwright) with that
# env in place. This is the env-wiring half of #1241.
#
# WHY a wrapper (and not a printed `export` block): the R2 secret never lands in
# a log, a file, or this script's stdout — it lives only in the env of the
# exec'd child process (the same shape as `infisical run -- <cmd>`).
#
# Credential precedence (first non-empty wins), mirroring publish-trunk's
# Infisical sourcing — NO PLAINTEXT IN GIT:
#   1. TITAN_R2_* already exported in the caller's env (e.g. from rig/local/.env
#      or e2e/.env.layer2 the operator sourced by hand).
#   2. Infisical `staging` env (requires the `infisical` CLI + INFISICAL_TOKEN +
#      INFISICAL_PROJECT_ID): R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY,
#      R2_ACCOUNT_ID (→ endpoint https://<account>.r2.cloudflarestorage.com).
#
# SKIP-STILL-CLEAN CONTRACT (acceptance criterion 4 + the spec's skip rules):
# when no creds can be resolved this script does NOT fail — it still sets
# LAYER2_RIG_AVAILABLE=1 and execs Playwright, which then `test.skip()`s spec 54
# cleanly (R2 creds absent → skip, never a false RED). Missing creds are an
# operator message, not an error.
#
# Usage:
#   e2e/resolve-r2-env.sh -- pnpm exec playwright test specs/v3/54-r2-independent-verify.spec.ts
#   # diagnostics (prints lengths only, never values; runs no spec):
#   e2e/resolve-r2-env.sh --check
set -euo pipefail

log() { printf '[layer2-r2] %s\n' "$*" >&2; }

CHECK_ONLY=0
# Split flags from the trailing `-- <cmd...>`.
ARGS=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --check) CHECK_ONLY=1; shift ;;
    --) shift; ARGS=("$@"); break ;;
    *) ARGS+=("$1"); shift ;;
  esac
done

# Start from anything the caller already exported (precedence step 1).
ACCESS="${TITAN_R2_ACCESS_KEY:-}"
SECRET="${TITAN_R2_SECRET_KEY:-}"
ENDPOINT="${TITAN_R2_ENDPOINT:-}"
BUCKET="${TITAN_R2_BUCKET:-titan-artifacts}"
REGION="${TITAN_R2_REGION:-auto}"

# Precedence step 2: fill the gaps from Infisical, best-effort.
resolve_from_infisical() {
  command -v infisical >/dev/null 2>&1 || { log "infisical CLI not on PATH — skipping Infisical lookup."; return 1; }
  if [ -z "${INFISICAL_TOKEN:-}" ] || [ -z "${INFISICAL_PROJECT_ID:-}" ]; then
    log "INFISICAL_TOKEN / INFISICAL_PROJECT_ID unset — skipping Infisical lookup."
    return 1
  fi
  local ienv ipath
  ienv="${INFISICAL_ENV:-staging}"
  ipath="${INFISICAL_SECRET_PATH:-/}"
  iget() {
    infisical secrets get "$1" \
      --token "$INFISICAL_TOKEN" \
      --projectId "$INFISICAL_PROJECT_ID" \
      --env "$ienv" \
      --path "$ipath" \
      --plain 2>/dev/null || true
  }
  log "resolving R2 creds from Infisical env '$ienv'"
  [ -n "$ACCESS" ] || ACCESS="$(iget R2_ACCESS_KEY_ID)"
  [ -n "$SECRET" ] || SECRET="$(iget R2_SECRET_ACCESS_KEY)"
  if [ -z "$ENDPOINT" ]; then
    local acct
    acct="$(iget R2_ACCOUNT_ID)"
    [ -n "$acct" ] && ENDPOINT="https://${acct}.r2.cloudflarestorage.com"
  fi
  return 0
}

if [ -z "$ACCESS" ] || [ -z "$SECRET" ] || [ -z "$ENDPOINT" ]; then
  resolve_from_infisical || true
fi

# LAYER2_RIG_AVAILABLE is the opt-in switch — always set (the spec still
# skips cleanly on absent creds). Without it the spec skips on the local-dev
# posture branch and we'd never reach the R2 assertions.
export LAYER2_RIG_AVAILABLE=1

if [ -n "$ACCESS" ] && [ -n "$SECRET" ] && [ -n "$ENDPOINT" ]; then
  export TITAN_R2_ENDPOINT="$ENDPOINT"
  export TITAN_R2_ACCESS_KEY="$ACCESS"
  export TITAN_R2_SECRET_KEY="$SECRET"
  export TITAN_R2_BUCKET="$BUCKET"
  export TITAN_R2_REGION="$REGION"
  log "R2 resolved — spec 54 will RUN: endpoint=$ENDPOINT bucket=$BUCKET region=$REGION access-key-len=${#ACCESS} secret-key-len=${#SECRET}"
else
  log "R2 creds NOT resolved (need TITAN_R2_ENDPOINT/ACCESS_KEY/SECRET_KEY in env, or R2_ACCESS_KEY_ID/R2_SECRET_ACCESS_KEY/R2_ACCOUNT_ID in Infisical)."
  log "spec 54 will SKIP cleanly (no false RED). See e2e/.env.layer2.example."
fi

if [ "$CHECK_ONLY" -eq 1 ]; then
  exit 0
fi

if [ "${#ARGS[@]}" -eq 0 ]; then
  log "no command after '--' to exec; nothing to run."
  exit 2
fi

exec "${ARGS[@]}"
