#!/usr/bin/env bash
# check-rig-mounts.sh — dangling/foreign bind-mount guard for the local rig (#149).
#
# Why this exists:
#   Root cause of the false P1 #147: an agent composed the rig from a git
#   WORKTREE (/tmp/wt-145), the worktree was deleted post-merge, and Docker
#   silently recreated the bind-mount source as an EMPTY directory —
#   /titan/fixtures had no package.json, npm ENOENT'd inside the worker, and
#   two smoke runs burned on a phantom engine regression. The #91/#44
#   freshness guard checks the image SHA but not MOUNT integrity, so the rig
#   looked "fresh" while serving hollow fixtures.
#
#   This sibling of check-rig-freshness.sh docker-inspects every bind mount
#   of every running titan-* container and warns LOUDLY when a mount source:
#     (a) does not exist on the host (deleted after compose),
#     (b) is an EMPTY directory while this checkout's counterpart is
#         non-empty (the exact #147 signature: Docker recreated the deleted
#         worktree path as an empty dir on restart),
#     (c) points OUTSIDE the current checkout root (e.g. /tmp/wt-* — the rig
#         was composed from a different/foreign checkout). Known system
#         paths (/var/run, /var/lib/docker, ...) are exempt: the docker
#         socket and volume-backing binds are legitimately out-of-tree.
#
# Contract (mirrors check-rig-freshness.sh; consumed by run-golden.sh and
# `task rig:smoke`):
#   - stdout: EXACTLY `true` (at least one mount issue found) or `false`
#     (clean, or no rig / no docker to check). Feeds the ADDITIVE
#     `rigMountIssue` telemetry field (parse-compat: existing fields keep
#     their exact names/order).
#   - stderr: human-readable log lines, including the loud RIG MOUNT
#     warning naming the container + mount.
#   - exit code: ALWAYS 0. A suspicious mount is a warning, not a hard
#     fail — out-of-tree mounts can be intentional on exotic setups.
#
# Env overrides (for tests — see tests/test_check_rig_mounts.sh):
#   RIG_SMOKE_SKIP_MOUNTS=1      skip this guard (emit `false`)
#   RIG_SMOKE_SKIP_FRESHNESS=1   family-wide skip (same knob run-golden.sh
#                                tests use to stay hermetic — also skips)
#   RIG_SMOKE_DOCKER_CMD         command run instead of `docker`
#   RIG_SMOKE_CHECKOUT_ROOT      checkout root (default: repo root of this
#                                script — dev/rig-smoke/../..)
#   RIG_SMOKE_MOUNT_ALLOWLIST    extra colon-separated source-path prefixes
#                                to exempt from the outside-checkout check
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DOCKER="${RIG_SMOKE_DOCKER_CMD:-docker}"
CHECKOUT_ROOT="${RIG_SMOKE_CHECKOUT_ROOT:-$REPO_ROOT}"

# System prefixes that legitimately live outside any checkout: the docker
# socket, Docker Desktop's /run/desktop translation layer, the named-volume
# backing dirs (TITAN_WORKSPACE_HOST_ROOT → /var/lib/docker/volumes/...).
SYSTEM_ALLOWLIST="/var/run:/run:/var/lib/docker:/dev:/sys:/proc:/etc"
ALLOWLIST="${SYSTEM_ALLOWLIST}${RIG_SMOKE_MOUNT_ALLOWLIST:+:$RIG_SMOKE_MOUNT_ALLOWLIST}"

note() { echo "$*" >&2; }

