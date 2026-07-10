#!/usr/bin/env bash
# check-rig-freshness.sh — stale-jar guard for the local rig (#44).
#
# Why this exists:
#   rig/local/Dockerfile.titan-server does NOT compile — it COPYs the
#   host-built titan-server/build/quarkus-app. Anyone running
#   `docker compose up -d --build titan-server` directly (bypassing
#   `task dev:titan`'s Gradle step) ships whatever stale jar is lying around,
#   with zero warning. This bit the loop live on 2026-07-08: a rig "rebuilt
#   from trunk" served pre-#39 code and burned a 20-minute smoke run on a
#   false regression.
#
#   `task dev:titan` now bakes the checkout HEAD into the image
#   (build-arg GIT_SHA → OCI label org.opencontainers.image.revision +
#   /app/TITAN_GIT_SHA). This script compares that label against the current
#   checkout HEAD and warns LOUDLY on drift. The probe is `docker inspect`
#   on the label — the cheapest reliable oracle: no HTTP round-trip, no
#   engine logic, works even when titan-server is unhealthy.
#
#   #153 extension — the WORKER can be stale while the server is fresh:
#   rig/local/Dockerfile.titan-worker COPYs a pre-built fat jar, so a
#   recompose that rebuilds titan-server from source but cache-hits the
#   worker's stale-jar COPY layer ships an old ENGINE half with a
#   fresh-looking rig. This bit live on 2026-07-09/10: the deployed worker
#   image (built 01:19 UTC) predated the #146 wake-ADVANCE fix while
#   titan-server carried the #148 sha — invisible to the server-only probe,
#   costing +12s poll dead time on the triage canary (issue #153,
#   task_archive-proven).
#
#   #155 refinement — since PR #155 Dockerfile.titan-worker carries the same
#   GIT_SHA provenance label as the server, so the PREFERRED worker oracle is
#   label-vs-HEAD (exact, no false positives). The #154 image-created-time
#   skew heuristic remains ONLY as the fallback for unlabeled worker images
#   (pre-#155 builds, or a direct `docker compose up --build titan-worker`):
#   it false-positives when a byte-identical jar cache-hits (observed
#   2026-07-10: content-correct worker flagged 6.6h stale). Worker cases:
#     1. label present, == HEAD  → fresh, silent.
#     2. label present, != HEAD  → flagged stale (label wins, no skew math).
#     3. label missing/unknown   → fall back to the skew heuristic.
#
# Contract (consumed by run-golden.sh and `task rig:smoke`):
#   - stdout: EXACTLY `true` (server label mismatch / unprovable server
#     provenance / worker label mismatch / unlabeled worker image older than
#     the server image by more than RIG_SMOKE_WORKER_SKEW_MAX_S, while the
#     respective container is running) or `false` (fresh, or no rig to
#     check). This feeds the `rigShaMismatch` telemetry field.
#   - stderr: human-readable log lines, including the loud STALE RIG warning.
#   - exit code: ALWAYS 0. A stale rig is a warning, not a hard fail —
#     intentional drift is legitimate mid-bisect (#44).
#
# Env overrides (for tests — see tests/test_check_rig_freshness.sh):
#   RIG_SMOKE_SKIP_FRESHNESS=1   skip entirely (emit `false`)
#   RIG_SMOKE_DOCKER_CMD         command run instead of `docker`
#   RIG_SMOKE_EXPECTED_SHA       expected sha (default: `git rev-parse HEAD`)
#   RIG_SMOKE_WORKER_SKEW_MAX_S  max tolerated worker-behind-server image-age
#                                skew in seconds (default 3600; #153)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DOCKER="${RIG_SMOKE_DOCKER_CMD:-docker}"
LABEL_KEY="org.opencontainers.image.revision"
WORKER_SKEW_MAX_S="${RIG_SMOKE_WORKER_SKEW_MAX_S:-3600}"

note() { echo "$*" >&2; }

