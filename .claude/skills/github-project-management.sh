#!/usr/bin/env bash
# Materialize the Release Flow GitHub Project + seed it from
# design/16-execution-plan.md. Idempotent: rerun-safe (creates only
# what's missing).
#
# Prerequisites:
#   gh auth refresh -s project
#
# Usage:
#   bash .claude/skills/github-project-management.sh [OWNER]
# Default OWNER is "hadamrd" (the current repo's owner).

set -euo pipefail
OWNER="${1:-hadamrd}"
TITLE="Release Flow — v1.0 Roadmap"
REPO="${OWNER}/dashboard-plugin"

echo "==> Looking up existing project '$TITLE' under @$OWNER…"
PROJECT_ID=$(gh project list --owner "$OWNER" --format json \
  | jq -r ".projects[] | select(.title==\"$TITLE\") | .number" || true)

if [ -z "$PROJECT_ID" ]; then
  echo "==> Creating project: $TITLE"
  PROJECT_ID=$(gh project create --owner "$OWNER" --title "$TITLE" --format json | jq -r '.number')
  echo "    project number: $PROJECT_ID"
else
  echo "    found project: #$PROJECT_ID"
fi

echo "==> Custom fields…"

ensure_field() {
  local name="$1"
  local data_type="$2"
  local options="$3"  # comma-separated, only for SINGLE_SELECT
  local existing
  existing=$(gh project field-list "$PROJECT_ID" --owner "$OWNER" --format json \
    | jq -r ".fields[] | select(.name==\"$name\") | .id" || true)
  if [ -n "$existing" ]; then
    echo "    field '$name' exists ($existing)"
    return
  fi
  echo "    creating field '$name'"
  if [ "$data_type" = "SINGLE_SELECT" ]; then
    gh project field-create "$PROJECT_ID" --owner "$OWNER" \
      --name "$name" --data-type SINGLE_SELECT \
      --single-select-options "$options" >/dev/null
  else
    gh project field-create "$PROJECT_ID" --owner "$OWNER" \
      --name "$name" --data-type "$data_type" >/dev/null
  fi
}

ensure_field "Phase" SINGLE_SELECT "A,B,C,D,E,F,G,H,I,J,K,L,M"
ensure_field "Sub-phase" TEXT ""
ensure_field "Owning agent" SINGLE_SELECT "PO,Engine,Frontend,PDL,Test,CQC,Layer-2,DevOps,Boot-verifier"
ensure_field "Last update" DATE ""
ensure_field "Notes" TEXT ""
# Note: Status is built-in; don't recreate.

echo "==> Phase issues…"

ensure_issue() {
  local title="$1"
  local body="$2"
  local labels="$3"
  local existing
  existing=$(gh issue list --repo "$REPO" --search "$title in:title" --state all --json number,title \
    | jq -r ".[] | select(.title==\"$title\") | .number" | head -1)
  if [ -n "$existing" ]; then
    echo "    issue '$title' exists (#$existing)" >&2
    printf '%s' "$existing"
    return
  fi
  echo "    creating issue: $title" >&2
  # `gh issue create` returns the issue URL on stdout (no --json support
  # in older gh). Trim the URL to the trailing /N.
  local url
  url=$(gh issue create --repo "$REPO" --title "$title" --body "$body" --label "$labels")
  printf '%s' "${url##*/}"
}

# Phase descriptions distilled from design/16-execution-plan.md.
declare -A PHASE_DESC=(
  ["Phase A — Engine truly DB-backed"]="DAOs + write-through + materializer + apps to DB + boot order. ✅ Done in commits a1efb1f + e3d8ed8."
  ["Phase B — Discovery worker + cd-pipeline.yml parser"]="JSON Schema + parser + DiscoveryWorker + LocalDirectoryDiscoverySource + dev seed. ✅ Done in commit a8b46f9."
  ["Phase C — VersionObserver SPI + impls"]="DockerRegistry + Webhook + MonorepoRelease observers + scheduler + webhook endpoints. SPI shell ✅; impls in flight."
  ["Phase D — CdScheduler + triggers + gates"]="Trigger SPI impls + gate ladder + dedup + CdScheduler PeriodicWork. SPI shell ✅."
  ["Phase E — DeploymentLauncher + state machine"]="Launcher + RunListener + recordDeployment workflow step + state machine."
  ["Phase F — Control plane (andon/freeze/approval)"]="AndonService + FreezeService + ApprovalService + permissions + control-plane Jelly pages."
  ["Phase G — Notifier SPI + impls"]="Slack + Webhook notifiers + dispatcher. SPI shell ✅."
  ["Phase H — UI dashboard pages"]="Apps grid + matrix + drawer + control-plane + discovery errors page."
  ["Phase I — i18n + a11y sweep"]="Every UI string via Messages.properties + ARIA on dynamic regions."
  ["Phase J — Tests + coverage"]="Push to ≥70% line / ≥60% branch."
  ["Phase K — Layer-2 Hetzner test rig"]="Provisioning + dummy SaaS stack + sample shared library + e2e test runner. Prep ✅."
  ["Phase L — Quality gates + CI"]="Spotless / SpotBugs / Error Prone / CodeQL / GH Actions / release-drafter."
  ["Phase M — Hosting + v1.0 release"]="hosting request + repo transfer + v1.0 tag + marketplace."
)

declare -A PHASE_STATUS=(
  ["Phase A — Engine truly DB-backed"]="Done"
  ["Phase B — Discovery worker + cd-pipeline.yml parser"]="Done"
  ["Phase C — VersionObserver SPI + impls"]="Active"
  ["Phase D — CdScheduler + triggers + gates"]="Queued"
  ["Phase E — DeploymentLauncher + state machine"]="Queued"
  ["Phase F — Control plane (andon/freeze/approval)"]="Queued"
  ["Phase G — Notifier SPI + impls"]="Queued"
  ["Phase H — UI dashboard pages"]="Queued"
  ["Phase I — i18n + a11y sweep"]="Queued"
  ["Phase J — Tests + coverage"]="Queued"
  ["Phase K — Layer-2 Hetzner test rig"]="Active"
  ["Phase L — Quality gates + CI"]="Queued"
  ["Phase M — Hosting + v1.0 release"]="Queued"
)

for phase in "${!PHASE_DESC[@]}"; do
  num=$(ensure_issue "$phase" "${PHASE_DESC[$phase]}" "phase,roadmap")
  if [ -n "$num" ]; then
    echo "    adding issue #$num to project"
    gh project item-add "$PROJECT_ID" --owner "$OWNER" \
      --url "https://github.com/$REPO/issues/$num" >/dev/null 2>&1 || true
  fi
done

echo "==> Done. Open https://github.com/users/$OWNER/projects/$PROJECT_ID"
