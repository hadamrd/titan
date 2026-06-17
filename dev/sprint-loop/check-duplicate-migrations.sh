#!/usr/bin/env bash
# check-duplicate-migrations.sh — Flyway duplicate-version guard (#1157, #1159).
#
# Why this exists:
#   Flyway refuses to start if two versioned migrations (`V<version>__*.sql`)
#   share the same version — and it only discovers the clash at BOOT time, long
#   after the offending PR merged. #1157 was exactly this: a second `V34__*.sql`
#   landed on trunk, compiled green, passed `gradle check` + every unit test,
#   and only exploded when the rig tried to boot ("Found more than one
#   migration with version 34"). The rig was undeployable until someone
#   renumbered it to V41. Nothing in the repo caught it pre-merge.
#
#   This script closes that gap. It is a distinct sub-check of `task ci:verify`
#   (see Taskfile.yml `verify:migrations`) so a colliding version fails the PR
#   gate with the offending version named — not an opaque boot-time stack trace.
#
# What it does:
#   1. Scans one or more Flyway migration directories for `V<version>__*.sql`.
#   2. Normalises the version token: Flyway treats `_` and `.` as equivalent
#      separators, so `V15_1` and `V15.1` are the SAME version "15.1", while
#      `V15` is version "15" (distinct). We normalise `_` → `.` before
#      comparison so the check matches Flyway's own resolver.
#   3. Repeatable migrations (`R__*.sql`) have no version — they are ignored.
#   4. If two files resolve to the same version, exit 1 and name the version +
#      every file that claims it.
#
# Why scan ALL dirs together:
#   Flyway loads every configured location into ONE schema-history namespace,
#   so versions must be unique ACROSS the combined set, not just within one
#   directory. This repo ships two locations (the base `migration/` dir and the
#   Postgres-specific `migration-postgresql/` dir) — both are on the classpath
#   at boot, so both are scanned together by default.
#
# Usage:
#   check-duplicate-migrations.sh [dir ...]
#   # default dirs (when none given): the two titan-db-core Flyway locations.
#
# Exit codes:
#   0 — no duplicate versions found
#   1 — at least one version is claimed by >1 file (names them)
#   2 — config error (a requested dir does not exist)
set -euo pipefail

# Resolve the repo root from this script's location so default dirs work no
# matter the caller's CWD (the loop runner invokes tasks from varied dirs).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

DEFAULT_DIRS=(
  "$REPO_ROOT/titan-db-core/src/main/resources/io/adaptiq/titan/db/migration"
  "$REPO_ROOT/titan-db-core/src/main/resources/io/adaptiq/titan/db/migration-postgresql"
)

if [ "$#" -gt 0 ]; then
  DIRS=("$@")
else
  DIRS=("${DEFAULT_DIRS[@]}")
fi

for d in "${DIRS[@]}"; do
  if [ ! -d "$d" ]; then
    echo "[dup-migration] migration dir not found: $d" >&2
    exit 2
  fi
done

# Collect "version<TAB>filepath" rows for every versioned migration.
# - Match only files whose basename starts with `V` followed by a version
#   token, then `__`. Repeatable (`R__`) and any non-migration file is skipped.
# - Normalise the version: strip the leading `V`, take everything before the
#   first `__`, then translate `_` → `.` so 15_1 and 15.1 compare equal.
ROWS=""
for d in "${DIRS[@]}"; do
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    base="$(basename "$f")"
    # Pull the version token between the leading V and the `__` separator.
    raw="$(printf '%s' "$base" | sed -nE 's/^V([0-9][0-9_.]*)__.*\.sql$/\1/p')"
    [ -n "$raw" ] || continue
    version="$(printf '%s' "$raw" | tr '_' '.')"
    ROWS="${ROWS}${version}	${f}
"
  done < <(find "$d" -maxdepth 1 -type f -name 'V*__*.sql' 2>/dev/null | sort)
done

if [ -z "$ROWS" ]; then
  echo "[dup-migration] no versioned migrations found under: ${DIRS[*]}" >&2
  # An empty set is not a duplicate; treat as clean (exit 0). A genuinely
  # mis-pointed dir surfaces as exit 2 above.
  echo "[dup-migration] OK: no versioned migrations to check."
  exit 0
fi

# Find versions that appear more than once.
DUP_VERSIONS="$(printf '%s' "$ROWS" | grep -v '^$' | cut -f1 | sort | uniq -d || true)"

if [ -z "$DUP_VERSIONS" ]; then
  TOTAL="$(printf '%s' "$ROWS" | grep -cv '^$' || true)"
  echo "[dup-migration] OK: $TOTAL versioned migrations, all versions unique."
  exit 0
fi

echo "[dup-migration] FAIL: duplicate Flyway migration version(s) detected." >&2
echo "[dup-migration] Flyway will REFUSE to boot with these (see #1157). Renumber the newer file." >&2
while IFS= read -r v; do
  [ -n "$v" ] || continue
  echo "  version $v is claimed by:" >&2
  printf '%s' "$ROWS" | grep -v '^$' | awk -F'\t' -v ver="$v" '$1==ver {print "    "$2}' >&2
done <<< "$DUP_VERSIONS"
exit 1
