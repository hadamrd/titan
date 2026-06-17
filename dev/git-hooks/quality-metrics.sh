#!/usr/bin/env bash
# quality-metrics.sh — complexity-based quality signals beyond LOC.
#
# Motivation: docs/design/71-complexity-metrics.md and issue #909.
# A 700-line cap is a crude proxy. The real signals are:
#   - Cyclomatic complexity per method (McCabe, branch-count heuristic).
#   - Collaborator / responsibility count per class (DI fields).
#   - Test-to-source LOC ratio (sad-path coverage hint).
#   - Fan-in (how many other files import this one).
#   - Churn (delegated to find-hot-files.sh).
#
# Usage:
#   bash dev/git-hooks/quality-metrics.sh report              # full repo report (markdown)
#   bash dev/git-hooks/quality-metrics.sh check FILE...       # soft-warn on listed files
#   bash dev/git-hooks/quality-metrics.sh hot                 # churn x complexity intersection
#
# Thresholds (rationale in docs/design/71-complexity-metrics.md):
#   CC_WARN_PER_METHOD  = 10   # McCabe; >10 is "needs refactor" per industry consensus
#   COLLAB_WARN         = 7    # constructor-injected fields; >7 = too many concerns
#   TEST_RATIO_WARN     = 0.5  # test LOC / source LOC; <0.5 = under-tested
#   FAN_IN_WARN         = 25   # imported-by; >25 = central god-import
#
# Output is informational by default. Exits non-zero only with --strict
# (used by nightly CI report, not by per-commit gate).

set -euo pipefail

CC_WARN_PER_METHOD="${CC_WARN_PER_METHOD:-10}"
COLLAB_WARN="${COLLAB_WARN:-7}"
TEST_RATIO_WARN="${TEST_RATIO_WARN:-0.5}"
FAN_IN_WARN="${FAN_IN_WARN:-25}"
TOP="${TOP:-20}"

STRICT=0
MODE=""
FILES=()
for arg in "$@"; do
  case "$arg" in
    --strict) STRICT=1 ;;
    --top=*)  TOP="${arg#*=}" ;;
    report|check|hot) MODE="$arg" ;;
    -h|--help)
      sed -n '2,30p' "$0"
      exit 0
      ;;
    *) FILES+=("$arg") ;;
  esac
done

[[ -z "$MODE" ]] && MODE="report"

# ---------- helpers ----------

# Cyclomatic complexity (McCabe) per method, by branch-keyword count.
# We don't parse Java; we use a robust-enough heuristic:
#   start at 1, add 1 for each of: if/else if/for/while/case/catch/&&/||/?:
# Returns: prints "<max-cc>\t<method-count>" for the file.
cc_for_file() {
  local f="$1"
  # Two-pass via awk: first prepend a sentinel " " to every line so word-boundary
  # detection works with [^A-Za-z0-9_] anchors (POSIX awk has no \b).
  awk '
    function bcount(s, pat,   t, n) { t = s; n = gsub(pat, "", t); return n }
    BEGIN {
      depth=0; in_method=0; method_depth=0; max_cc=0; method_count=0; cc=1
      P_IF    = "[^A-Za-z0-9_]if[ \t]*\\("
      P_FOR   = "[^A-Za-z0-9_]for[ \t]*\\("
      P_WHILE = "[^A-Za-z0-9_]while[ \t]*\\("
      P_CASE  = "[^A-Za-z0-9_]case[ \t]+"
      P_CATCH = "[^A-Za-z0-9_]catch[ \t]*\\("
      P_AND   = "&&"
      P_OR    = "\\|\\|"
      P_OPEN  = "\\{"
      P_CLOSE = "\\}"
      P_SIG   = "\\([^;]*\\)[ \t]*(throws[^{]*)?\\{[ \t]*$"
      P_KW    = "[^A-Za-z0-9_](class|interface|enum|record|new)[^A-Za-z0-9_]"
      P_COMM  = "^[ \t]*//"
    }
    {
      line = " " $0 " "
      sub(/\/\/.*$/, "", line)
      open  = bcount(line, P_OPEN)
      close_= bcount(line, P_CLOSE)
    }
    !in_method && line ~ P_SIG && line !~ P_KW && line !~ P_COMM {
        in_method = 1
        cc = 1
        method_depth = depth + open - close_
        method_count++
        depth += open - close_
        next
    }
    in_method {
      cc += bcount(line, P_IF)
      cc += bcount(line, P_FOR)
      cc += bcount(line, P_WHILE)
      cc += bcount(line, P_CASE)
      cc += bcount(line, P_CATCH)
      cc += bcount(line, P_AND)
      cc += bcount(line, P_OR)
      depth += open - close_
      if (depth < method_depth) {
        if (cc > max_cc) max_cc = cc
        in_method = 0
      }
      next
    }
    { depth += open - close_ }
    END { printf "%d\t%d\n", max_cc, method_count }
  ' "$f"
}

