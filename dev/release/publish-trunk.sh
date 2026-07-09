#!/usr/bin/env bash
# publish-trunk.sh — publish head-of-trunk Titan images to the configured
# OCI registry.
#
# Defaults to the in-cluster Titan registry (#958). GHCR is still supported as
# an override for legacy / fallback flows.
#
# Builds + pushes:
#   ${REGISTRY}/${REGISTRY_NS}titan-server:trunk-<sha>  + :latest-trunk
#   ${REGISTRY}/${REGISTRY_NS}titan-ui:trunk-<sha>      + :latest-trunk
#   ${REGISTRY}/${REGISTRY_NS}titan-worker:trunk-<sha>  + :latest-trunk
#
# The k3s rig can pin `image.tag: latest-trunk` (with imagePullPolicy: Always)
# to pull head-of-trunk on every pod restart, or pin a specific trunk-<sha>
# for reproducible deploys.
#
# Idempotency: re-runs at the same SHA are no-ops (docker layer cache hits,
# docker push is a no-op for already-pushed manifests).
#
# Usage:
#   # default — push to the in-cluster registry (#958)
#   REGISTRY_USER=releaseflow REGISTRY_PASSWORD=... \
#       bash dev/release/publish-trunk.sh
#
#   # legacy / fallback — push to GHCR
#   REGISTRY=ghcr.io REGISTRY_NS=hadamrd/ \
#       REGISTRY_USER=hadamrd REGISTRY_PASSWORD=<GHCR_PAT> \
#       bash dev/release/publish-trunk.sh
#
#   # dry-run
#   bash dev/release/publish-trunk.sh --dry-run
#
# Env:
#   REGISTRY            Registry host (default: registry.test.example.com)
#   REGISTRY_NS         Image-namespace prefix (default: ""; for GHCR use "hadamrd/")
#                       — interpolated as ${REGISTRY}/${REGISTRY_NS}titan-*
#   REGISTRY_USER       Username for `docker login` (no default — must be set)
#   REGISTRY_PASSWORD   Password / PAT for `docker login` (no default; sourced
#                       from Infisical as REGISTRY_HTPASSWD's plaintext, or
#                       from GHCR PAT for the legacy path)
#
# Backwards-compat shims:
#   GHCR_USER  → REGISTRY_USER  (deprecated; still honoured)
#   GHCR_TOKEN → REGISTRY_PASSWORD (deprecated; still honoured)
#   If GHCR_* are set but REGISTRY is not, REGISTRY defaults to ghcr.io and
#   REGISTRY_NS to hadamrd/ for a drop-in legacy call.

set -euo pipefail

DRY_RUN=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    *) echo "Unknown arg: $arg" >&2; exit 2 ;;
  esac
done

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

# ── Compat shim — GHCR_USER/GHCR_TOKEN map to REGISTRY_USER/REGISTRY_PASSWORD
# and, if no REGISTRY is set, also flip the defaults to GHCR for a true
# drop-in. Useful while CI configs still reference the old names.
if [[ -n "${GHCR_USER:-}" && -z "${REGISTRY_USER:-}" ]]; then
  REGISTRY_USER="$GHCR_USER"
  : "${REGISTRY:=ghcr.io}"
  : "${REGISTRY_NS:=hadamrd/}"
fi
if [[ -n "${GHCR_TOKEN:-}" && -z "${REGISTRY_PASSWORD:-}" ]]; then
  REGISTRY_PASSWORD="$GHCR_TOKEN"
fi

REGISTRY="${REGISTRY:-registry.test.example.com}"
REGISTRY_NS="${REGISTRY_NS:-}"

SHA="$(git rev-parse --short=12 HEAD)"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
SHA_TAG="trunk-${SHA}"
FLOATING_TAG="latest-trunk"

run() {
  if [[ "$DRY_RUN" == "1" ]]; then
    echo "DRY-RUN: $*"
  else
    "$@"
  fi
}

# ── 1. Sanity ─────────────────────────────────────────────────────────────────
echo "==> Pre-flight"
echo "    registry:  ${REGISTRY}"
echo "    namespace: ${REGISTRY_NS:-<none>}"
echo "    branch:    ${BRANCH}"
echo "    head:      ${SHA}"
echo "    sha tag:   ${SHA_TAG}"
echo "    float tag: ${FLOATING_TAG}"

if [[ "$BRANCH" != "trunk" && "$DRY_RUN" != "1" ]]; then
  echo "ERROR: not on trunk (HEAD = ${BRANCH}). Refuse to publish non-trunk." >&2
  exit 1
