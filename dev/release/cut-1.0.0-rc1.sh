#!/usr/bin/env bash
# cut-1.0.0-rc1.sh — CTO-only release cut for Titan 1.0.0-rc1.
#
# What this does (in order, fail-fast on any step):
#   1. Sanity-checks we are on the release commit on trunk.
#   2. Tags the merge commit as v1.0.0-rc1 (annotated) and pushes the tag.
#   3. Logs in to ghcr.io with $GHCR_USER + $GHCR_TOKEN
#      (PAT with write:packages scope).
#   4. Builds titan-server, titan-ui, titan-worker images from the
#      Dockerfiles the local rig uses (rig/local/Dockerfile.titan-*).
#   5. Tags each image as both :v1.0.0-rc1 and :latest-rc, pushes both.
#
# Note: this is a *release candidate*. It publishes the :latest-rc moving
# tag rather than :latest so the rc1 images cannot be pulled by accident
# by clients that follow :latest (which still points to 0.1.0). The final
# 1.0.0 cut (a future cut-1.0.0.sh) is what moves :latest forward.
#
# Idempotency: re-running after a partial failure is safe — git tag uses
# -f only when RETAG=1 is set; docker build re-uses layer cache; docker
# push is a no-op for already-pushed manifests.
#
# Dry-run: pass --dry-run (or set DRY_RUN=1) to print every mutating
# command without executing it. Sanity checks (git status, version pin,
# env vars, dockerfile existence) still run.
#
# Pre-flight (CTO does this BEFORE running the script):
#   - git checkout trunk && git pull --ff-only origin trunk
#   - task verify  # full Gradle check across all product modules
#   - export GHCR_USER=hadamrd
#   - export GHCR_TOKEN=ghp_<PAT-with-write:packages>
#
# Not run by any agent — this is a manual CTO step.

set -euo pipefail

VERSION="1.0.0-rc1"
TAG="v${VERSION}"
MOVING_TAG="latest-rc"
GHCR_NS="ghcr.io/hadamrd"
REPO_ROOT="$(git rev-parse --show-toplevel)"

DRY_RUN="${DRY_RUN:-0}"
if [[ "${1:-}" == "--dry-run" ]]; then
  DRY_RUN=1
fi

# run "$@" — execute (or, in dry-run mode, print prefixed with "[dry-run]").
run() {
  if [[ "$DRY_RUN" == "1" ]]; then
    echo "[dry-run] $*"
  else
    "$@"
  fi
}

cd "$REPO_ROOT"

if [[ "$DRY_RUN" == "1" ]]; then
  echo "==> DRY-RUN MODE — no tags pushed, no images built or pushed"
  echo
fi

# ── 1. Sanity ─────────────────────────────────────────────────────────────────
echo "==> Pre-flight checks"

CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [[ "$CURRENT_BRANCH" != "trunk" ]]; then
  if [[ "$DRY_RUN" == "1" ]]; then
    echo "[dry-run] WARN: not on trunk (HEAD = $CURRENT_BRANCH) — would refuse in live mode"
  else
    echo "ERROR: not on trunk (HEAD = $CURRENT_BRANCH)" >&2
    exit 1
  fi
fi

if ! git diff --quiet || ! git diff --cached --quiet; then
  if [[ "$DRY_RUN" == "1" ]]; then
    echo "[dry-run] WARN: working tree is dirty — would refuse in live mode"
  else
    echo "ERROR: working tree is dirty — commit or stash first" >&2
    exit 1
  fi
fi

PINNED_VERSION="$(grep -E '^version=' gradle.properties | cut -d= -f2)"
if [[ "$PINNED_VERSION" != "$VERSION" ]]; then
  echo "ERROR: gradle.properties version=$PINNED_VERSION, expected $VERSION" >&2
  exit 1
fi

if [[ "$DRY_RUN" != "1" ]]; then
  if [[ -z "${GHCR_USER:-}" || -z "${GHCR_TOKEN:-}" ]]; then
    echo "ERROR: GHCR_USER and GHCR_TOKEN must be exported" >&2
    exit 1
  fi
fi

# ── 2. Tag + push ─────────────────────────────────────────────────────────────
echo "==> Tagging ${TAG} on $(git rev-parse --short HEAD)"

TAG_ARGS=(-a "$TAG" -m "Release ${VERSION}

First release candidate for Titan 1.0. See CHANGELOG.md and
docs/release/1.0.0-rc1.md for the full set of changes since 0.1.0,
upgrade notes, and known-broken follow-ups.")

if [[ "${RETAG:-0}" == "1" ]]; then
  run git tag -f "${TAG_ARGS[@]}"
  run git push --force origin "$TAG"
else
  run git tag "${TAG_ARGS[@]}"
  run git push origin "$TAG"
fi

# ── 3. GHCR login ─────────────────────────────────────────────────────────────
echo "==> Logging in to ghcr.io as ${GHCR_USER:-<unset-in-dry-run>}"
if [[ "$DRY_RUN" == "1" ]]; then
  echo "[dry-run] echo \$GHCR_TOKEN | docker login ghcr.io -u \$GHCR_USER --password-stdin"
else
  echo "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USER" --password-stdin
fi

# ── 4 + 5. Build, tag, push ───────────────────────────────────────────────────
build_and_push() {
  local component="$1"   # server | ui | worker
  local image="${GHCR_NS}/titan-${component}"
  local dockerfile="rig/local/Dockerfile.titan-${component}"

  if [[ ! -f "$dockerfile" ]]; then
    echo "ERROR: ${dockerfile} not found" >&2
    exit 1
  fi

  echo "==> Building ${image}:${TAG}"
  run docker build -f "$dockerfile" -t "${image}:${TAG}" -t "${image}:${MOVING_TAG}" .

  echo "==> Pushing ${image}:${TAG}"
  run docker push "${image}:${TAG}"

  echo "==> Pushing ${image}:${MOVING_TAG}"
  run docker push "${image}:${MOVING_TAG}"
}

build_and_push server
build_and_push ui
build_and_push worker

echo
if [[ "$DRY_RUN" == "1" ]]; then
  echo "✓ DRY-RUN complete for ${TAG} — no side effects."
else
  echo "✓ Released ${TAG}"
fi
echo "  Tag:                  origin ${TAG}"
echo "  Images:               ${GHCR_NS}/titan-{server,ui,worker}:${TAG}"
echo "                        ${GHCR_NS}/titan-{server,ui,worker}:${MOVING_TAG}"
echo
echo "Next: bump rig/k3s/helm/titan/values.yaml image tags to ${TAG} and"
echo "      run 'task deploy:k3s' to roll the public rig with rc1."
