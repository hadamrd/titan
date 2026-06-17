#!/usr/bin/env bash
# Adversarial unit test for dev/sprint-loop/check-duplicate-migrations.sh (#1157, #1159).
#
# Acceptance criterion under test:
#   The Flyway duplicate-version guard must turn a colliding `V<N>__*.sql` into
#   a RED PR gate (exit 1, offending version named) instead of a boot-time
#   crash. #1157 shipped a second V34 to trunk; Flyway refused to boot and the
#   rig was undeployable. These tests pin every case: clean tree passes, a
#   collision (same dir AND across dirs, plain AND `_1` suffix forms) fails with
#   the version named, repeatable migrations are ignored, and a missing dir is a
#   loud config error — never a silent green.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SCRIPT="$REPO_ROOT/dev/sprint-loop/check-duplicate-migrations.sh"

if [ ! -f "$SCRIPT" ]; then
  echo "FAIL: $SCRIPT missing" >&2
  exit 1
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# ── Case 1: clean tree (unique versions) → exit 0 ────────────────────────────
mkdir -p "$TMP/clean"
: > "$TMP/clean/V1__init.sql"
: > "$TMP/clean/V2__agents.sql"
: > "$TMP/clean/V10__credentials.sql"
set +e
out=$(bash "$SCRIPT" "$TMP/clean" 2>&1); rc=$?
set -e
if [ "$rc" -ne 0 ]; then echo "FAIL: clean tree should exit 0, got $rc: $out" >&2; exit 1; fi
echo "ok: clean tree (unique versions) → exit 0"

# ── Case 2: ADVERSARIAL — two files share version 34 in the SAME dir → exit 1 ─
# This is the literal #1157 shape (a second V34 landed alongside the first).
mkdir -p "$TMP/dup"
: > "$TMP/dup/V33__a.sql"
: > "$TMP/dup/V34__build_search_fts.sql"
: > "$TMP/dup/V34__some_other_thing.sql"
set +e
out=$(bash "$SCRIPT" "$TMP/dup" 2>&1); rc=$?
set -e
if [ "$rc" -ne 1 ]; then echo "FAIL: duplicate V34 should exit 1, got $rc" >&2; exit 1; fi
if ! printf '%s' "$out" | grep -q "version 34"; then
  echo "FAIL: output must name the offending version (34). got: $out" >&2; exit 1
fi
if ! printf '%s' "$out" | grep -q "V34__some_other_thing.sql"; then
  echo "FAIL: output must name BOTH colliding files. got: $out" >&2; exit 1
fi
echo "ok: ADVERSARIAL — duplicate V34 in same dir → exit 1, version + files named"

# ── Case 3: ADVERSARIAL — collision ACROSS two scanned dirs → exit 1 ─────────
# Flyway merges all locations into one history; a clash across dirs still
# breaks boot. The checker must scan the dirs together.
mkdir -p "$TMP/a" "$TMP/b"
: > "$TMP/a/V5__failure_model.sql"
: > "$TMP/b/V5__duplicate_elsewhere.sql"
set +e
out=$(bash "$SCRIPT" "$TMP/a" "$TMP/b" 2>&1); rc=$?
set -e
if [ "$rc" -ne 1 ]; then echo "FAIL: cross-dir V5 collision should exit 1, got $rc" >&2; exit 1; fi
if ! printf '%s' "$out" | grep -q "version 5"; then
  echo "FAIL: cross-dir collision must name version 5. got: $out" >&2; exit 1
fi
echo "ok: ADVERSARIAL — collision across two dirs → exit 1"

# ── Case 4: `V15` and `V15_1` are DISTINCT versions (15 vs 15.1) → exit 0 ────
# Flyway treats `_` as a separator: V15_1 is version 15.1, not a dup of 15.
# This mirrors the real repo (migration-postgresql ships V15_1 alongside V15).
mkdir -p "$TMP/suffix"
: > "$TMP/suffix/V15__pipeline_timeout.sql"
: > "$TMP/suffix/V15_1__partial_index.sql"
set +e
out=$(bash "$SCRIPT" "$TMP/suffix" 2>&1); rc=$?
set -e
if [ "$rc" -ne 0 ]; then
  echo "FAIL: V15 and V15_1 are distinct versions, should exit 0. got $rc: $out" >&2; exit 1
fi
echo "ok: V15 vs V15_1 treated as distinct (15 vs 15.1) → exit 0"

# ── Case 5: ADVERSARIAL — `V15_1` and `V15.1` ARE the same version → exit 1 ──
# Flyway normalises `_` and `.` to the same separator, so these collide.
mkdir -p "$TMP/norm"
: > "$TMP/norm/V15_1__a.sql"
: > "$TMP/norm/V15.1__b.sql"
set +e
out=$(bash "$SCRIPT" "$TMP/norm" 2>&1); rc=$?
set -e
if [ "$rc" -ne 1 ]; then
  echo "FAIL: V15_1 and V15.1 are the SAME version (15.1), should exit 1. got $rc" >&2; exit 1
fi
if ! printf '%s' "$out" | grep -q "version 15.1"; then
  echo "FAIL: must name normalised version 15.1. got: $out" >&2; exit 1
fi
echo "ok: ADVERSARIAL — V15_1 and V15.1 normalise to the same version → exit 1"

# ── Case 6: repeatable migrations (R__*.sql) are IGNORED (no version) ─────────
mkdir -p "$TMP/repeat"
: > "$TMP/repeat/V1__init.sql"
: > "$TMP/repeat/R__partial_indexes.sql"
: > "$TMP/repeat/R__some_view.sql"
set +e
out=$(bash "$SCRIPT" "$TMP/repeat" 2>&1); rc=$?
set -e
if [ "$rc" -ne 0 ]; then
  echo "FAIL: R__ migrations have no version, must not collide. got $rc: $out" >&2; exit 1
fi
echo "ok: repeatable (R__) migrations ignored → exit 0"

# ── Case 7: missing dir → exit 2 (loud config error, never silent green) ─────
set +e
bash "$SCRIPT" "$TMP/does-not-exist" >/dev/null 2>&1; rc=$?
set -e
if [ "$rc" -ne 2 ]; then echo "FAIL: missing dir should exit 2, got $rc" >&2; exit 1; fi
echo "ok: missing dir → exit 2 (config error)"

# ── Case 8: REAL repo dirs (default invocation) are currently clean → exit 0 ─
# Regression guard: trunk must stay green through this checker. If this ever
# fails, trunk has a real duplicate-version collision that needs renumbering.
set +e
out=$(bash "$SCRIPT" 2>&1); rc=$?
set -e
if [ "$rc" -ne 0 ]; then
  echo "FAIL: real repo migration dirs must currently be collision-free. got $rc: $out" >&2; exit 1
fi
echo "ok: real repo Flyway dirs are currently unique → exit 0"

echo ""
echo "PASS: all check-duplicate-migrations.sh cases (including adversarial collision guards)"
