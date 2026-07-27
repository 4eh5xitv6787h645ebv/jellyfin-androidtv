#!/usr/bin/env bash
#
# Reports how much of upstream this fork modifies.
#
# Merge cost tracks the number of upstream lines we change, so this is the number to watch. Files
# the fork owns outright (fork/, app/src/fork/, ...) are excluded -- they cannot conflict.
#
# Usage: scripts/fork/diff-budget.sh [base-ref]   (default: upstream/master)

set -euo pipefail

BASE="${1:-upstream/master}"
FILE_BUDGET="${FORK_FILE_BUDGET:-20}"

cd "$(git rev-parse --show-toplevel)"

if ! git rev-parse --verify --quiet "$BASE" >/dev/null; then
	echo "Base ref '$BASE' not found. Try: git fetch upstream" >&2
	exit 1
fi

# Paths the fork owns. Changes here are additive and never conflict.
FORK_OWNED=(
	':(exclude)fork'
	':(exclude)app/src/fork'
	':(exclude)FORK.md'
	':(exclude)scripts/fork'
	':(exclude).github/workflows/fork-*.yml'
)

MERGE_BASE="$(git merge-base "$BASE" HEAD)"

mapfile -t CHANGED < <(git diff --name-only "$MERGE_BASE" HEAD -- . "${FORK_OWNED[@]}")
FILE_COUNT="${#CHANGED[@]}"

echo "Upstream diff budget (base: $BASE)"
echo

if [ "$FILE_COUNT" -eq 0 ]; then
	echo "  No upstream files modified."
else
	git diff --stat "$MERGE_BASE" HEAD -- . "${FORK_OWNED[@]}" | sed 's/^/  /'
fi

echo
echo "  Upstream files touched: $FILE_COUNT / $FILE_BUDGET"

# Every deliberate edit is supposed to carry a marker. Anything unmarked is drift.
UNMARKED=()
for file in "${CHANGED[@]}"; do
	[ -f "$file" ] || continue
	if ! grep -q "FORK:\|// FORK\|# FORK" "$file" 2>/dev/null; then
		UNMARKED+=("$file")
	fi
done

if [ "${#UNMARKED[@]}" -gt 0 ]; then
	echo
	echo "  Modified but missing a FORK marker:"
	printf '    %s\n' "${UNMARKED[@]}"
fi

if [ "$FILE_COUNT" -gt "$FILE_BUDGET" ]; then
	echo
	echo "  Over budget. Look for a seam being missed -- see FORK.md." >&2
	exit 1
fi
