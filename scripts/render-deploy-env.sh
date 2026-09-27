#!/usr/bin/env bash

set -euo pipefail

app_image="${1:?Usage: render-deploy-env.sh <app-image>}"

: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"
: "${TELEGRAM_BOT_TOKEN:?TELEGRAM_BOT_TOKEN is required}"
: "${TELEGRAM_BOT_API_IMAGE:?TELEGRAM_BOT_API_IMAGE is required}"
: "${YOUTUBE_PO_TOKEN_PROVIDER_IMAGE:?YOUTUBE_PO_TOKEN_PROVIDER_IMAGE is required}"

admin_enabled="${ADMIN_ENABLED:-false}"
admin_username="${ADMIN_USERNAME:-}"
admin_password_hash="${ADMIN_PASSWORD_HASH:-}"
admin_public_port="${ADMIN_PUBLIC_PORT:-8080}"
admin_cookie_secure="${ADMIN_COOKIE_SECURE:-false}"

case "$admin_enabled:$admin_cookie_secure" in
  true:true|true:false|false:true|false:false) ;;
  *) echo 'ADMIN_ENABLED and ADMIN_COOKIE_SECURE must be true or false' >&2; exit 1 ;;
esac
if [ -n "$admin_username" ] && ! [[ "$admin_username" =~ ^[a-zA-Z0-9_.@-]+$ ]]; then
  echo 'ADMIN_USERNAME must contain only letters, digits, underscore, dot, @ or hyphen' >&2
  exit 1
fi
if [ -n "$admin_password_hash" ] && ! printf '%s\n' "$admin_password_hash" | grep -Eq '^\$2[aby]\$1[0-4]\$[./A-Za-z0-9]{53}$'; then
  echo 'ADMIN_PASSWORD_HASH must be a BCrypt hash with cost 10 through 14' >&2
  exit 1
fi
if [ "$admin_enabled" = true ] && { [ -z "$admin_username" ] || [ -z "$admin_password_hash" ]; }; then
  echo 'ADMIN_USERNAME and ADMIN_PASSWORD_HASH are required when admin is enabled' >&2
  exit 1
fi
if ! [[ "$admin_public_port" =~ ^[1-9][0-9]{0,4}$ ]] || [ "$admin_public_port" -gt 65535 ]; then
  echo 'ADMIN_PUBLIC_PORT must be an integer from 1 through 65535' >&2
  exit 1
fi

telegram_local_api_enabled="${TELEGRAM_LOCAL_API_ENABLED:-false}"
case "$telegram_local_api_enabled" in
  true|false) ;;
  *) echo 'TELEGRAM_LOCAL_API_ENABLED must be true or false' >&2; exit 1 ;;
esac

youtube_po_token_provider_enabled="${YOUTUBE_PO_TOKEN_PROVIDER_ENABLED:-false}"
case "$youtube_po_token_provider_enabled" in
  true|false) ;;
  *) echo 'YOUTUBE_PO_TOKEN_PROVIDER_ENABLED must be true or false' >&2; exit 1 ;;
esac

if [ "$telegram_local_api_enabled" = "true" ]; then
  : "${TELEGRAM_API_ID:?TELEGRAM_API_ID is required for local Bot API}"
  : "${TELEGRAM_API_HASH:?TELEGRAM_API_HASH is required for local Bot API}"
  telegram_bot_api_url="${TELEGRAM_BOT_API_URL:-http://telegram-bot-api:8081/bot}"
  telegram_bot_file_api_url="${TELEGRAM_BOT_FILE_API_URL:-http://telegram-bot-api:8081/file/bot}"
  telegram_max_upload_bytes="${TELEGRAM_MAX_UPLOAD_BYTES:-2000000000}"
  download_work_dir="/var/lib/kradnik/media"
