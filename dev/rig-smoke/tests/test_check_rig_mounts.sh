#!/usr/bin/env bash
# Adversarial unit test for dev/rig-smoke/check-rig-mounts.sh (#149).
#
# Acceptance criteria under test (issue #149):
#   - ADVERSARIAL: a bind mount pointing at an EMPTY temp dir outside the
#     checkout (the #147 compose-from-deleted-worktree footgun) → the RIG
#     MOUNT warning appears, stdout is `true`, and the loud line names the
#     container + mount (+ the non-empty checkout counterpart).
#   - ADVERSARIAL: a bind mount whose source no longer exists → flagged.
#   - Normal checkout mount → silent `false`.
#   - System mounts (/var/run/docker.sock etc.) → exempt, silent `false`.
#   - No rig / no docker / explicit skip → quiet `false`, exit 0.
#   - The script NEVER exits non-zero — a suspicious mount is a warning,
#     not a hard fail.
#
# docker is stubbed via RIG_SMOKE_DOCKER_CMD (canned `ps` / `inspect`
# output — the test_check_rig_freshness.sh stub pattern); the checkout root
# is pinned via RIG_SMOKE_CHECKOUT_ROOT to a synthetic tree so no real
# checkout state leaks into the oracle.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SCRIPT="$REPO_ROOT/dev/rig-smoke/check-rig-mounts.sh"

if [ ! -x "$SCRIPT" ]; then
  echo "FAIL: $SCRIPT not executable" >&2
  exit 1
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# Synthetic checkout: a non-empty e2e/pipelines counterpart, like the repo's.
CHECKOUT="$TMP/checkout"
mkdir -p "$CHECKOUT/e2e/pipelines/node-simple"
echo '{"name":"fixture"}' > "$CHECKOUT/e2e/pipelines/node-simple/package.json"

# Foreign deleted-then-recreated worktree dir: EMPTY, outside the checkout —
# exactly what Docker leaves behind after the worktree is deleted (#147).
FOREIGN="$TMP/wt-145/e2e/pipelines"
mkdir -p "$FOREIGN"

# Stub docker: `ps` prints the container list from $TMP/ps.txt, `inspect`
# prints the bind-mount lines (source|destination) from $TMP/mounts-<name>.txt.
cat > "$TMP/docker-stub.sh" <<EOF
#!/usr/bin/env bash
case "\$1" in
  ps)      cat "$TMP/ps.txt" ;;
  inspect) cat "$TMP/mounts-\${!#}.txt" 2>/dev/null || true ;;
  *)       exit 1 ;;
esac
EOF
chmod +x "$TMP/docker-stub.sh"

# Stub docker with NO containers running.
cat > "$TMP/docker-empty.sh" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  ps) exit 0 ;;
  *)  exit 1 ;;
esac
EOF
chmod +x "$TMP/docker-empty.sh"

run_check() { # stdout is the machine result; stderr goes to a file
  RIG_SMOKE_DOCKER_CMD="$TMP/docker-stub.sh" \
    RIG_SMOKE_CHECKOUT_ROOT="$CHECKOUT" \
    bash "$SCRIPT" 2> "$TMP/stderr.txt"
}

echo "local-titan-worker-1" > "$TMP/ps.txt"

# ── Case 1: ADVERSARIAL — foreign EMPTY worktree mount (the #147 footgun) ──
cat > "$TMP/mounts-local-titan-worker-1.txt" <<EOF
$FOREIGN|/titan/fixtures
/var/run/docker.sock|/var/run/docker.sock
EOF
set +e
OUT=$(run_check)
RC=$?
set -e
if [ "$RC" -ne 0 ]; then
  echo "FAIL: mount issue must be a warning (exit 0), got exit $RC" >&2
  exit 1
fi
if [ "$OUT" != "true" ]; then
  echo "FAIL: foreign empty worktree mount should emit 'true', got '$OUT'" >&2
  exit 1
fi
if ! grep -q 'RIG MOUNT ISSUE' "$TMP/stderr.txt"; then
  echo "FAIL: foreign mount did not print the RIG MOUNT ISSUE warning:" >&2
  cat "$TMP/stderr.txt" >&2
  exit 1
fi
# The loud line must NAME the container and the mount.
if ! grep -q 'local-titan-worker-1: /titan/fixtures' "$TMP/stderr.txt"; then
  echo "FAIL: warning does not name container + mount:" >&2
  cat "$TMP/stderr.txt" >&2
  exit 1
fi
# ...and surface the empty-vs-counterpart signature (issue criterion b).
if ! grep -q 'EMPTY but checkout counterpart' "$TMP/stderr.txt"; then
  echo "FAIL: warning does not surface the empty-source-vs-counterpart detail:" >&2
  cat "$TMP/stderr.txt" >&2
  exit 1
fi
echo "ok: ADVERSARIAL — empty foreign worktree mount → rigMountIssue=true + loud warning naming container+mount"

# ── Case 2: ADVERSARIAL — mount source deleted entirely → flagged ──────────
cat > "$TMP/mounts-local-titan-worker-1.txt" <<EOF
$TMP/wt-999/e2e/pipelines|/titan/fixtures
EOF
OUT=$(run_check)
if [ "$OUT" != "true" ] || ! grep -q 'source MISSING on host' "$TMP/stderr.txt"; then
  echo "FAIL: missing mount source must be flagged; got '$OUT':" >&2
  cat "$TMP/stderr.txt" >&2
  exit 1
fi
echo "ok: ADVERSARIAL — missing mount source → flagged with 'source MISSING on host'"