# Collaborator count: distinct constructor-injected / @Inject / private final fields
# of reference type (not primitive). Heuristic but reliable on this codebase
# (we use constructor injection consistently).
collab_count() {
  local f="$1"
  # Match "private final <Type> <name>;" where Type starts with uppercase.
  local n
  n=$(grep -cE '^\s*(private|protected)\s+final\s+[A-Z][A-Za-z0-9_<>?, ]*\s+[a-z][A-Za-z0-9_]*\s*[;=]' "$f" 2>/dev/null || true)
  echo "${n:-0}"
}

# Test:source LOC ratio. Looks for a parallel test file under src/test/.
test_ratio() {
  local f="$1"
  # f like titan-server/src/main/java/io/adaptiq/.../Foo.java
  # test:  titan-server/src/test/java/io/adaptiq/.../FooTest.java (or *Tests, *IT)
  local rel base src_lines test_lines=0
  base="$(basename "$f" .java)"
  # Substitute /src/main/ → /src/test/ ; handle both absolute and relative paths
  # by also matching a leading "src/main/" with no slash prefix.
  rel="${f/\/src\/main\//\/src\/test\/}"
  rel="${rel/#src\/main\//src\/test\/}"
  rel="$(dirname "$rel")"
  for cand in "$rel/${base}Test.java" "$rel/${base}Tests.java" "$rel/${base}IT.java" "$rel/${base}PropertyTest.java"; do
    [[ -f "$cand" ]] && test_lines=$(( test_lines + $(wc -l <"$cand") ))
  done
  local rel2="${f/\/src\/main\//\/src\/integrationTest\/}"
  rel2="${rel2/#src\/main\//src\/integrationTest\/}"
  rel2="$(dirname "$rel2")"
  for cand in "$rel2/${base}Test.java" "$rel2/${base}IT.java"; do
    [[ -f "$cand" ]] && test_lines=$(( test_lines + $(wc -l <"$cand") ))
  done
  src_lines=$(wc -l <"$f")
  if (( src_lines == 0 )); then
    echo "0.00"
  else
    awk -v t="$test_lines" -v s="$src_lines" 'BEGIN { printf "%.2f", t/s }'
  fi
}

# Fan-in: how many other .java files import this class.
# For per-file (check mode) we count by simple name; expensive O(N²) for large
# scans, so the `report` mode precomputes a fan-in map up front and uses it.
fan_in() {
  local f="$1"
  if [[ -n "${FANIN_MAP:-}" && -f "$FANIN_MAP" ]]; then
    local key
    key="$(fanin_key "$f")"
    awk -F'\t' -v k="$key" '$1==k {print $2; found=1} END {if (!found) print 0}' "$FANIN_MAP"
    return
  fi
  local cls n
  cls="$(basename "$f" .java)"
  n=$( { grep -rlE "\b${cls}\b" --include='*.java' titan-*/src 2>/dev/null || true; } \
       | { grep -vFx "$f" || true; } \
       | wc -l )
  echo "${n:-0}"
}

# Fan-in cache key: the fully-qualified class name (package + ClassName) so
# we don't conflate two classes with the same simple name in different packages.
fanin_key() {
  local f="$1"
  local pkg
  pkg=$(awk '/^package /{ sub(/;.*/,"",$2); print $2; exit }' "$f")
  echo "${pkg}.$(basename "$f" .java)"
}

# Build fan-in map: count import-references to each fully-qualified class name.
build_fanin_map() {
  local out="$1"
  # Extract every "import X.Y.Z;" across product code, count occurrences.
  find titan-server titan-pipeline-model titan-worker titan-db-core titan-step-api titan-trigger-api titan-extensions titan-secrets-api \
    -path '*/src/*' -name '*.java' 2>/dev/null \
    -exec awk '/^import /{ sub(/;.*/,"",$2); print $2 }' {} + \
    | sort | uniq -c \
    | awk '{ printf "%s\t%d\n", $2, $1 }' >"$out"
}

# Per-file metrics tuple.
file_metrics() {
  local f="$1"
  local loc cc_method_count cc methods collab ratio fanin
  loc=$(wc -l <"$f")
  read -r cc methods < <(cc_for_file "$f")
  collab=$(collab_count "$f")
  ratio=$(test_ratio "$f")
  fanin=$(fan_in "$f")
  printf "%s\t%d\t%d\t%d\t%d\t%s\t%d\n" "$f" "$loc" "$cc" "$methods" "$collab" "$ratio" "$fanin"
}

# ---------- modes ----------

