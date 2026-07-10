#!/usr/bin/env bash
# Adversarial unit test for dev/rig-smoke/check-rig-freshness.sh (#44).
#
# Acceptance criterion under test (issue #44; worker-skew extension #153;
# worker label parity #155):
#   - STALE image (label sha != checkout HEAD) → the STALE RIG flag appears
#     (adversarial: assert the warning IS printed and stdout is `true`).
#   - FRESH image (label sha == HEAD) → the flag does NOT appear.
#   - Image without a provenance label (built via direct `docker compose up
#     --build`, the original footgun) → flagged as stale.
#   - #155 three-case worker contract:
#       labeled-fresh  → silent `false` — even with a huge image-age skew
#                        (the byte-identical-jar cache-hit that false-
#                        positived the #154 heuristic on 2026-07-10);
#       labeled-stale  → flagged `true` — even with zero image-age skew;
#       unlabeled      → falls back to the #153/#154 skew heuristic.
#   - Unlabeled fallback: fresh server + WORKER image built long before the
#     server image (#153: a recompose that cache-hit a stale jar shipped a
#     pre-#146 engine undetected) → flagged as stale; within tolerated skew
#     → false.
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

# Stub docker: `ps` answers per the name= filter; `inspect` dispatches on the
# full arg string — server / worker provenance labels come from $TMP/label.txt
# / $TMP/worker-label.txt (empty file = no label; #155 gave the worker its own
# label), image ids are fixed, image Created timestamps come from
# $TMP/server-created.txt / $TMP/worker-created.txt (#153 skew fallback).
cat > "$TMP/docker-stub.sh" <<EOF
#!/usr/bin/env bash
args="\$*"
case "\$args" in
  ps*name=titan-server*) echo "local-titan-server-1" ;;
  ps*name=titan-worker*) cat "$TMP/worker-name.txt" 2>/dev/null ;;
  *"index .Config.Labels"*local-titan-server-1) cat "$TMP/label.txt" ;;
  *"index .Config.Labels"*local-titan-worker-1) cat "$TMP/worker-label.txt" ;;
  *"{{.Image}} local-titan-server-1"*)      echo "sha256:srvimg" ;;
  *"{{.Image}} local-titan-worker-1"*)      echo "sha256:wrkimg" ;;
  *"{{.Created}} sha256:srvimg"*)           cat "$TMP/server-created.txt" ;;
  *"{{.Created}} sha256:wrkimg"*)           cat "$TMP/worker-created.txt" ;;
  *) exit 1 ;;
esac
EOF
chmod +x "$TMP/docker-stub.sh"

iso_utc_ago() { # <seconds-ago> → docker-style RFC3339 timestamp
  date -u -d "@$(( $(date +%s) - $1 ))" +%Y-%m-%dT%H:%M:%S.000000000Z
}

# Default fixture state: worker present but UNLABELED (pre-#155 image → the
# skew-fallback path), both images built ~now (no skew).
reset_stub_state() {
  echo "local-titan-worker-1" > "$TMP/worker-name.txt"
  : > "$TMP/worker-label.txt"
  iso_utc_ago 60 > "$TMP/server-created.txt"
  iso_utc_ago 120 > "$TMP/worker-created.txt"
}
reset_stub_state

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

# ── Case 7: #153/#155 — UNLABELED worker 18h older → skew fallback flags it ─
reset_stub_state
echo "cccccccccccccccccccccccccccccccccccccccc" > "$TMP/label.txt"
iso_utc_ago 60 > "$TMP/server-created.txt"
iso_utc_ago $((18 * 3600)) > "$TMP/worker-created.txt" # the observed 17.7h incident skew
OUT=$(run_check "$TMP/docker-stub.sh" "cccccccccccccccccccccccccccccccccccccccc")
if [ "$OUT" != "true" ] || ! grep -q 'STALE RIG' "$TMP/stderr.txt"; then
  echo "FAIL: unlabeled worker image built 18h before a fresh server image must be flagged stale (#153); got '$OUT'" >&2
  cat "$TMP/stderr.txt" >&2
  exit 1
fi
if ! grep -q 'titan-worker' "$TMP/stderr.txt"; then
  echo "FAIL: the #153 skew warning should name the worker image:" >&2
  cat "$TMP/stderr.txt" >&2
  exit 1
fi
if ! grep -q 'falling back' "$TMP/stderr.txt"; then
  echo "FAIL: an unlabeled worker must announce the skew FALLBACK (#155 case 3):" >&2
  cat "$TMP/stderr.txt" >&2
  exit 1
fi
echo "ok: #153/#155 — unlabeled worker + 18h skew → fallback heuristic flags stale"

