#!/usr/bin/env bash
# cut-0.1.0.sh — CTO-only release cut for Titan 0.1.0.
#
# What this does (in order, fail-fast on any step):
#   1. Sanity-checks we are on the release commit on trunk.
#   2. Tags the merge commit as v0.1.0 (annotated) and pushes the tag.
#   3. Logs in to ghcr.io with $GHCR_USER + $GHCR_TOKEN
#      (PAT with write:packages scope).
#   4. Builds titan-server, titan-ui, titan-worker images from the
#      Dockerfiles the local rig uses (rig/local/Dockerfile.titan-*).
#   5. Tags each image as both :v0.1.0 and :latest, pushes both.
#
# Idempotency: re-running after a partial failure is safe — git tag uses
# -f only when RETAG=1 is set; docker build re-uses layer cache; docker
# push is a no-op for already-pushed manifests.
#
# Pre-flight (CTO does this BEFORE running the script):
#   - git checkout trunk && git pull --ff-only origin trunk
#   - task verify  # full Gradle check across all product modules
#   - export GHCR_USER=hadamrd
#   - export GHCR_TOKEN=ghp_<PAT-with-write:packages>
#
# Not run by any agent — this is a manual CTO step.

set -euo pipefail

VERSION="0.1.0"
TAG="v${VERSION}"
GHCR_NS="ghcr.io/hadamrd"
REPO_ROOT="$(git rev-parse --show-toplevel)"

cd "$REPO_ROOT"

# ── 1. Sanity ─────────────────────────────────────────────────────────────────
echo "==> Pre-flight checks"

if [[ "$(git rev-parse --abbrev-ref HEAD)" != "trunk" ]]; then
  echo "ERROR: not on trunk (HEAD = $(git rev-parse --abbrev-ref HEAD))" >&2
  exit 1
fi

if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "ERROR: working tree is dirty — commit or stash first" >&2
  exit 1
fi

PINNED_VERSION="$(grep -E '^version=' gradle.properties | cut -d= -f2)"
if [[ "$PINNED_VERSION" != "$VERSION" ]]; then
  echo "ERROR: gradle.properties version=$PINNED_VERSION, expected $VERSION" >&2
  exit 1
fi

if [[ -z "${GHCR_USER:-}" || -z "${GHCR_TOKEN:-}" ]]; then
  echo "ERROR: GHCR_USER and GHCR_TOKEN must be exported" >&2
  exit 1
fi

# ── 2. Tag + push ─────────────────────────────────────────────────────────────
echo "==> Tagging ${TAG} on $(git rev-parse --short HEAD)"

TAG_ARGS=(-a "$TAG" -m "Release ${VERSION}

First public release of Titan as a standalone CI/CD control plane.
See CHANGELOG.md for the full list of shipped features and fixes.")

if [[ "${RETAG:-0}" == "1" ]]; then
  git tag -f "${TAG_ARGS[@]}"
  git push --force origin "$TAG"
else
  git tag "${TAG_ARGS[@]}"
  git push origin "$TAG"
fi

# ── 3. GHCR login ─────────────────────────────────────────────────────────────
echo "==> Logging in to ghcr.io as ${GHCR_USER}"
echo "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USER" --password-stdin

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
  docker build -f "$dockerfile" -t "${image}:${TAG}" -t "${image}:latest" .

  echo "==> Pushing ${image}:${TAG}"
  docker push "${image}:${TAG}"

  echo "==> Pushing ${image}:latest"
  docker push "${image}:latest"
}

build_and_push server
build_and_push ui
build_and_push worker

echo
echo "✓ Released ${TAG}"
echo "  Tag pushed:           origin ${TAG}"
echo "  Images published:     ${GHCR_NS}/titan-{server,ui,worker}:${TAG}"
echo "                        ${GHCR_NS}/titan-{server,ui,worker}:latest"
echo
echo "Next: bump rig/k3s/helm/titan/values.yaml image tags to ${TAG} and"
echo "      run 'task deploy:k3s' to roll the public rig."
