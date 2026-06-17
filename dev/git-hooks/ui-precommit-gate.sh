#!/usr/bin/env bash
# Titan pre-commit UI gate (gate 5): vite build + `tsc --noEmit` on staged
# titan-ui .ts/.tsx changes.
#
# Why TWO tools, not one (the #1199 fix):
#   - `vite build` (esbuild/rollup) only TRANSPILES. esbuild strips types
#     WITHOUT checking them, so a genuine TS *type* error (e.g. a
#     PageHeaderProps that redeclares `title` over HTMLAttributes) sails
#     straight through a green `vite build`.
#   - `tsc -p tsconfig.json --noEmit` is the ONLY step that actually
#     type-checks.
#   Running only `vite build` (the pre-#1199 behaviour) let exactly that
#   PageHeaderProps type error reach trunk, then blow up ~15 min later at the
#   deploy's `tsc --noEmit` (incident #1194). This gate closes the gap: type
#   errors now fail the COMMIT, not the deploy.
#   We still run `vite build` because rollup is STRICTER than vitest about
#   orphan / missing imports (PRs #415/#422/#426/#428) — a drift class that
#   `tsc` does NOT catch. The two checks are complementary, not redundant.
#
# Usage:
#   ui-precommit-gate.sh <staged-ui-file>...   # files used only for logging/count
#
# Env overrides (used by the unit test to inject a fake runner):
#   UI_GATE_DIR            titan-ui project dir (default: <repo-root>/titan-ui)
#   UI_GATE_PNPM           package-runner command (default: pnpm)
#   UI_GATE_SKIP_INSTALL   set to 1 to skip the node_modules bootstrap
#   UI_GATE_VITE_LOG       vite build log path (default: /tmp/titan-precommit-vite.log)
#   UI_GATE_TSC_LOG        tsc log path        (default: /tmp/titan-precommit-tsc.log)
#
# Exit codes (distinct so callers/tests can tell WHICH sub-gate failed):
#   0  both vite build + tsc --noEmit passed (or no UI files staged)
#   1  vite build failed   (orphan/missing import — rollup stricter than vitest)
#   2  tsc --noEmit failed  (TS type error — the #1199 deploy-breaker class)
#   3  pnpm install bootstrap failed
set -euo pipefail

RED=$'\e[31m'; GRN=$'\e[32m'; RST=$'\e[0m'

PNPM="${UI_GATE_PNPM:-pnpm}"
VITE_LOG="${UI_GATE_VITE_LOG:-/tmp/titan-precommit-vite.log}"
TSC_LOG="${UI_GATE_TSC_LOG:-/tmp/titan-precommit-tsc.log}"

# Resolve the titan-ui dir. Default to <repo-root>/titan-ui; overridable so the
# test can point at a throwaway fixture dir with a fake pnpm.
if [[ -n "${UI_GATE_DIR:-}" ]]; then
  UI_DIR="$UI_GATE_DIR"
else
  UI_DIR="$(git rev-parse --show-toplevel 2>/dev/null)/titan-ui"
fi

if [[ ! -d "$UI_DIR" ]]; then
  echo "${RED}gate 5: titan-ui dir not found at '$UI_DIR'${RST}" >&2
  exit 3
fi

# No UI files staged → nothing to do. (The hook only calls us when there are,
# but keep the guard so the script is safe to invoke directly with no args.)
UI_COUNT=$#
if (( UI_COUNT == 0 )); then
  exit 0
fi

cd "$UI_DIR"

# Preflight: tsconfig.json must exist. Without this guard a missing tsconfig
# (detached worktree, deleted file) makes `tsc -p tsconfig.json` fail with a
# "cannot find file" error that exits 2 → the misleading "found TYPE errors"
# message. Treat a missing project file as a bootstrap failure (exit 3).
if [[ ! -f tsconfig.json ]]; then
  echo "${RED}gate 5: tsconfig.json missing in '$UI_DIR' — cannot type-check (this is a setup problem, not a type error).${RST}" >&2
  exit 3
fi

# Bootstrap node_modules if missing (e.g. a fresh worktree). Skippable in tests.
if [[ "${UI_GATE_SKIP_INSTALL:-0}" != "1" && ! -d node_modules ]]; then
  echo "${GRN}gate 5: titan-ui/node_modules missing — running pnpm install${RST}"
  if ! "$PNPM" install --frozen-lockfile >"$VITE_LOG" 2>&1; then
    tail -40 "$VITE_LOG" >&2
    echo "${RED}gate 5: pnpm install failed.${RST}" >&2
    exit 3
  fi
fi

# ── 5a: vite build — rollup catches orphan/missing imports vitest misses ─────
echo "${GRN}gate 5: vite build (~7s) on ${UI_COUNT} staged UI file(s)${RST}"
if ! "$PNPM" exec vite build --logLevel error >"$VITE_LOG" 2>&1; then
  tail -40 "$VITE_LOG" >&2
  echo "${RED}gate 5: titan-ui vite build failed. Likely a missing/orphan import — rollup is stricter than vitest. See PRs #415/#422/#426/#428.${RST}" >&2
  exit 1
fi

# ── 5b: tsc --noEmit — the ONLY step that type-checks (the #1199 fix) ─────────
# vite build above only transpiles (esbuild strips types). Without this, TS
# type errors reach trunk green and detonate at the deploy's tsc (#1194).
echo "${GRN}gate 5: tsc -p tsconfig.json --noEmit (type-check) on ${UI_COUNT} staged UI file(s)${RST}"
if ! "$PNPM" exec tsc -p tsconfig.json --noEmit >"$TSC_LOG" 2>&1; then
  # tsc prints the ROOT-CAUSE error FIRST, with cascading follow-on errors
  # after it (unlike vite, which emits errors at the END of its log). A plain
  # `tail` would hide the original offending type incompatibility, so show the
  # HEAD (root cause) and, for long outputs, a short tail for context.
  if [[ "$(wc -l < "$TSC_LOG")" -gt 60 ]]; then
    head -50 "$TSC_LOG" >&2
    echo "    ... ($(wc -l < "$TSC_LOG") lines total; showing head + tail) ..." >&2
    tail -10 "$TSC_LOG" >&2
  else
    cat "$TSC_LOG" >&2
  fi
  echo "${RED}gate 5: titan-ui tsc --noEmit found TYPE errors. vite build transpiles (esbuild strips types) so it can't catch these — this is the class that broke the deploy in #1194. Fix the type error (root cause is the FIRST error above) and re-stage.${RST}" >&2
  exit 2
fi

echo "${GRN}gate 5: vite build + tsc --noEmit passed${RST}"
exit 0