mode_check() {
  local fail=0
  for f in "${FILES[@]}"; do
    [[ "$f" == *.java ]] || continue
    [[ -f "$f" ]] || continue
    [[ "$f" == */src/test/* || "$f" == */src/integrationTest/* ]] && continue
    local loc cc methods collab ratio fanin
    read -r _ loc cc methods collab ratio fanin < <(file_metrics "$f")
    local warned=0
    if (( cc > CC_WARN_PER_METHOD )); then
      printf '\033[33mquality-metrics: %s — method cyclomatic complexity %d (warn>%d). Extract branches.\033[0m\n' \
        "$f" "$cc" "$CC_WARN_PER_METHOD" >&2
      warned=1
    fi
    if (( collab > COLLAB_WARN )); then
      printf '\033[33mquality-metrics: %s — %d collaborators injected (warn>%d). Class likely has too many concerns; see docs/design/67-god-object-decomposition.md.\033[0m\n' \
        "$f" "$collab" "$COLLAB_WARN" >&2
      warned=1
    fi
    if awk -v r="$ratio" -v t="$TEST_RATIO_WARN" 'BEGIN { exit !(r+0 < t+0) }'; then
      printf '\033[33mquality-metrics: %s — test:source LOC ratio %s (warn<%s). Add adversarial tests before extending.\033[0m\n' \
        "$f" "$ratio" "$TEST_RATIO_WARN" >&2
      warned=1
    fi
    if (( fanin > FAN_IN_WARN )); then
      printf '\033[33mquality-metrics: %s — fan-in %d (warn>%d). Central type; changes here ripple wide.\033[0m\n' \
        "$f" "$fanin" "$FAN_IN_WARN" >&2
      warned=1
    fi
    (( warned )) && fail=1
  done
  if (( STRICT && fail )); then
    exit 1
  fi
  exit 0
}

mode_report() {
  echo "# Quality metrics report"
  echo
  echo "Thresholds: CC>${CC_WARN_PER_METHOD} · collaborators>${COLLAB_WARN} · test-ratio<${TEST_RATIO_WARN} · fan-in>${FAN_IN_WARN}"
  echo
  echo "| File | LOC | maxCC | methods | collab | test/src | fan-in |"
  echo "|---|---:|---:|---:|---:|---:|---:|"
  local tmp
  tmp=$(mktemp)
  FANIN_MAP=$(mktemp)
  build_fanin_map "$FANIN_MAP"
  export FANIN_MAP
  # Limit scope to product .java under src/main/.
  while IFS= read -r f; do
    file_metrics "$f"
  done < <(find titan-server titan-pipeline-model titan-worker titan-db-core titan-step-api titan-trigger-api titan-extensions titan-secrets-api -path '*/src/main/java/*' -name '*.java' 2>/dev/null) \
    | sort -t$'\t' -k3 -rn \
    | head -"$TOP" >"$tmp"

  while IFS=$'\t' read -r f loc cc methods collab ratio fanin; do
    local flag=""
    (( cc > CC_WARN_PER_METHOD )) && flag="${flag}⚠cc "
    (( collab > COLLAB_WARN )) && flag="${flag}⚠col "
    awk -v r="$ratio" -v t="$TEST_RATIO_WARN" 'BEGIN { exit !(r+0 < t+0) }' && flag="${flag}⚠test "
    (( fanin > FAN_IN_WARN )) && flag="${flag}⚠fanin "
    printf "| %s | %d | %d | %d | %d | %s | %d | %s\n" \
      "$f" "$loc" "$cc" "$methods" "$collab" "$ratio" "$fanin" "$flag"
  done <"$tmp"
  rm -f "$tmp" "$FANIN_MAP"
}

mode_hot() {
  echo "# Hot AND complex"
  echo
  echo "Cross-references find-hot-files.sh (churn) with current complexity."
  echo "Files appearing here are high-leverage decomposition targets."
  echo
  local hot_tmp metric_tmp
  hot_tmp=$(mktemp); metric_tmp=$(mktemp)
  bash "$(dirname "$0")/find-hot-files.sh" --top=30 >"$hot_tmp" 2>/dev/null || true
  echo "| File | churn | LOC | maxCC | collab |"
  echo "|---|---:|---:|---:|---:|"
  while read -r commits f; do
    [[ -z "$f" || ! -f "$f" ]] && continue
    [[ "$f" == */src/main/java/* ]] || continue
    local loc cc methods collab _r _fi
    read -r _ loc cc methods collab _r _fi < <(file_metrics "$f")
    printf "| %s | %s | %d | %d | %d |\n" "$f" "$commits" "$loc" "$cc" "$collab"
  done <"$hot_tmp"
  rm -f "$hot_tmp" "$metric_tmp"
}

case "$MODE" in
  check)  mode_check ;;
  report) mode_report ;;
  hot)    mode_hot ;;
  *) echo "unknown mode: $MODE" >&2; exit 2 ;;
esac
