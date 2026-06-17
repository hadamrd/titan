#!/usr/bin/env bash
# Adversarial unit test for dev/git-hooks/ui-precommit-gate.sh (#1199).
#
# Acceptance criterion under test:
#   Pre-commit gate 5 must run BOTH `vite build` AND `tsc --noEmit`. Before
#   #1199 it ran only `vite build`, which TRANSPILES (esbuild strips types
#   without checking), so a genuine TS type error (the PageHeaderProps
#   redeclare-`title`-over-HTMLAttributes shape) passed pre-commit, reached
#   trunk green, and only detonated ~15 min later at the deploy's
#   `tsc --noEmit` (#1194). These tests pin that the gate now type-checks:
#     - clean UI  -> both run, exit 0
#     - type error (tsc fails, vite passes) -> exit 2 with a tsc message
#       (this is the regression the gate exists to catch — would have failed
#        BEFORE the fix because tsc was never invoked)
#     - orphan import (vite fails) -> exit 1, tsc never reached
#     - no UI files -> exit 0 (nothing to do)
#     - both fail -> vite's exit 1 surfaces first (deterministic ordering)
#     - pnpm install bootstrap fails -> exit 3, vite/tsc never reached
#     - tsconfig.json missing -> exit 3 (setup), NOT a misleading exit-2 type
#       error (the preflight guard the critic asked for)
#
# We inject a FAKE pnpm (UI_GATE_PNPM) that simulates vite/tsc success/failure
# deterministically, so the test is sub-second and needs no node_modules.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SCRIPT="$REPO_ROOT/dev/git-hooks/ui-precommit-gate.sh"

if [ ! -f "$SCRIPT" ]; then
  echo "FAIL: $SCRIPT missing" >&2
  exit 1
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# A fake titan-ui dir with node_modules present (so install is skipped) and a
# tsconfig so the invocation looks real.
UI_DIR="$TMP/titan-ui"
mkdir -p "$UI_DIR/node_modules"
printf '{"compilerOptions":{}}\n' > "$UI_DIR/tsconfig.json"

# Fake pnpm: `pnpm exec vite build ...` and `pnpm exec tsc ...` each succeed or
# fail based on FAKE_VITE_RC / FAKE_TSC_RC. It also records what it was asked to
# run so we can assert tsc was (or wasn't) reached.
FAKE_PNPM="$TMP/pnpm"
CALLS_LOG="$TMP/calls.log"
cat > "$FAKE_PNPM" <<'EOF'
#!/usr/bin/env bash
# args look like: exec vite build --logLevel error  OR  exec tsc -p tsconfig.json --noEmit
echo "$@" >> "$CALLS_LOG"
case "$*" in
  install*)
    echo "fake: pnpm install" >&2
    exit "${FAKE_INSTALL_RC:-0}" ;;
  *"vite build"*)
    echo "fake: vite build" >&2
    exit "${FAKE_VITE_RC:-0}" ;;
  *"tsc "*)
    if [ -n "${FAKE_TSC_LONG:-}" ]; then
      # Emulate tsc's real output shape: the ROOT-CAUSE error first, then many
      # cascading follow-on errors. Used to prove the gate shows the HEAD
      # (root cause), not just the tail.
      echo "ROOT_CAUSE_ERROR: src/PageHeader.tsx(3,3): error TS2430: interface incompatible" >&2
      for i in $(seq 1 80); do echo "cascade follow-on error line $i" >&2; done
    else
      echo "fake: tsc --noEmit" >&2
    fi
    exit "${FAKE_TSC_RC:-0}" ;;
  *)
    exit 0 ;;
esac
EOF
chmod +x "$FAKE_PNPM"

run_gate() {
  # run_gate <vite_rc> <tsc_rc> -- args...
  local vite_rc="$1" tsc_rc="$2"; shift 2
  : > "$CALLS_LOG"
  set +e
  out=$(UI_GATE_DIR="$UI_DIR" UI_GATE_PNPM="$FAKE_PNPM" UI_GATE_SKIP_INSTALL=1 \
        CALLS_LOG="$CALLS_LOG" FAKE_VITE_RC="$vite_rc" FAKE_TSC_RC="$tsc_rc" \
        UI_GATE_VITE_LOG="$TMP/vite.log" UI_GATE_TSC_LOG="$TMP/tsc.log" \
        bash "$SCRIPT" "$@" 2>&1)
  rc=$?
  set -e
}