else
  telegram_bot_api_url="${TELEGRAM_BOT_API_URL:-https://api.telegram.org/bot}"
  telegram_bot_file_api_url="${TELEGRAM_BOT_FILE_API_URL:-https://api.telegram.org/file/bot}"
  telegram_max_upload_bytes="${TELEGRAM_MAX_UPLOAD_BYTES:-47185920}"
  download_work_dir="/tmp/kradnik-downloads"
fi

if [ "$youtube_po_token_provider_enabled" = "true" ]; then
  youtube_po_token_provider_url="http://youtube-pot-provider:4416"
else
  youtube_po_token_provider_url=""
fi

app_container_name="kradnik-prod-app"
postgres_container_name="kradnik-prod-postgres"
postgres_db="kradnik_prod"
postgres_volume_name="kradnik-prod-postgres-data"
postgres_public_port="5434"
telegram_bot_api_container_name="kradnik-prod-telegram-bot-api"
youtube_po_token_provider_container_name="kradnik-prod-youtube-pot-provider"
telegram_bot_api_data_volume_name="kradnik-prod-telegram-bot-api-data"
media_work_volume_name="kradnik-prod-media-work"

printf 'ADMIN_ENABLED=%s\n' "$admin_enabled"
printf 'ADMIN_USERNAME=%s\n' "$admin_username"
# Compose must receive literal dollar signs from the BCrypt hash.
printf "ADMIN_PASSWORD_HASH='%s'\n" "$admin_password_hash"
printf 'ADMIN_PUBLIC_PORT=%s\n' "$admin_public_port"
printf 'ADMIN_COOKIE_SECURE=%s\n' "$admin_cookie_secure"
printf 'APP_IMAGE=%s\n' "$app_image"
printf 'APP_CONTAINER_NAME=%s\n' "$app_container_name"
printf 'POSTGRES_CONTAINER_NAME=%s\n' "$postgres_container_name"
printf 'POSTGRES_DB=%s\n' "$postgres_db"
printf 'POSTGRES_USER=kradnik\n'
printf 'POSTGRES_PASSWORD=%s\n' "$POSTGRES_PASSWORD"
printf 'POSTGRES_VOLUME_NAME=%s\n' "$postgres_volume_name"
printf 'POSTGRES_BIND_ADDRESS=127.0.0.1\n'
printf 'POSTGRES_PUBLIC_PORT=%s\n' "$postgres_public_port"
printf 'TELEGRAM_BOT_TOKEN=%s\n' "$TELEGRAM_BOT_TOKEN"
printf 'TELEGRAM_BOT_API_IMAGE=%s\n' "$TELEGRAM_BOT_API_IMAGE"
printf 'YOUTUBE_PO_TOKEN_PROVIDER_IMAGE=%s\n' "$YOUTUBE_PO_TOKEN_PROVIDER_IMAGE"
printf 'TELEGRAM_BOT_API_CONTAINER_NAME=%s\n' "$telegram_bot_api_container_name"
printf 'TELEGRAM_BOT_API_DATA_VOLUME_NAME=%s\n' "$telegram_bot_api_data_volume_name"
printf 'MEDIA_WORK_VOLUME_NAME=%s\n' "$media_work_volume_name"
printf 'TELEGRAM_API_ID=%s\n' "${TELEGRAM_API_ID:-}"
printf 'TELEGRAM_API_HASH=%s\n' "${TELEGRAM_API_HASH:-}"
printf 'TELEGRAM_LOCAL_API_ENABLED=%s\n' "$telegram_local_api_enabled"
printf 'YOUTUBE_PO_TOKEN_PROVIDER_ENABLED=%s\n' "$youtube_po_token_provider_enabled"
printf 'YOUTUBE_PO_TOKEN_PROVIDER_URL=%s\n' "$youtube_po_token_provider_url"
printf 'YOUTUBE_PO_TOKEN_PROVIDER_CONTAINER_NAME=%s\n' "$youtube_po_token_provider_container_name"
printf 'TELEGRAM_BOT_API_URL=%s\n' "$telegram_bot_api_url"
printf 'TELEGRAM_BOT_FILE_API_URL=%s\n' "$telegram_bot_file_api_url"
printf 'TELEGRAM_MAX_UPLOAD_BYTES=%s\n' "$telegram_max_upload_bytes"
printf 'TELEGRAM_FILE_STORAGE_CHAT_ID=%s\n' "${TELEGRAM_FILE_STORAGE_CHAT_ID:-}"
printf 'TELEGRAM_CONNECT_TIMEOUT=%s\n' "${TELEGRAM_CONNECT_TIMEOUT:-10s}"
printf 'TELEGRAM_REQUEST_TIMEOUT=%s\n' "${TELEGRAM_REQUEST_TIMEOUT:-60m}"
printf 'DOWNLOAD_WORKERS=%s\n' "${DOWNLOAD_WORKERS:-3}"
printf 'DOWNLOAD_PLAYLIST_WORKERS=%s\n' "${DOWNLOAD_PLAYLIST_WORKERS:-1}"
printf 'DOWNLOAD_PLAYLIST_ITEM_PARALLELISM=%s\n' "${DOWNLOAD_PLAYLIST_ITEM_PARALLELISM:-2}"
printf 'DOWNLOAD_WORK_DIR=%s\n' "$download_work_dir"
printf 'DOWNLOAD_YT_DLP_METADATA_TIMEOUT=%s\n' "${DOWNLOAD_YT_DLP_METADATA_TIMEOUT:-30s}"
printf 'DOWNLOAD_YT_DLP_DOWNLOAD_TIMEOUT=%s\n' "${DOWNLOAD_YT_DLP_DOWNLOAD_TIMEOUT:-30m}"
printf 'DOWNLOAD_YT_DLP_CLOUD_MAX_WORKSPACE_BYTES=%s\n' "${DOWNLOAD_YT_DLP_CLOUD_MAX_WORKSPACE_BYTES:-536870912}"
printf 'DOWNLOAD_INSTAGRAM_METADATA_TIMEOUT=%s\n' "${DOWNLOAD_INSTAGRAM_METADATA_TIMEOUT:-30s}"
printf 'DOWNLOAD_INSTAGRAM_DOWNLOAD_TIMEOUT=%s\n' "${DOWNLOAD_INSTAGRAM_DOWNLOAD_TIMEOUT:-10m}"
printf 'DOWNLOAD_VIDEO_FFMPEG_TIMEOUT=%s\n' "${DOWNLOAD_VIDEO_FFMPEG_TIMEOUT:-20m}"
printf 'DOWNLOAD_CHOICE_SESSION_TTL=%s\n' "${DOWNLOAD_CHOICE_SESSION_TTL:-30m}"
printf 'DOWNLOAD_CHOICE_SESSION_MAX_AGE=%s\n' "${DOWNLOAD_CHOICE_SESSION_MAX_AGE:-30d}"
printf 'DOWNLOAD_CHOICE_SESSION_CLEANUP_DELAY_MS=%s\n' "${DOWNLOAD_CHOICE_SESSION_CLEANUP_DELAY_MS:-600000}"
printf 'TELEGRAM_DONATION_URL=%s\n' "${TELEGRAM_DONATION_URL:-https://pay.cloudtips.ru/p/6f779a3d}"
printf 'TELEGRAM_DONATION_CHANNEL_ID=%s\n' "${TELEGRAM_DONATION_CHANNEL_ID:-@mediakradnik}"
printf 'TELEGRAM_DONATION_PIN_ENABLED=%s\n' "${TELEGRAM_DONATION_PIN_ENABLED:-false}"
printf 'TELEGRAM_DONATION_PIN_MESSAGE_ID=%s\n' "${TELEGRAM_DONATION_PIN_MESSAGE_ID:-}"
printf 'TELEGRAM_DONATION_PIN_LANGUAGE=%s\n' "${TELEGRAM_DONATION_PIN_LANGUAGE:-en}"
