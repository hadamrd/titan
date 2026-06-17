#!/usr/bin/env bash
# find-hot-files.sh — surface god-objects mechanically by churn.
#
# A file touched by many PRs over a short window is doing too much. This is
# the operational signal design 67 ("god-object decomposition") relies on to
# pick the next extraction target without guessing.
#
# Usage:
#   bash dev/git-hooks/find-hot-files.sh                  # last 30 days
#   bash dev/git-hooks/find-hot-files.sh --since=14.days  # custom window
#   bash dev/git-hooks/find-hot-files.sh --top=20         # custom row cap
#
# Output: tab-separated `<commits>\t<file>`, top N sorted descending.
set -euo pipefail

SINCE="${SINCE:-30.days}"
TOP="${TOP:-15}"

for arg in "$@"; do
  case "$arg" in
    --since=*) SINCE="${arg#*=}" ;;
    --top=*)   TOP="${arg#*=}" ;;
    *) echo "unknown arg: $arg" >&2; exit 2 ;;
  esac
done

git log --since="$SINCE" --name-only --pretty=format: -- '*.java' \
  | grep -v '^$' \
  | sort \
  | uniq -c \
  | sort -rn \
  | head -"$TOP" \
  | awk '{printf "%5d\t%s\n", $1, $2}'
