#!/usr/bin/env bash
#
# Merges upstream into the fork branch.
#
# Upstream ships prereleases (v0.19.0-beta.7, and release candidates) as real tags, and those count
# as versions to sync. Syncing every tag -- beta included -- keeps each merge small; skipping to the
# next stable means merging a whole release cycle at once, which is where conflicts pile up.
#
# Usage:
#   scripts/fork/sync-upstream.sh                 # newest upstream tag, prereleases included
#   scripts/fork/sync-upstream.sh v0.20.0-beta.1  # a specific tag
#   scripts/fork/sync-upstream.sh master          # upstream/master tip
#   scripts/fork/sync-upstream.sh --list          # show recent upstream tags and exit

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

# Newest tag by tag date rather than version sort: semver orders v0.19.0-beta.7 *before* v0.19.0,
# but chronologically the beta came after, and what we want is "the next thing upstream published".
latest_upstream_tag() {
	git tag -l 'v*' --sort=-creatordate | head -n 1
}

list_upstream_tags() {
	git for-each-ref --sort=-creatordate --count=15 \
		--format='  %(refname:short)%09%(creatordate:short)' 'refs/tags/v*'
}

echo "Fetching upstream..."
git fetch upstream --tags --quiet

if [ "${1:-}" = "--list" ]; then
	echo "Recent upstream tags (prereleases included):"
	list_upstream_tags
	exit 0
fi

REF="${1:-$(latest_upstream_tag)}"
[ "$REF" = "master" ] && REF="upstream/master"

if [ -z "$REF" ]; then
	echo "Could not determine an upstream ref to merge." >&2
	exit 1
fi

if ! git rev-parse --verify --quiet "$REF^{commit}" >/dev/null; then
	echo "Ref '$REF' not found. Try: scripts/fork/sync-upstream.sh --list" >&2
	exit 1
fi

if [ -n "$(git status --porcelain)" ]; then
	echo "Working tree is dirty. Commit or stash first." >&2
	exit 1
fi

# rerere replays previously resolved conflicts, which is most of the value of merging over rebasing.
git config rerere.enabled true
git config merge.conflictstyle zdiff3

# Keep the pristine mirror in step so `master` stays a clean copy of upstream.
git fetch upstream master:master 2>/dev/null || \
	echo "  (could not fast-forward local master; it has diverged from upstream)"

if git merge-base --is-ancestor "$REF" HEAD; then
	echo "Already up to date with $REF."
	exit 0
fi

echo "Merging $REF into $(git rev-parse --abbrev-ref HEAD)..."
if git merge --no-edit "$REF"; then
	echo
	echo "Merged cleanly."
else
	echo
	echo "Conflicts to resolve. Files carrying a '// FORK:' marker are our deliberate edits --"
	echo "keep the fork side and re-apply it onto upstream's new code."
	echo "Everything else should almost always take upstream's version."
	exit 1
fi

echo
scripts/fork/diff-budget.sh "$REF" || true

cat <<'EOF'

Next:
  ./gradlew :app:assembleForkDebug
EOF