stale_banner() { # <detail lines...>
  note "[rig-smoke] ============================================================"
  note "[rig-smoke] WARNING: STALE RIG — titan-server may not match this checkout"
  while [ $# -gt 0 ]; do
    note "[rig-smoke]   $1"
    shift
  done
  note "[rig-smoke]   Rebuild via 'task dev:titan' to restore provenance."
  note "[rig-smoke]   (warning only — intentional drift mid-bisect is legitimate)"
  note "[rig-smoke] ============================================================"
}

if [ "${RIG_SMOKE_SKIP_FRESHNESS:-0}" = "1" ]; then
  note "[rig-smoke] freshness: skipped (RIG_SMOKE_SKIP_FRESHNESS=1)"
  echo "false"
  exit 0
fi

# shellcheck disable=SC2086  # deliberate word-splitting of the command prefix
if ! command -v ${DOCKER%% *} >/dev/null 2>&1; then
  note "[rig-smoke] freshness: '${DOCKER%% *}' not on PATH — skipping check"
  echo "false"
  exit 0
fi

# shellcheck disable=SC2086
CONTAINER=$($DOCKER ps --filter name=titan-server --format '{{.Names}}' 2>/dev/null | head -n 1 || true)
if [ -z "$CONTAINER" ]; then
  note "[rig-smoke] freshness: no titan-server container running — nothing to check"
  echo "false"
  exit 0
fi

EXPECTED_SHA="${RIG_SMOKE_EXPECTED_SHA:-}"
if [ -z "$EXPECTED_SHA" ]; then
  EXPECTED_SHA=$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || true)
fi
if [ -z "$EXPECTED_SHA" ]; then
  note "[rig-smoke] freshness: cannot resolve checkout HEAD — skipping check"
  echo "false"
  exit 0
fi

# shellcheck disable=SC2086
RUNNING_SHA=$($DOCKER inspect --format "{{ index .Config.Labels \"$LABEL_KEY\" }}" "$CONTAINER" 2>/dev/null || true)

if [ -z "$RUNNING_SHA" ] || [ "$RUNNING_SHA" = "unknown" ] || [ "$RUNNING_SHA" = "<no value>" ]; then
  # No / unknown label = the image was built WITHOUT GIT_SHA — i.e. a direct
  # `docker compose up --build titan-server` (the exact #44 footgun) or an
  # image predating the guard. Unprovable provenance is flagged, not excused.
  stale_banner \
    "container ${CONTAINER} carries no git provenance label (${LABEL_KEY}=${RUNNING_SHA:-<missing>})" \
    "it was built without GIT_SHA — e.g. 'docker compose up --build titan-server' bypassing 'task dev:titan'" \
    "checkout HEAD is ${EXPECTED_SHA}; the running jar could be ANY age"
  echo "true"
  exit 0
fi

if [ "$RUNNING_SHA" != "$EXPECTED_SHA" ]; then
  stale_banner \
    "container ${CONTAINER} image sha : ${RUNNING_SHA}" \
    "checkout HEAD sha           : ${EXPECTED_SHA}"
  echo "true"
  exit 0
fi

note "[rig-smoke] freshness: titan-server image matches checkout HEAD (${EXPECTED_SHA})"

# ── #153/#155: worker freshness check ───────────────────────────────────────
# Preferred oracle (#155): the worker image carries the same GIT_SHA
# provenance label as the server — compare it to HEAD exactly like the
# server check above. Fallback (#153/#154, unlabeled images only): a worker
# image created substantially BEFORE the (just-proven-fresh) server image
# means the pair was not built together — the recompose cache-hit a stale
# jar layer (the exact 2026-07-10 incident: worker 01:19 UTC vs server
# 18:59 UTC, shipping a pre-#146 engine). The skew heuristic false-positives
# on byte-identical jar cache-hits, hence label-first.
# Indeterminate lookups skip quietly — this probe must never invent staleness.
# shellcheck disable=SC2086
WORKER_CONTAINER=$($DOCKER ps --filter name=titan-worker --format '{{.Names}}' 2>/dev/null | head -n 1 || true)
if [ -z "$WORKER_CONTAINER" ]; then
  note "[rig-smoke] freshness: no titan-worker container running — skipping worker check"
  echo "false"
  exit 0