# ── Case 1: clean UI change → vite build + tsc both run → exit 0 ─────────────
run_gate 0 0 "titan-ui/src/components/PageHeader.tsx"
[ "$rc" -eq 0 ] || { echo "FAIL: clean change should exit 0, got $rc: $out" >&2; exit 1; }
grep -q "vite build" "$CALLS_LOG" || { echo "FAIL: vite build was not invoked. calls: $(cat "$CALLS_LOG")" >&2; exit 1; }
grep -q "tsc " "$CALLS_LOG"       || { echo "FAIL: tsc was not invoked on the clean path. calls: $(cat "$CALLS_LOG")" >&2; exit 1; }
echo "ok: clean UI change → vite build + tsc --noEmit both run → exit 0"

# ── Case 2: ADVERSARIAL — TYPE error (the #1199 / #1194 shape) ───────────────
# vite build passes (esbuild transpiles past the type error) but tsc fails.
# This is THE regression: before the fix tsc was never run, so this case would
# have exited 0 (green) and the type error would have reached trunk + deploy.
run_gate 0 1 "titan-ui/src/components/PageHeader.tsx"
[ "$rc" -eq 2 ] || { echo "FAIL: type error must exit 2 (tsc gate), got $rc: $out" >&2; exit 1; }
grep -q "vite build" "$CALLS_LOG" || { echo "FAIL: vite build should still run. calls: $(cat "$CALLS_LOG")" >&2; exit 1; }
grep -q "tsc " "$CALLS_LOG"       || { echo "FAIL: tsc must run even when vite passed. calls: $(cat "$CALLS_LOG")" >&2; exit 1; }
printf '%s' "$out" | grep -qi "tsc"  || { echo "FAIL: failure message must mention tsc. got: $out" >&2; exit 1; }
printf '%s' "$out" | grep -qi "type" || { echo "FAIL: failure message must mention TYPE errors. got: $out" >&2; exit 1; }
echo "ok: ADVERSARIAL — type error (vite green, tsc red) → exit 2 with tsc/type message"

# ── Case 3: ADVERSARIAL — orphan/missing import → vite build fails → exit 1 ──
# tsc must NOT be reached (vite short-circuits), so the rollup-strict drift
# class keeps its own distinct exit code + message.
run_gate 1 0 "titan-ui/src/routes/index.tsx"
[ "$rc" -eq 1 ] || { echo "FAIL: vite build failure must exit 1, got $rc: $out" >&2; exit 1; }
grep -q "vite build" "$CALLS_LOG" || { echo "FAIL: vite build should run. calls: $(cat "$CALLS_LOG")" >&2; exit 1; }
grep -q "tsc " "$CALLS_LOG"       && { echo "FAIL: tsc should NOT run after vite failed. calls: $(cat "$CALLS_LOG")" >&2; exit 1; }
printf '%s' "$out" | grep -qi "vite build" || { echo "FAIL: failure message must mention vite build. got: $out" >&2; exit 1; }
echo "ok: ADVERSARIAL — orphan import (vite red) → exit 1, tsc not reached"

# ── Case 4: no UI files staged → exit 0, nothing invoked ─────────────────────
run_gate 0 0
[ "$rc" -eq 0 ] || { echo "FAIL: no args should exit 0, got $rc: $out" >&2; exit 1; }
[ ! -s "$CALLS_LOG" ] || { echo "FAIL: nothing should be invoked with no UI files. calls: $(cat "$CALLS_LOG")" >&2; exit 1; }
echo "ok: no UI files staged → exit 0, neither vite nor tsc invoked"

# ── Case 5: ADVERSARIAL — both fail → vite short-circuits first (exit 1) ─────
# Defensive: if BOTH would fail, the vite gate runs first, so we surface its
# code (1) deterministically rather than a confusing mix.
run_gate 1 1 "titan-ui/src/x.tsx"
[ "$rc" -eq 1 ] || { echo "FAIL: both-fail should surface vite's exit 1 first, got $rc: $out" >&2; exit 1; }
echo "ok: ADVERSARIAL — both fail → vite's exit 1 surfaces first (deterministic ordering)"

# ── Case 6: bootstrap failure — pnpm install fails → exit 3 ──────────────────
# Previously every case set UI_GATE_SKIP_INSTALL=1, so the exit-3 install path
# was never exercised. Use a FRESH ui dir WITHOUT node_modules (so the bootstrap
# actually runs) and a fake pnpm whose `install` exits non-zero. The gate must
# surface exit 3 (NOT 1/2) and a "pnpm install failed" message — neither vite
# nor tsc should be reached.
FRESH_UI="$TMP/fresh-ui"
mkdir -p "$FRESH_UI"   # deliberately NO node_modules
printf '{"compilerOptions":{}}\n' > "$FRESH_UI/tsconfig.json"
: > "$CALLS_LOG"
set +e
out=$(UI_GATE_DIR="$FRESH_UI" UI_GATE_PNPM="$FAKE_PNPM" UI_GATE_SKIP_INSTALL=0 \
      CALLS_LOG="$CALLS_LOG" FAKE_INSTALL_RC=1 \
      UI_GATE_VITE_LOG="$TMP/vite.log" UI_GATE_TSC_LOG="$TMP/tsc.log" \
      bash "$SCRIPT" "titan-ui/src/components/PageHeader.tsx" 2>&1)