# ── Case 8: #153 — worker image within tolerated skew → false ───────────────
reset_stub_state
echo "cccccccccccccccccccccccccccccccccccccccc" > "$TMP/label.txt"
iso_utc_ago 60 > "$TMP/server-created.txt"
iso_utc_ago 600 > "$TMP/worker-created.txt" # 10min apart — a normal dev:titan build
OUT=$(run_check "$TMP/docker-stub.sh" "cccccccccccccccccccccccccccccccccccccccc")
if [ "$OUT" != "false" ] || grep -q 'STALE RIG' "$TMP/stderr.txt"; then
  echo "FAIL: worker/server images built minutes apart must NOT be flagged; got '$OUT'" >&2
  cat "$TMP/stderr.txt" >&2
  exit 1
fi
echo "ok: #153 — worker image within tolerated skew (10min) → false"

# ── Case 9: #153 — no worker container → server verdict stands, quiet skip ──
reset_stub_state
: > "$TMP/worker-name.txt"
echo "cccccccccccccccccccccccccccccccccccccccc" > "$TMP/label.txt"
OUT=$(run_check "$TMP/docker-stub.sh" "cccccccccccccccccccccccccccccccccccccccc")
if [ "$OUT" != "false" ] || grep -q 'STALE RIG' "$TMP/stderr.txt"; then
  echo "FAIL: fresh server + no worker container should emit 'false' quietly; got '$OUT'" >&2
  cat "$TMP/stderr.txt" >&2
  exit 1
fi
echo "ok: #153 — no titan-worker container → skew check skipped, false"

# ── Case 10: #155 — LABELED-FRESH worker → silent false DESPITE huge skew ───
# Adversarial against the #154 heuristic: a byte-identical jar cache-hit
# leaves an old-Created but content-correct image (the observed 2026-07-10
# false positive, 6.6h). With a matching label, the skew math must never run.
reset_stub_state
echo "cccccccccccccccccccccccccccccccccccccccc" > "$TMP/label.txt"
echo "cccccccccccccccccccccccccccccccccccccccc" > "$TMP/worker-label.txt"
iso_utc_ago 60 > "$TMP/server-created.txt"
iso_utc_ago $((18 * 3600)) > "$TMP/worker-created.txt" # would trip the skew fallback
OUT=$(run_check "$TMP/docker-stub.sh" "cccccccccccccccccccccccccccccccccccccccc")
if [ "$OUT" != "false" ] || grep -q 'STALE RIG' "$TMP/stderr.txt"; then
  echo "FAIL: labeled-fresh worker must be silent 'false' even with 18h image-age skew (#155 case 1); got '$OUT'" >&2
  cat "$TMP/stderr.txt" >&2
  exit 1
fi
if grep -q 'falling back' "$TMP/stderr.txt"; then
  echo "FAIL: a labeled worker must NOT hit the skew fallback:" >&2
  cat "$TMP/stderr.txt" >&2
  exit 1
fi
echo "ok: #155 case 1 — labeled-fresh worker → silent false (skew heuristic bypassed)"

# ── Case 11: #155 ADVERSARIAL — LABELED-STALE worker → flagged, zero skew ───
# Fresh server, worker label carries a DIFFERENT sha, images built minutes
# apart (the skew heuristic would say fresh). The label verdict must win.
reset_stub_state
echo "cccccccccccccccccccccccccccccccccccccccc" > "$TMP/label.txt"
echo "dddddddddddddddddddddddddddddddddddddddd" > "$TMP/worker-label.txt"
OUT=$(run_check "$TMP/docker-stub.sh" "cccccccccccccccccccccccccccccccccccccccc")
if [ "$OUT" != "true" ] || ! grep -q 'STALE RIG' "$TMP/stderr.txt"; then
  echo "FAIL: labeled-stale worker must be flagged 'true' even with zero image-age skew (#155 case 2); got '$OUT'" >&2
  cat "$TMP/stderr.txt" >&2
  exit 1
fi
if ! grep -q 'titan-worker' "$TMP/stderr.txt"; then
  echo "FAIL: the #155 label-mismatch warning should name the worker:" >&2
  cat "$TMP/stderr.txt" >&2
  exit 1
fi
echo "ok: #155 case 2 ADVERSARIAL — labeled-stale worker → flagged despite zero skew"

# ── Case 12: #155 — worker label 'unknown' (compose default) → fallback ─────
reset_stub_state
echo "cccccccccccccccccccccccccccccccccccccccc" > "$TMP/label.txt"
echo "unknown" > "$TMP/worker-label.txt" # direct `docker compose up --build titan-worker`
OUT=$(run_check "$TMP/docker-stub.sh" "cccccccccccccccccccccccccccccccccccccccc")
if [ "$OUT" != "false" ] || ! grep -q 'falling back' "$TMP/stderr.txt"; then
  echo "FAIL: worker label 'unknown' must route to the skew fallback (#155 case 3); got '$OUT'" >&2
  cat "$TMP/stderr.txt" >&2
  exit 1
fi
echo "ok: #155 case 3 — worker label 'unknown' → skew fallback (no skew → false)"

echo ""
echo "PASS: all check-rig-freshness.sh cases (adversarial stale-image, #153 worker-skew fallback, #155 worker label parity)"