mount_banner() { # <detail lines...>
  note "[rig-smoke] ============================================================"
  note "[rig-smoke] WARNING: RIG MOUNT ISSUE — bind mounts don't match this checkout"
  while [ $# -gt 0 ]; do
    note "[rig-smoke]   $1"
    shift
  done
  note "[rig-smoke]   A rig composed from a deleted worktree serves EMPTY fixture"
  note "[rig-smoke]   dirs (the #147 phantom P1). Recompose from THIS checkout via"
  note "[rig-smoke]   'task dev:titan' to restore mount integrity."
  note "[rig-smoke]   (warning only — intentional out-of-tree mounts are legitimate)"
  note "[rig-smoke] ============================================================"
}

if [ "${RIG_SMOKE_SKIP_MOUNTS:-0}" = "1" ] || [ "${RIG_SMOKE_SKIP_FRESHNESS:-0}" = "1" ]; then
  note "[rig-smoke] mounts: skipped (RIG_SMOKE_SKIP_MOUNTS/RIG_SMOKE_SKIP_FRESHNESS)"
  echo "false"
  exit 0
fi

# shellcheck disable=SC2086  # deliberate word-splitting of the command prefix
if ! command -v ${DOCKER%% *} >/dev/null 2>&1; then
  note "[rig-smoke] mounts: '${DOCKER%% *}' not on PATH — skipping check"
  echo "false"
  exit 0
fi

# shellcheck disable=SC2086
CONTAINERS=$($DOCKER ps --filter name=titan --format '{{.Names}}' 2>/dev/null || true)
if [ -z "$CONTAINERS" ]; then
  note "[rig-smoke] mounts: no titan-* containers running — nothing to check"
  echo "false"
  exit 0
fi

# True when $1 is the checkout root or lives under it.
inside_checkout() {
  case "$1" in
    "$CHECKOUT_ROOT" | "$CHECKOUT_ROOT"/*) return 0 ;;
    *) return 1 ;;
  esac
}

# True when $1 sits under an allowlisted system prefix.
allowlisted() {
  local prefix rest="$ALLOWLIST"
  while [ -n "$rest" ]; do
    prefix="${rest%%:*}"
    case "$rest" in *:*) rest="${rest#*:}" ;; *) rest="" ;; esac
    [ -z "$prefix" ] && continue
    case "$1" in
      "$prefix" | "$prefix"/*) return 0 ;;
    esac
  done
  return 1
}

# True when $1 is an empty directory (or a directory we cannot list).
empty_dir() {
  [ -d "$1" ] && [ -z "$(ls -A "$1" 2>/dev/null)" ]
}

# Print this checkout's counterpart of a foreign source path, if one exists:
# the longest path suffix of $1 that resolves under $CHECKOUT_ROOT.
# /tmp/wt-145/e2e/pipelines → <checkout>/e2e/pipelines. Empty output = none.
checkout_counterpart() {
  local rel="${1#/}"
  while [ -n "$rel" ]; do
    if [ -e "$CHECKOUT_ROOT/$rel" ]; then
      echo "$CHECKOUT_ROOT/$rel"
      return 0
    fi
    case "$rel" in
      */*) rel="${rel#*/}" ;;
      *) rel="" ;;
    esac
  done
  return 0
}

ISSUES=()

for CONTAINER in $CONTAINERS; do
  # shellcheck disable=SC2086
  MOUNTS=$($DOCKER inspect --format \
    '{{range .Mounts}}{{if eq .Type "bind"}}{{.Source}}|{{.Destination}}{{"\n"}}{{end}}{{end}}' \
    "$CONTAINER" 2>/dev/null || true)
  [ -z "$MOUNTS" ] && continue

  while IFS='|' read -r SRC DST; do
    # Tolerate garbage/blank lines (e.g. a stubbed docker in the freshness
    # tests answers `inspect` with a bare sha — no pipe, no mount).
    [ -z "$SRC" ] || [ -z "${DST:-}" ] && continue

    # System/allowlisted sources are exempt from ALL checks — on Docker
    # Desktop / WSL the daemon resolves them inside its VM, so host-side
    # existence probes would false-positive on perfectly healthy rigs.
    allowlisted "$SRC" && continue

    if [ ! -e "$SRC" ]; then
      # (a) dangling: the source was deleted after the container was created.
      ISSUES+=("${CONTAINER}: ${DST} ← ${SRC} — source MISSING on host (deleted after compose?)")
      continue
    fi

    if ! inside_checkout "$SRC"; then
      # (c) foreign: composed from another checkout/worktree (/tmp/wt-*...).
      DETAIL="${CONTAINER}: ${DST} ← ${SRC} — source is OUTSIDE this checkout (${CHECKOUT_ROOT})"
      COUNTERPART=$(checkout_counterpart "$SRC")
      if [ -n "$COUNTERPART" ] && empty_dir "$SRC" && ! empty_dir "$COUNTERPART"; then
        # (b) the #147 signature: Docker recreated the deleted worktree path
        # as an empty dir while the real checkout counterpart has content.
        DETAIL="$DETAIL; source dir is EMPTY but checkout counterpart ${COUNTERPART} is not"
      fi
      ISSUES+=("$DETAIL")
    fi
  done <<< "$MOUNTS"
done

if [ "${#ISSUES[@]}" -gt 0 ]; then
  mount_banner "${ISSUES[@]}"
  echo "true"
  exit 0
fi

note "[rig-smoke] mounts: all titan-* bind mounts resolve inside this checkout (${CHECKOUT_ROOT})"
echo "false"
exit 0
