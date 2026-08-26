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

if [ -z "$description" ]; then
  echo "Release description must not be empty" >&2
  usage >&2
  exit 1
fi

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

if [ "$(git branch --show-current)" != "main" ]; then
  echo "Expected branch: main" >&2
  exit 1
fi

if [ -n "$(git status --porcelain)" ]; then
  echo "Working tree must be clean" >&2
  git status --short >&2
  exit 1
fi

if git remote get-url origin >/dev/null 2>&1; then
  git fetch --prune --tags origin \
    '+refs/heads/main:refs/remotes/origin/main' \
    >/dev/null
fi

main_sha="$(git rev-parse origin/main)"
head_sha="$(git rev-parse HEAD)"

if [ "$head_sha" != "$main_sha" ]; then
  echo "Local main must match origin/main" >&2
  echo "HEAD:        $head_sha" >&2
  echo "origin/main: $main_sha" >&2
  exit 1
fi

latest_tag="$(git tag -l 'v[0-9]*.[0-9]*.[0-9]*' --sort=-v:refname | head -n 1)"
if [ -n "$latest_tag" ]; then
  scope_base="$latest_tag"
else
  scope_base="$(git hash-object -t tree /dev/null)"
fi

echo "Release scope:"
echo "  from: ${latest_tag:-initial commit}"
echo "  to:   $main_sha"
git diff --stat "$scope_base" HEAD

echo
echo "Flyway changes:"
flyway_changes="$(
  git diff --name-only --diff-filter=ACMR "$scope_base" HEAD -- \
    'src/main/resources/db/migration/*.sql'
)"
if [ -n "$flyway_changes" ]; then
  printf '%s\n' "$flyway_changes"
else
  echo "  none"
fi

existing_target_tag="$(git tag --points-at "$head_sha" --list 'v[0-9]*.[0-9]*.[0-9]*' --sort=-v:refname | head -n 1)"
if [ -n "$existing_target_tag" ]; then
  next_version="$existing_target_tag"
elif [ -z "$latest_tag" ]; then
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

if [ -z "$existing_target_tag" ]; then
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
fi

echo
echo "Release preflight OK"
echo "Latest tag: ${latest_tag:-none}"
echo "Next version: $next_version"
echo "Target main SHA: $main_sha"
echo
echo "Create tag, GitHub Release, and deploy production:"
echo "  gh workflow run release.yml --ref main -f bump=$(shell_quote "$bump") -f description=$(shell_quote "$description")"