rc=$?
set -e
[ "$rc" -eq 3 ] || { echo "FAIL: pnpm install failure must exit 3 (bootstrap), got $rc: $out" >&2; exit 1; }
grep -q "^install" "$CALLS_LOG" || { echo "FAIL: pnpm install should have been attempted. calls: $(cat "$CALLS_LOG")" >&2; exit 1; }
grep -q "vite build" "$CALLS_LOG" && { echo "FAIL: vite build must NOT run after install failed. calls: $(cat "$CALLS_LOG")" >&2; exit 1; }
grep -q "tsc " "$CALLS_LOG"       && { echo "FAIL: tsc must NOT run after install failed. calls: $(cat "$CALLS_LOG")" >&2; exit 1; }
printf '%s' "$out" | grep -qi "install failed" || { echo "FAIL: message must mention the install bootstrap failure. got: $out" >&2; exit 1; }
echo "ok: bootstrap — pnpm install fails → exit 3, vite/tsc not reached"

# ── Case 7: missing tsconfig.json → exit 3 (NOT a misleading exit-2/type) ────
# Guards the regression the critic flagged: a missing tsconfig made
# `tsc -p tsconfig.json` fail with exit 2 → the misleading "found TYPE errors"
# message. The preflight guard must classify it as a setup/bootstrap failure
# (exit 3) BEFORE any vite/tsc run.
NOCFG_UI="$TMP/nocfg-ui"
mkdir -p "$NOCFG_UI/node_modules"   # node_modules present so install is skipped
# deliberately NO tsconfig.json
: > "$CALLS_LOG"
set +e
out=$(UI_GATE_DIR="$NOCFG_UI" UI_GATE_PNPM="$FAKE_PNPM" UI_GATE_SKIP_INSTALL=1 \
      CALLS_LOG="$CALLS_LOG" \
      UI_GATE_VITE_LOG="$TMP/vite.log" UI_GATE_TSC_LOG="$TMP/tsc.log" \
      bash "$SCRIPT" "titan-ui/src/components/PageHeader.tsx" 2>&1)
rc=$?
set -e
[ "$rc" -eq 3 ] || { echo "FAIL: missing tsconfig must exit 3 (setup), not 2/type, got $rc: $out" >&2; exit 1; }
[ ! -s "$CALLS_LOG" ] || { echo "FAIL: nothing should run when tsconfig is missing. calls: $(cat "$CALLS_LOG")" >&2; exit 1; }
printf '%s' "$out" | grep -qi "tsconfig.json missing" || { echo "FAIL: message must name the missing tsconfig. got: $out" >&2; exit 1; }
printf '%s' "$out" | grep -qi "found TYPE error" && { echo "FAIL: missing tsconfig must NOT use the misleading 'found TYPE errors' tsc message. got: $out" >&2; exit 1; }
echo "ok: missing tsconfig.json → exit 3 with a setup message (not a misleading type error)"

# ── Case 8: long tsc output → ROOT-CAUSE (first) error must be shown ─────────
# tsc prints the offending type incompatibility FIRST, then many cascading
# follow-on errors. A plain `tail` would hide the root cause; the gate shows
# the HEAD so the dev sees the real error. Inject an 80+ line tsc log whose
# first line is the root cause and assert it survives to stderr.
: > "$CALLS_LOG"
set +e
out=$(UI_GATE_DIR="$UI_DIR" UI_GATE_PNPM="$FAKE_PNPM" UI_GATE_SKIP_INSTALL=1 \
      CALLS_LOG="$CALLS_LOG" FAKE_VITE_RC=0 FAKE_TSC_RC=1 FAKE_TSC_LONG=1 \
      UI_GATE_VITE_LOG="$TMP/vite.log" UI_GATE_TSC_LOG="$TMP/tsc.log" \
      bash "$SCRIPT" "titan-ui/src/components/PageHeader.tsx" 2>&1)
rc=$?
set -e
[ "$rc" -eq 2 ] || { echo "FAIL: long tsc type error must exit 2, got $rc: $out" >&2; exit 1; }
printf '%s' "$out" | grep -q "ROOT_CAUSE_ERROR" || { echo "FAIL: root-cause (first) tsc error must be shown, not hidden by a tail. got: $out" >&2; exit 1; }
echo "ok: long tsc output → root-cause (first) error is shown to the dev"

echo ""
echo "PASS: all ui-precommit-gate.sh cases (vite + tsc, type-error regression, orphan-import, empty, both-fail, bootstrap-fail, missing-tsconfig, long-log root-cause)"