fi

if [[ "$DRY_RUN" != "1" ]]; then
  if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "ERROR: working tree dirty — refuse to publish uncommitted state" >&2
    exit 1
  fi
  if [[ -z "${REGISTRY_USER:-}" || -z "${REGISTRY_PASSWORD:-}" ]]; then
    echo "ERROR: REGISTRY_USER and REGISTRY_PASSWORD must be exported" >&2
    echo "       (in-cluster registry: source REGISTRY_HTPASSWD's plaintext" >&2
    echo "        from Infisical — project adaptiq, env staging, key REGISTRY_HTPASSWD)" >&2
    exit 1
  fi
fi

# ── 2. Build the artifacts the Dockerfiles COPY from ─────────────────────────
# Dockerfile.titan-server  COPYs titan-server/build/quarkus-app/...
# Dockerfile.titan-worker  COPYs titan-worker/build/libs/titan-worker-*.jar
# Dockerfile.titan-ui      COPYs titan-ui/dist/...
# Nothing in `docker build` itself compiles Java or runs Vite — those have to
# happen here, or the resulting image bakes in whatever stale outputs were
# last left on disk. This was a silent-failure mode for months: publish-trunk
# would "succeed" while pushing yesterday's binaries (see PR fixing this).
echo "==> Building JVM artifacts (gradle)"
if [[ "$DRY_RUN" == "1" ]]; then
  echo "DRY-RUN: ./gradlew :titan-server:quarkusBuild :titan-worker:shadowJar -x test --console=plain"
else
  rm -f titan-worker/build/libs/titan-worker-*.jar
  ./gradlew :titan-server:quarkusBuild :titan-worker:shadowJar -x test --console=plain
fi

echo "==> Building titan-ui (vite)"
if [[ "$DRY_RUN" == "1" ]]; then
  echo "DRY-RUN: (cd titan-ui && pnpm install --frozen-lockfile && pnpm build)"
else
  (cd titan-ui && pnpm install --frozen-lockfile && pnpm build)
fi

# ── 3. Registry login ─────────────────────────────────────────────────────────
if [[ "$DRY_RUN" == "1" ]]; then
  echo "DRY-RUN: echo \"\$REGISTRY_PASSWORD\" | docker login ${REGISTRY} -u \"\$REGISTRY_USER\" --password-stdin"
else
  echo "==> Logging in to ${REGISTRY} as ${REGISTRY_USER}"
  echo "$REGISTRY_PASSWORD" | docker login "$REGISTRY" -u "$REGISTRY_USER" --password-stdin
fi

# ── 4. Build, tag, push ───────────────────────────────────────────────────────
build_and_push() {
  local component="$1"   # server | ui | worker
  local image="${REGISTRY}/${REGISTRY_NS}titan-${component}"
  local dockerfile="rig/local/Dockerfile.titan-${component}"

  if [[ ! -f "$dockerfile" ]]; then
    echo "ERROR: ${dockerfile} not found" >&2
    exit 1
  fi

  echo "==> ${image}:${SHA_TAG}  (+ :${FLOATING_TAG})"
  # GIT_SHA (full sha) is baked into the server image as provenance (#44 —
  # OCI revision label + /app/TITAN_GIT_SHA) so the stale-jar guard
  # (dev/rig-smoke/check-rig-freshness.sh) can verify a rig built from
  # published images too. Only Dockerfile.titan-server declares the ARG;
  # passing it elsewhere would just emit unconsumed-build-arg warnings.
  local build_args=()
  if [[ "$component" == "server" ]]; then
    build_args+=(--build-arg "GIT_SHA=$(git rev-parse HEAD)")
  fi
  run docker build -f "$dockerfile" \
      "${build_args[@]}" \
      -t "${image}:${SHA_TAG}" \
      -t "${image}:${FLOATING_TAG}" .
  run docker push "${image}:${SHA_TAG}"
  run docker push "${image}:${FLOATING_TAG}"
}

build_and_push server
build_and_push ui
build_and_push worker

echo
if [[ "$DRY_RUN" == "1" ]]; then
  echo "DRY-RUN complete — no images built or pushed."
else
  echo "Published ${REGISTRY}/${REGISTRY_NS}titan-{server,ui,worker}:${SHA_TAG}"
  echo "          ${REGISTRY}/${REGISTRY_NS}titan-{server,ui,worker}:${FLOATING_TAG}"
fi
