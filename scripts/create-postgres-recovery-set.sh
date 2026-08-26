#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/create-postgres-recovery-set.sh <compose-directory> <backup-root>

Both paths must be absolute. The backup root must be outside the compose directory.
USAGE
}

if [ "$#" -ne 2 ]; then
  usage >&2
  exit 1
fi

compose_directory_input="$1"
backup_root_input="$2"

case "$compose_directory_input" in
  /*) ;;
  *) echo "Compose directory must be absolute" >&2; exit 1 ;;
esac

case "$backup_root_input" in
  /*) ;;
  *) echo "Backup root must be absolute" >&2; exit 1 ;;
esac

test "$compose_directory_input" != "/"
test "$backup_root_input" != "/"
test -f "$compose_directory_input/docker-compose.yml"
test -f "$compose_directory_input/.env"

compose_directory="$(cd "$compose_directory_input" && pwd -P)"
mkdir -p "$backup_root_input"
backup_root="$(cd "$backup_root_input" && pwd -P)"

if [ "$backup_root" = "$compose_directory" ] || [[ "$backup_root" == "$compose_directory/"* ]]; then
  echo "Backup root must be outside the compose directory" >&2
  exit 1
fi

umask 077
timestamp="$(date -u +'%Y%m%dT%H%M%SZ')"
recovery_directory="$backup_root/kradnik-postgres-$timestamp"
mkdir -m 700 "$recovery_directory"
touch "$recovery_directory/INCOMPLETE"

postgres_container="$(cd "$compose_directory" && docker compose ps -q postgres)"
if [ -z "$postgres_container" ]; then
  echo "PostgreSQL container is not running" >&2
  exit 1
fi

if [ "$(docker inspect -f '{{.State.Status}}' "$postgres_container")" != "running" ]; then
  echo "PostgreSQL container is not running" >&2
  exit 1
fi

database_name="$(docker exec "$postgres_container" sh -lc 'printf %s "$POSTGRES_DB"')"
database_user="$(docker exec "$postgres_container" sh -lc 'printf %s "$POSTGRES_USER"')"

docker exec "$postgres_container" sh -lc \
  'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' \
  > "$recovery_directory/database.dump"

docker exec "$postgres_container" sh -lc \
  'exec pg_dumpall -U "$POSTGRES_USER" --globals-only' \
  > "$recovery_directory/globals.sql"

docker exec -i "$postgres_container" pg_restore --list \
  < "$recovery_directory/database.dump" \
  > "$recovery_directory/database.list"

flyway_version="$(
  docker exec "$postgres_container" sh -lc \
    'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT version FROM flyway_schema_history WHERE success = true AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1"'
)"

app_container="$(cd "$compose_directory" && docker compose ps -q app)"
app_image="unknown"
if [ -n "$app_container" ]; then
  app_image="$(docker inspect -f '{{.Config.Image}}' "$app_container")"
fi

cp "$compose_directory/docker-compose.yml" "$recovery_directory/docker-compose.yml"
cp "$compose_directory/.env" "$recovery_directory/deploy.env"
chmod 600 "$recovery_directory/docker-compose.yml" "$recovery_directory/deploy.env"

{
  printf 'created_at_utc=%s\n' "$timestamp"
  printf 'database_name=%s\n' "$database_name"
  printf 'database_user=%s\n' "$database_user"
  printf 'flyway_version=%s\n' "$flyway_version"
  printf 'app_image=%s\n' "$app_image"
} > "$recovery_directory/metadata.env"

(
  cd "$recovery_directory"
  sha256sum database.dump database.list deploy.env docker-compose.yml globals.sql metadata.env > SHA256SUMS
)

rm "$recovery_directory/INCOMPLETE"
printf 'Recovery set created: %s\n' "$recovery_directory"
printf 'Copy this directory outside the VPS before continuing the release.\n'
