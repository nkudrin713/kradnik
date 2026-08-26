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

if [ -n "$(git status --porcelain)" ]; then
  echo "Working tree must be clean" >&2
  git status --short >&2
  exit 1
fi

if git remote get-url origin >/dev/null 2>&1; then
  git fetch --prune --tags origin \
    '+refs/heads/main:refs/remotes/origin/main' \
    '+refs/heads/develop:refs/remotes/origin/develop' \
    >/dev/null
fi

develop_sha="$(git rev-parse origin/develop)"
main_sha="$(git rev-parse origin/main)"
head_sha="$(git rev-parse HEAD)"

if [ "$head_sha" != "$develop_sha" ]; then
  echo "Local develop must match origin/develop" >&2
  echo "HEAD:           $head_sha" >&2
  echo "origin/develop: $develop_sha" >&2
  exit 1
fi

echo "Release scope:"
echo "  origin/main:    $main_sha"
echo "  origin/develop: $develop_sha"
git diff --stat origin/main origin/develop

echo
echo "Flyway changes:"
flyway_changes="$(
  git diff --name-only --diff-filter=ACMR origin/main origin/develop -- \
    'src/main/resources/db/migration/*.sql'
)"
if [ -n "$flyway_changes" ]; then
  printf '%s\n' "$flyway_changes"
else
  echo "  none"
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
release_branch="release/$next_version"
release_body="Release $next_version

Source develop SHA: $develop_sha"

echo
echo "Release preflight OK"
echo "Latest tag: ${latest_tag:-none}"
echo "Next version: $next_version"
echo "Source develop SHA: $develop_sha"
echo
echo "Prepare release snapshot:"
echo "  git switch -c $(shell_quote "$release_branch") origin/main"
echo "  git restore --source=$(shell_quote "$develop_sha") --staged --worktree -- ."
echo "  git commit -m $(shell_quote "release: $release_title")"
echo "  test \"\$(git rev-parse HEAD^{tree})\" = \"\$(git rev-parse $(shell_quote "$develop_sha^{tree}"))\""
echo "  git push -u origin $(shell_quote "$release_branch")"
echo "  gh pr create --base main --head $(shell_quote "$release_branch") --title $(shell_quote "release: $release_title") --body $(shell_quote "$release_body") --label $(shell_quote "release:$bump")"
echo
echo "Deploy after release:"
echo "  gh workflow run deploy.yml --ref main -f environment=production -f version=$next_version"
