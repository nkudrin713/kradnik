# Kradnik

A Telegram bot for downloading public videos from YouTube, Instagram, and VK. A user sends a link directly to the bot or invokes it in another chat with `@bot link`, selects a video quality, audio, or cover image, and receives the file.

## Features

- parses YouTube, Instagram, and VK links;
- supports English and Russian user interfaces with a persisted per-user language preference;
- shows available options and estimated sizes before creating a download job;
- downloads video, audio, and cover images;
- prepares video for Telegram constraints with `ffmpeg`;
- reuses a `file_id` that was previously uploaded to Telegram;
- stores the queue, attempts, leases, choice sessions, and language preferences in PostgreSQL;
- works with both the cloud Telegram Bot API and a local server for files up to 2 GB;
- cleans temporary directories and checks free disk space before downloading.

The bot supports only public single posts. Playlists, private content, and authentication bypasses are not supported.

## Request flow

```text
TelegramPollingService
  -> TelegramUpdateHandler
     -> TelegramLanguageSelector + TelegramUserPreferenceService
     -> direct message or guest_message
  -> DownloadChoiceCoordinator
  -> DownloadChoicePlanner + DownloadChoiceSessionService

user selection
  -> DownloadChoiceHandler
  -> TelegramDownloadStarter
  -> DownloadJobService
  -> DownloadQueueWorker
  -> DownloadJobProcessor
  -> DownloadEngine
  -> TelegramFileSender
```

On the first `/start`, the bot asks the user to choose English or Russian. The `/language` command opens the same selector later. If no explicit choice is available, the bot defaults to English.

The download route is split into two parts. Before selection, the bot resolves the platform, retrieves the format catalog, builds a localized menu, and persists the menu together with its language. After selection, it creates a PostgreSQL job with the same language; a worker claims the job with a lease, downloads the file, and sends it to Telegram. In guest mode, the status, menu, and final file successively replace one inline message.

## Code map

| Task | Main files |
| --- | --- |
| Commands and callback routing | `telegram/handler/TelegramUpdateHandler.kt`, `DownloadChoiceHandler.kt` |
| Language selection and persistence | `telegram/TelegramLanguageSelector.kt`, `telegram/localization/` |
| Localized copy | `src/main/resources/i18n/messages_*.properties` |
| Video quality, audio, and cover menu | `download/choice/DownloadChoicePlanner.kt`, `telegram/TelegramDownloadChoiceView.kt` |
| Link parsing and platform parameters | `download/platform/*DownloadHandler.kt` |
| Source downloading | `download/DownloadEngine.kt`, `ytdlp/client/YtDlpService.kt` |
| Instagram-specific behavior | `download/instagram/` |
| Queue, leases, and retries | `download/service/DownloadJobService.kt`, `download/processing/` |
| Telegram delivery and limits | `telegram/TelegramMediaSender.kt`, `download/telegram/TelegramFileSender.kt`, `telegram/config/TelegramBotProperties.kt` |
| Video preparation | `download/video/` |
| Database schema | `src/main/resources/db/migration/` |
| Containers and deployment | `docker-compose.yml`, `.github/workflows/`, `scripts/render-deploy-env.sh` |

To add a platform, add a `DownloadPlatform` value, a `PlatformDownloadHandler` implementation, and supported-URL tests. `PlatformResolver` receives the handlers through Spring. The platform handler owns format parameters, the normalized URL, and the cache key.

## Local development

Java 21, Docker, `yt-dlp`, `ffmpeg`, and a Telegram bot token are required.

```bash
cp .env.example .env
# Set TELEGRAM_BOT_TOKEN in .env
docker compose up -d postgres
./gradlew bootRun --args='--spring.profiles.active=local'
```

Run checks with:

```bash
./gradlew check
```

`check` runs the tests and verifies aggregate JaCoCo coverage. Integration tests use PostgreSQL through Testcontainers, so Docker must be running.

## Configuration

The application does not know whether an instance is test or production. The environment provides concrete addresses, tokens, limits, and paths through environment variables.

Main configuration groups:

- `POSTGRES_*` — PostgreSQL connection;
- `TELEGRAM_BOT_*`, `TELEGRAM_MAX_UPLOAD_BYTES` — Telegram API and file-size limit;
- `TELEGRAM_FILE_STORAGE_CHAT_ID` — private chat used to obtain a `file_id` before delivering a new file in guest mode;
- `TELEGRAM_DONATION_PIN_LANGUAGE` — donation channel pin language, `en` by default;
- `DOWNLOAD_WORK_DIR`, `DOWNLOAD_*_TIMEOUT` — work directory and timeouts;
- `DOWNLOAD_INSTAGRAM_RATE_LIMIT_*` — local Instagram request limiting;
- `YOUTUBE_PO_TOKEN_PROVIDER_URL` — optional YouTube PO Token Provider.

A local Telegram Bot API is selected through `TELEGRAM_BOT_API_URL` and `TELEGRAM_BOT_FILE_API_URL`. In this mode, `DOWNLOAD_WORK_DIR` must point to a volume shared with the Bot API container.

Guest mode must be enabled through BotFather, and `TELEGRAM_FILE_STORAGE_CHAT_ID` must be configured. The bot needs permission to send files to that private chat. Cached `file_id` values are used without an intermediate upload.

## Docker and deployment

The image contains the application, `yt-dlp`, `ffmpeg`, and the required runtime dependencies. To build the full stack locally:

```bash
./gradlew bootJar
mkdir -p .deploy
cp build/libs/app.jar .deploy/app.jar
docker build -t kradnik:local .
APP_IMAGE=kradnik:local docker compose up -d
```

The `telegram-local` and `youtube-pot` Compose profiles enable optional services. In GitHub Actions, every successful push to `main` is deployed to the test environment. A production release tags the already verified `main` commit as `vX.Y.Z` and deploys the same image digest without rebuilding it. Environment values remain in GitHub Environments and server-side `.env` files rather than Kotlin code.

Flyway manages database migrations. Applied migrations are never modified; every schema change is added as a new migration file.
