#!/usr/bin/env bash
# Integration-shaped unit test for the #149 bind-mount guard as wired through
# dev/rig-smoke/run-golden.sh (the test_run_golden_freshness.sh pattern).
#
# Acceptance criterion under test (issue #149, adversarial both ways):
#   - Reproduce the #147 footgun: a titan container whose /titan/fixtures
#     bind mount points at an EMPTY dir left behind by a deleted worktree →
#     the smoke OUTPUT carries the loud RIG MOUNT ISSUE line AND the
#     appended telemetry line carries "rigMountIssue":true.
#   - Healthy mounts (source inside the checkout) → no warning,
#     "rigMountIssue":false.
#   - Parse-compat: pre-#149 fields (passed/failed/did_not_run/durationMs/
#     rigShaMismatch) survive unchanged so check-3-consecutive.sh keeps
#     counting greens.
#
# docker is stubbed via RIG_SMOKE_DOCKER_CMD — one stub answers BOTH probes
# (freshness inspects .Config.Labels, mounts inspects .Mounts) by matching
# the requested --format; playwright via RIG_SMOKE_PLAYWRIGHT_CMD. The
# oracle is the JSONL file content, independent of the script's echo output.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SCRIPT="$REPO_ROOT/dev/rig-smoke/run-golden.sh"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

JSONL="$TMP/rig-smoke.jsonl"
TEE="$TMP/tee-out.txt"
SHA="cccccccccccccccccccccccccccccccccccccccc"

# Synthetic checkout with a non-empty fixtures counterpart.
CHECKOUT="$TMP/checkout"
mkdir -p "$CHECKOUT/e2e/pipelines/node-simple"
echo '{"name":"fixture"}' > "$CHECKOUT/e2e/pipelines/node-simple/package.json"

# The deleted-worktree leftover: empty dir outside the checkout (#147).
FOREIGN="$TMP/wt-145/e2e/pipelines"
mkdir -p "$FOREIGN"

# Green playwright stub — mount integrity must be orthogonal to pass/fail.
cat > "$TMP/pw-pass.sh" <<'EOF'
#!/usr/bin/env bash
echo "Running 14 tests using 2 workers"
echo "  14 passed (90s)"
exit 0
EOF
chmod +x "$TMP/pw-pass.sh"

# docker stub serving BOTH probes: label sha for the freshness inspect,
# mount lines (from $TMP/mounts.txt) for the mounts inspect.
cat > "$TMP/docker-stub.sh" <<EOF
#!/usr/bin/env bash
case "\$1 \$*" in
  ps\ *)              echo "local-titan-worker-1" ;;
  inspect\ *Labels*)  echo "$SHA" ;;
  inspect\ *Mounts*)  cat "$TMP/mounts.txt" ;;
  *)                  exit 1 ;;
esac
EOF
chmod +x "$TMP/docker-stub.sh"

run_smoke() {
  RIG_SMOKE_PLAYWRIGHT_CMD="$TMP/pw-pass.sh" \
    RIG_SMOKE_JSONL="$JSONL" \
    RIG_SMOKE_TEE="$TEE" \
    RIG_SMOKE_DOCKER_CMD="$TMP/docker-stub.sh" \
    RIG_SMOKE_EXPECTED_SHA="$SHA" \
    RIG_SMOKE_CHECKOUT_ROOT="$CHECKOUT" \
    RIG_SMOKE_SKIP_PREWARM=1 \
    bash "$SCRIPT"
}

# ── Case 1: ADVERSARIAL — empty foreign mount → warning + telemetry flag ───
echo "$FOREIGN|/titan/fixtures" > "$TMP/mounts.txt"
if ! run_smoke > "$TMP/broken.out" 2>&1; then
  echo "FAIL: a mount issue must be a WARNING — the smoke run itself must stay green" >&2
  cat "$TMP/broken.out" >&2
  exit 1
fi
if ! grep -q 'RIG MOUNT ISSUE' "$TMP/broken.out"; then
  echo "FAIL: broken mount did not surface the RIG MOUNT ISSUE warning in smoke output:" >&2
  cat "$TMP/broken.out" >&2
  exit 1
fi
LAST=$(tail -n 1 "$JSONL")
if ! printf '%s' "$LAST" | grep -q '"rigMountIssue":true'; then
  echo "FAIL: broken-mount run's telemetry line lacks rigMountIssue=true: $LAST" >&2
  exit 1
fi
echo "ok: ADVERSARIAL — empty deleted-worktree mount → RIG MOUNT ISSUE warning + rigMountIssue=true (run stays green)"

# ── Case 2: healthy mount → no warning, flag false ─────────────────────────
echo "$CHECKOUT/e2e/pipelines|/titan/fixtures" > "$TMP/mounts.txt"
run_smoke > "$TMP/healthy.out" 2>&1
if grep -q 'RIG MOUNT ISSUE' "$TMP/healthy.out"; then
  echo "FAIL: healthy mount must not warn:" >&2
  cat "$TMP/healthy.out" >&2
  exit 1
fi
LAST=$(tail -n 1 "$JSONL")
if ! printf '%s' "$LAST" | grep -q '"rigMountIssue":false'; then
  echo "FAIL: healthy run's telemetry line lacks rigMountIssue=false: $LAST" >&2
  exit 1
fi
echo "ok: healthy mount → no warning, rigMountIssue=false"

# ── Case 3: parse-compat — pre-#149 fields intact on both lines ────────────
for key in '"passed":14' '"failed":0' '"did_not_run":0' '"durationMs":' '"rigShaMismatch":false'; do
  if [ "$(grep -c "$key" "$JSONL")" -ne 2 ]; then
    echo "FAIL: telemetry field $key missing from a line — parse-compat broken:" >&2
    cat "$JSONL" >&2
    exit 1
  fi
done
# check-3-consecutive.sh must still parse these lines (needs a 3rd green).
tail -n 1 "$JSONL" >> "$JSONL"
if ! bash "$REPO_ROOT/dev/sprint-loop/check-3-consecutive.sh" "$JSONL" > /dev/null; then
  echo "FAIL: check-3-consecutive.sh cannot count runs carrying rigMountIssue" >&2
  exit 1
fi
echo "ok: parse-compat — existing fields intact; check-3-consecutive.sh still counts greens"

echo ""
echo "PASS: all run-golden.sh mount-guard cases (#149 end-to-end)"
