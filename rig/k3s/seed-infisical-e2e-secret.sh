#!/usr/bin/env bash
# seed-infisical-e2e-secret.sh — idempotently pre-seed the ONE Infisical secret
# that e2e spec 46 (46-infisical-secret.spec.ts) binds, so the spec can flip
# skip→green against a rig running TITAN_SECRETS_BACKEND=infisical.
#
# WHAT it seeds: a single secret in the Infisical `staging` env whose NAME equals
# the `key` part of the `scope/key` credential id the e2e binds. The spec binds
# `titan-e2e/E2E_INFISICAL_SECRET`, so the seeded secret name is
# `E2E_INFISICAL_SECRET`. The `scope` (`titan-e2e`) is Titan-internal addressing
# and is NOT part of the Infisical secret name.
#
# NO PLAINTEXT IN GIT: the value comes from $E2E_INFISICAL_VALUE (or is generated
# fresh if absent). This script never hard-codes a secret. `infisical secrets
# set` is an upsert, so re-running is idempotent (same name → value replaced).
#
# Usage:
#   export INFISICAL_TOKEN=...            # staging-scoped svc/machine token
#   export INFISICAL_PROJECT_ID=...       # the workspace id
#   export E2E_INFISICAL_CRED=titan-e2e/E2E_INFISICAL_SECRET   # scope/key
#   export E2E_INFISICAL_VALUE=...        # optional; generated if unset
#   rig/k3s/seed-infisical-e2e-secret.sh
#
# On success it prints the resolved coordinates (never the value) so the operator
# can export the SAME E2E_INFISICAL_CRED / E2E_INFISICAL_VALUE to `task e2e:infisical`.
set -euo pipefail

log() { printf '[seed-infisical-e2e] %s\n' "$*" >&2; }
die() { printf '[seed-infisical-e2e] ERROR: %s\n' "$*" >&2; exit 1; }

CRED="${E2E_INFISICAL_CRED:-titan-e2e/E2E_INFISICAL_SECRET}"
ENV="${INFISICAL_ENV:-staging}"
SECRET_PATH="${INFISICAL_SECRET_PATH:-/}"

# The credential id MUST be a scope/key (the spec asserts it contains '/').
case "$CRED" in
  */*) : ;;
  *)   die "E2E_INFISICAL_CRED ('$CRED') must be a scope/key id — it must contain '/'." ;;
esac
KEY="${CRED##*/}"     # the part after the last '/' — the Infisical secret name
SCOPE="${CRED%/*}"
[[ -n "$KEY" ]]   || die "E2E_INFISICAL_CRED ('$CRED') has an empty key part after '/'."
[[ -n "$SCOPE" ]] || die "E2E_INFISICAL_CRED ('$CRED') has an empty scope part before '/'."

command -v infisical >/dev/null 2>&1 \
  || die "the 'infisical' CLI is not on PATH — install it (https://infisical.com/docs/cli/overview)."
[[ -n "${INFISICAL_TOKEN:-}" ]] \
  || die "INFISICAL_TOKEN is not set — export the staging-scoped service token first."
[[ -n "${INFISICAL_PROJECT_ID:-}" ]] \
  || die "INFISICAL_PROJECT_ID is not set — export the Infisical workspace id first."

# Value: use the caller's known plaintext, else mint a fresh random one so the
# secret is never empty and never a committed literal.
VALUE="${E2E_INFISICAL_VALUE:-}"
if [[ -z "$VALUE" ]]; then
  VALUE="e2e-$(openssl rand -hex 16)"
  log "E2E_INFISICAL_VALUE not provided — generated a fresh ${#VALUE}-char value."
fi

log "upserting Infisical secret '$KEY' (env=$ENV path=$SECRET_PATH project=$INFISICAL_PROJECT_ID)"
# `secrets set` is an upsert → idempotent. --token authenticates non-interactively.
infisical secrets set "$KEY=$VALUE" \
  --token "$INFISICAL_TOKEN" \
  --projectId "$INFISICAL_PROJECT_ID" \
  --env "$ENV" \
  --path "$SECRET_PATH" >/dev/null

log "done. Secret '$KEY' is present in env '$ENV'. Export these for task e2e:infisical:"
# Print to STDOUT (machine-consumable) — value length only, never the value.
printf 'E2E_INFISICAL_CRED=%s\n' "$CRED"
log "E2E_INFISICAL_VALUE is set (len=${#VALUE}); pass it (or let task e2e:infisical re-read it from Infisical)."
