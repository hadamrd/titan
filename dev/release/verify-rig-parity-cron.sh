#!/usr/bin/env bash
# verify-rig-parity-cron.sh — scheduled (every-15-min) wrapper around
# verify-rig-parity.sh that posts to Slack (or any incoming-webhook
# endpoint) when the in-cluster bundle is out of parity with the served
# bundle. Closes #1041.
#
# Designed to be installed under cron / k3s CronJob, e.g.:
#   */15 * * * * /opt/titan/dev/release/verify-rig-parity-cron.sh
#
# Env:
#   PARITY_ALERT_WEBHOOK  Slack-compatible incoming-webhook URL. If unset,
#                         the script still runs the check and exits with
#                         the check's status; only the alert is suppressed.
#   PARITY_ALERT_CHANNEL  Optional label included in the alert body.
#   RIG_URL, KUBE_*       Forwarded to verify-rig-parity.sh.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECK="${SCRIPT_DIR}/verify-rig-parity.sh"

if [[ ! -x "${CHECK}" ]]; then
  echo "verify-rig-parity-cron: ERROR — ${CHECK} not found / not executable" >&2
  exit 2
fi

OUT_FILE="$(mktemp)"
trap 'rm -f "${OUT_FILE}"' EXIT

set +e
"${CHECK}" 2>"${OUT_FILE}"
rc=$?
set -e

if [[ ${rc} -eq 0 ]]; then
  exit 0
fi

# Mismatch (or fetch failure). Post to webhook if configured.
if [[ -n "${PARITY_ALERT_WEBHOOK:-}" ]]; then
  body="$(cat "${OUT_FILE}")"
  channel="${PARITY_ALERT_CHANNEL:-titan-rig}"
  payload="$(printf '{"text":":rotating_light: *titan rig bundle split-brain* (%s)\n```%s```"}' \
    "${channel}" "${body//\"/\\\"}")"
  curl -fsS --max-time 10 -H 'Content-Type: application/json' \
    -d "${payload}" "${PARITY_ALERT_WEBHOOK}" >/dev/null || \
    echo "verify-rig-parity-cron: WARN — alert post failed" >&2
fi

cat "${OUT_FILE}" >&2
exit "${rc}"
