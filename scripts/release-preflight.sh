#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/release-preflight.sh [patch|minor|major] ["release description"]
USAGE
}

shell_quote() {
  printf '%q' "$1"
}

bump="${1:-patch}"
shift || true
description="$*"

case "$bump" in
  patch|minor|major)
    ;;
  -h|--help)
    usage
    exit 0
    ;;
  *)
    echo "Bump must be one of: patch, minor, major" >&2
    usage >&2
    exit 1
    ;;
esac

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

if [ "$(git branch --show-current)" != "develop" ]; then
  echo "Expected branch: develop" >&2
  exit 1
fi

if git remote get-url origin >/dev/null 2>&1; then
  git fetch --tags --prune-tags origin >/dev/null
fi

echo "Running local checks..."
./gradlew clean check jacocoTestReport bootJar

latest_tag="$(git tag -l 'v[0-9]*.[0-9]*.[0-9]*' --sort=-v:refname | head -n 1)"
if [ -z "$latest_tag" ]; then
  major=0
  minor=0
  patch=0
else
  version="${latest_tag#v}"
  major="${version%%.*}"
  rest="${version#*.}"
  minor="${rest%%.*}"
  patch="${rest#*.}"
fi

case "$bump" in
  patch)
    patch=$((patch + 1))
    ;;
  minor)
    minor=$((minor + 1))
    patch=0
    ;;
  major)
    major=$((major + 1))
    minor=0
    patch=0
    ;;
esac

next_version="v${major}.${minor}.${patch}"
release_title="${description:-<short description>}"

echo
echo "Release preflight OK"
echo "Latest tag: ${latest_tag:-none}"
echo "Next version: $next_version"
echo
echo "Working tree:"
git status --short
echo
echo "PR:"
echo "  gh pr create --base main --head develop --title $(shell_quote "release: $release_title") --body $(shell_quote "Release $next_version") --label $(shell_quote "release:$bump")"
echo
echo "Deploy after release:"
echo "  gh workflow run deploy.yml --ref main -f environment=production -f version=$next_version"
