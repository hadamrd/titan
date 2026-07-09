#!/usr/bin/env bash
# Adversarial unit test for dev/rig-smoke/check-rig-freshness.sh (#44).
#
# Acceptance criterion under test (issue #44):
#   - STALE image (label sha != checkout HEAD) → the STALE RIG flag appears
#     (adversarial: assert the warning IS printed and stdout is `true`).
#   - FRESH image (label sha == HEAD) → the flag does NOT appear.
#   - Image without a provenance label (built via direct `docker compose up
#     --build`, the original footgun) → flagged as stale.
#   - No rig / no docker / explicit skip → quiet `false`, exit 0.
#   - The script NEVER exits non-zero — stale is a warning, not a hard fail
#     (intentional drift mid-bisect is legitimate).
#
# docker is stubbed via RIG_SMOKE_DOCKER_CMD (canned `ps` / `inspect`
# output — same stub pattern as test_run_golden.sh's playwright stub); the
# expected sha is pinned via RIG_SMOKE_EXPECTED_SHA so no git checkout state
# leaks into the oracle.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SCRIPT="$REPO_ROOT/dev/rig-smoke/check-rig-freshness.sh"

if [ ! -x "$SCRIPT" ]; then
  echo "FAIL: $SCRIPT not executable" >&2
  exit 1
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# Stub docker: `ps` prints the container name, `inspect` prints whatever sha
# the case under test wrote to $TMP/label.txt (empty file = no label).
cat > "$TMP/docker-stub.sh" <<EOF
#!/usr/bin/env bash
case "\$1" in
  ps)      echo "local-titan-server-1" ;;
  inspect) cat "$TMP/label.txt" ;;
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

run_check() { # stdout of the script is the machine result; stderr goes to a file
  RIG_SMOKE_DOCKER_CMD="$1" RIG_SMOKE_EXPECTED_SHA="$2" bash "$SCRIPT" 2> "$TMP/stderr.txt"
}

# ── Case 1: ADVERSARIAL — stale image → flag appears + loud warning ────────
echo "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" > "$TMP/label.txt"
set +e
OUT=$(run_check "$TMP/docker-stub.sh" "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
RC=$?
set -e
if [ "$RC" -ne 0 ]; then
  echo "FAIL: stale rig must be a warning (exit 0), got exit $RC" >&2
  exit 1
fi
if [ "$OUT" != "true" ]; then
  echo "FAIL: stale image should emit 'true' on stdout, got '$OUT'" >&2
  exit 1
fi
if ! grep -q 'STALE RIG' "$TMP/stderr.txt"; then
  echo "FAIL: stale image did not print the STALE RIG warning:" >&2
  cat "$TMP/stderr.txt" >&2
  exit 1
fi
echo "ok: ADVERSARIAL — stale image → rigShaMismatch=true + loud STALE RIG warning, exit 0"

# ── Case 2: fresh image → no flag, no warning ───────────────────────────────
echo "cccccccccccccccccccccccccccccccccccccccc" > "$TMP/label.txt"
OUT=$(run_check "$TMP/docker-stub.sh" "cccccccccccccccccccccccccccccccccccccccc")
if [ "$OUT" != "false" ]; then
  echo "FAIL: fresh image should emit 'false', got '$OUT'" >&2
  exit 1
fi
if grep -q 'STALE RIG' "$TMP/stderr.txt"; then
  echo "FAIL: fresh image must NOT print the STALE RIG warning:" >&2
  cat "$TMP/stderr.txt" >&2
  exit 1
fi
echo "ok: fresh image → rigShaMismatch=false, no warning"

# ── Case 3: no provenance label (the direct `compose up --build` footgun) ──
: > "$TMP/label.txt"
OUT=$(run_check "$TMP/docker-stub.sh" "cccccccccccccccccccccccccccccccccccccccc")
if [ "$OUT" != "true" ] || ! grep -q 'STALE RIG' "$TMP/stderr.txt"; then
  echo "FAIL: label-less image (built without GIT_SHA) must be flagged stale; got '$OUT'" >&2
  cat "$TMP/stderr.txt" >&2
  exit 1
fi
echo "ok: image without GIT_SHA label (direct compose up --build) → flagged stale"

# ── Case 4: label literally 'unknown' (compose default) → flagged stale ────
echo "unknown" > "$TMP/label.txt"
OUT=$(run_check "$TMP/docker-stub.sh" "cccccccccccccccccccccccccccccccccccccccc")
if [ "$OUT" != "true" ]; then
  echo "FAIL: label 'unknown' must be flagged stale, got '$OUT'" >&2
  exit 1
fi
echo "ok: label 'unknown' (GIT_SHA build-arg default) → flagged stale"

# ── Case 5: no titan-server container → quiet false ─────────────────────────
OUT=$(run_check "$TMP/docker-empty.sh" "cccccccccccccccccccccccccccccccccccccccc")
if [ "$OUT" != "false" ] || grep -q 'STALE RIG' "$TMP/stderr.txt"; then
  echo "FAIL: no running rig should emit 'false' quietly, got '$OUT'" >&2
  exit 1
fi
echo "ok: no titan-server container → false, no warning"

# ── Case 6: explicit skip → false without touching docker at all ────────────
OUT=$(RIG_SMOKE_SKIP_FRESHNESS=1 RIG_SMOKE_DOCKER_CMD="/nonexistent/docker" bash "$SCRIPT" 2>/dev/null)
if [ "$OUT" != "false" ]; then
  echo "FAIL: RIG_SMOKE_SKIP_FRESHNESS=1 should emit 'false', got '$OUT'" >&2
  exit 1
fi
echo "ok: RIG_SMOKE_SKIP_FRESHNESS=1 → false (docker never invoked)"

echo ""
echo "PASS: all check-rig-freshness.sh cases (including adversarial stale-image guard)"
