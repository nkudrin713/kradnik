#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/verify-postgres-recovery-set.sh <recovery-directory>

Runs a restore drill in an isolated temporary PostgreSQL container.
USAGE
}

if [ "$#" -ne 1 ]; then
  usage >&2
  exit 1
fi

recovery_directory_input="$1"
case "$recovery_directory_input" in
  /*) ;;
  *) echo "Recovery directory must be absolute" >&2; exit 1 ;;
esac

recovery_directory="$(cd "$recovery_directory_input" && pwd -P)"
test ! -e "$recovery_directory/INCOMPLETE"
test -s "$recovery_directory/database.dump"
test -s "$recovery_directory/database.list"
test -s "$recovery_directory/deploy.env"
test -s "$recovery_directory/docker-compose.yml"
test -s "$recovery_directory/globals.sql"
test -s "$recovery_directory/metadata.env"
test -s "$recovery_directory/SHA256SUMS"

if command -v sha256sum >/dev/null 2>&1; then
  (cd "$recovery_directory" && sha256sum --check SHA256SUMS)
else
  (cd "$recovery_directory" && shasum -a 256 --check SHA256SUMS)
fi

container_name="kradnik-restore-drill-$$-$RANDOM"
cleanup() {
  docker rm -f "$container_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run --detach \
  --name "$container_name" \
  --env POSTGRES_PASSWORD=restore-drill \
  postgres:17-alpine >/dev/null

ready=false
for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
  if docker exec "$container_name" pg_isready -U postgres >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 2
done

if [ "$ready" != "true" ]; then
  echo "Temporary PostgreSQL did not become ready" >&2
  exit 1
fi

docker exec "$container_name" createdb -U postgres kradnik_restore
docker exec -i "$container_name" pg_restore \
  -U postgres \
  -d kradnik_restore \
  --exit-on-error \
  --no-acl \
  --no-owner \
  < "$recovery_directory/database.dump"

flyway_version="$(
  docker exec "$container_name" psql \
    -U postgres \
    -d kradnik_restore \
    -Atc 'SELECT version FROM flyway_schema_history WHERE success = true AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1'
)"
test -n "$flyway_version"

expected_flyway_version="$(sed -n 's/^flyway_version=//p' "$recovery_directory/metadata.env")"
test -n "$expected_flyway_version"
test "$flyway_version" = "$expected_flyway_version"

printf 'Restore drill passed. Flyway version: %s\n' "$flyway_version"