# ── Case 3: normal checkout mount (+ system mounts) → silent false ─────────
cat > "$TMP/mounts-local-titan-worker-1.txt" <<EOF
$CHECKOUT/e2e/pipelines|/titan/fixtures
/var/run/docker.sock|/var/run/docker.sock
/var/lib/docker/volumes/local_titan-ws/_data|/titan
EOF
OUT=$(run_check)
if [ "$OUT" != "false" ]; then
  echo "FAIL: healthy checkout mount should emit 'false', got '$OUT'" >&2
  exit 1
fi
if grep -q 'RIG MOUNT ISSUE' "$TMP/stderr.txt"; then
  echo "FAIL: healthy mounts must NOT warn:" >&2
  cat "$TMP/stderr.txt" >&2
  exit 1
fi
echo "ok: normal checkout mount + system mounts → rigMountIssue=false, no warning"

# ── Case 4: NON-empty foreign checkout → still flagged (criterion c) ───────
mkdir -p "$TMP/other-checkout/e2e/pipelines"
echo x > "$TMP/other-checkout/e2e/pipelines/marker.txt"
cat > "$TMP/mounts-local-titan-worker-1.txt" <<EOF
$TMP/other-checkout/e2e/pipelines|/titan/fixtures
EOF
OUT=$(run_check)
if [ "$OUT" != "true" ] || ! grep -q 'OUTSIDE this checkout' "$TMP/stderr.txt"; then
  echo "FAIL: non-empty foreign mount must still be flagged as outside-checkout; got '$OUT'" >&2
  cat "$TMP/stderr.txt" >&2
  exit 1
fi
echo "ok: foreign (non-empty) checkout mount → flagged OUTSIDE this checkout"

# ── Case 5: multiple containers — issue in the SECOND one still caught ─────
printf 'local-titan-server-1\nlocal-titan-worker-1\n' > "$TMP/ps.txt"
cat > "$TMP/mounts-local-titan-server-1.txt" <<EOF
$CHECKOUT/e2e/pipelines|/titan/fixtures
EOF
cat > "$TMP/mounts-local-titan-worker-1.txt" <<EOF
$FOREIGN|/titan/fixtures
EOF
OUT=$(run_check)
if [ "$OUT" != "true" ] || ! grep -q 'local-titan-worker-1' "$TMP/stderr.txt"; then
  echo "FAIL: issue in second container not caught; got '$OUT'" >&2
  cat "$TMP/stderr.txt" >&2
  exit 1
fi
echo "ok: multi-container scan — issue in second container caught + named"
echo "local-titan-worker-1" > "$TMP/ps.txt"

# ── Case 6: garbage inspect output (no source|dest pipe) → tolerated ───────
# The freshness tests stub `docker inspect` with a bare image sha; this probe
# must not misread that as a broken mount.
echo "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" > "$TMP/mounts-local-titan-worker-1.txt"
OUT=$(run_check)
if [ "$OUT" != "false" ]; then
  echo "FAIL: garbage inspect output should be tolerated as 'false', got '$OUT'" >&2
  exit 1
fi
echo "ok: garbage inspect output (no mount lines) → false, no crash"

# ── Case 7: no titan-* containers → quiet false ─────────────────────────────
OUT=$(RIG_SMOKE_DOCKER_CMD="$TMP/docker-empty.sh" RIG_SMOKE_CHECKOUT_ROOT="$CHECKOUT" \
  bash "$SCRIPT" 2> "$TMP/stderr.txt")
if [ "$OUT" != "false" ] || grep -q 'RIG MOUNT ISSUE' "$TMP/stderr.txt"; then
  echo "FAIL: no running rig should emit 'false' quietly, got '$OUT'" >&2
  exit 1
fi
echo "ok: no titan-* containers → false, no warning"

# ── Case 8: explicit skips → false without touching docker at all ──────────
for KNOB in RIG_SMOKE_SKIP_MOUNTS RIG_SMOKE_SKIP_FRESHNESS; do
  OUT=$(env "$KNOB=1" RIG_SMOKE_DOCKER_CMD="/nonexistent/docker" bash "$SCRIPT" 2>/dev/null)
  if [ "$OUT" != "false" ]; then
    echo "FAIL: $KNOB=1 should emit 'false', got '$OUT'" >&2
    exit 1
  fi
done
echo "ok: RIG_SMOKE_SKIP_MOUNTS=1 / RIG_SMOKE_SKIP_FRESHNESS=1 → false (docker never invoked)"

# ── Case 9: extra allowlist prefix exempts an intentional out-of-tree mount ─
mkdir -p "$TMP/blessed/data"
cat > "$TMP/mounts-local-titan-worker-1.txt" <<EOF
$TMP/blessed/data|/titan/extra
EOF
OUT=$(RIG_SMOKE_DOCKER_CMD="$TMP/docker-stub.sh" RIG_SMOKE_CHECKOUT_ROOT="$CHECKOUT" \
  RIG_SMOKE_MOUNT_ALLOWLIST="$TMP/blessed" bash "$SCRIPT" 2> "$TMP/stderr.txt")
if [ "$OUT" != "false" ]; then
  echo "FAIL: allowlisted out-of-tree mount should emit 'false', got '$OUT'" >&2
  cat "$TMP/stderr.txt" >&2
  exit 1
fi
echo "ok: RIG_SMOKE_MOUNT_ALLOWLIST exempts an intentional out-of-tree mount"

echo ""
echo "PASS: all check-rig-mounts.sh cases (including adversarial #147 empty-worktree guard)"