fi

# shellcheck disable=SC2086
WORKER_SHA=$($DOCKER inspect --format "{{ index .Config.Labels \"$LABEL_KEY\" }}" "$WORKER_CONTAINER" 2>/dev/null || true)

if [ -n "$WORKER_SHA" ] && [ "$WORKER_SHA" != "unknown" ] && [ "$WORKER_SHA" != "<no value>" ]; then
  # Case 1/2: labeled worker — the label verdict is final, no skew math.
  if [ "$WORKER_SHA" != "$EXPECTED_SHA" ]; then
    stale_banner \
      "container ${WORKER_CONTAINER} image sha : ${WORKER_SHA}" \
      "checkout HEAD sha           : ${EXPECTED_SHA}" \
      "(#155: the worker image ships a stale ENGINE while titan-server looks fresh)"
    echo "true"
    exit 0
  fi
  note "[rig-smoke] freshness: titan-worker image matches checkout HEAD (${EXPECTED_SHA})"
  echo "false"
  exit 0
fi

# Case 3: unlabeled worker image (pre-#155 build, or a direct `docker compose
# up --build titan-worker`) — fall back to the #153/#154 creation-time skew
# heuristic.
note "[rig-smoke] freshness: titan-worker image carries no provenance label — falling back to image-age skew heuristic (#154)"

SERVER_CREATED=""
WORKER_CREATED=""
# shellcheck disable=SC2086
SERVER_IMG=$($DOCKER inspect --format '{{.Image}}' "$CONTAINER" 2>/dev/null || true)
# shellcheck disable=SC2086
WORKER_IMG=$($DOCKER inspect --format '{{.Image}}' "$WORKER_CONTAINER" 2>/dev/null || true)
if [ -n "$SERVER_IMG" ]; then
  # shellcheck disable=SC2086
  SERVER_CREATED=$($DOCKER inspect --format '{{.Created}}' "$SERVER_IMG" 2>/dev/null || true)
fi
if [ -n "$WORKER_IMG" ]; then
  # shellcheck disable=SC2086
  WORKER_CREATED=$($DOCKER inspect --format '{{.Created}}' "$WORKER_IMG" 2>/dev/null || true)
fi
SERVER_CREATED_S=""
WORKER_CREATED_S=""
if [ -n "$SERVER_CREATED" ]; then
  SERVER_CREATED_S=$(date -d "$SERVER_CREATED" +%s 2>/dev/null || true)
fi
if [ -n "$WORKER_CREATED" ]; then
  WORKER_CREATED_S=$(date -d "$WORKER_CREATED" +%s 2>/dev/null || true)
fi
if [ -z "$SERVER_CREATED_S" ] || [ -z "$WORKER_CREATED_S" ]; then
  note "[rig-smoke] freshness: cannot determine worker/server image ages — skipping worker skew check"
  echo "false"
  exit 0
fi

SKEW_S=$(( SERVER_CREATED_S - WORKER_CREATED_S ))
if [ "$SKEW_S" -gt "$WORKER_SKEW_MAX_S" ]; then
  stale_banner \
    "titan-worker image (${WORKER_CONTAINER}) was built ${SKEW_S}s BEFORE the titan-server image (max tolerated skew ${WORKER_SKEW_MAX_S}s)" \
    "the worker image COPYs a pre-built fat jar and carries no provenance label (pre-#155 build?) — it likely ships a stale ENGINE" \
    "(#153: a pre-#146 wake-less worker ran undetected while the server looked fresh, +12s poll dead time on the triage canary)" \
    "worker image created: ${WORKER_CREATED}" \
    "server image created: ${SERVER_CREATED}"
  echo "true"
  exit 0
fi

note "[rig-smoke] freshness: titan-worker image age within ${WORKER_SKEW_MAX_S}s of titan-server (skew ${SKEW_S}s)"
echo "false"
exit 0
