# Kradnik

Kradnik is a Telegram bot that downloads media from links and sends the result back as video, audio, or cover art.

The project is built as a backend service around Telegram Bot API, `yt-dlp`, `ffmpeg`, and PostgreSQL.
It is designed to accept user-submitted links, process them asynchronously, and deliver Telegram-friendly files.

All current bot instructions and user-facing messages are in Russian. English localization may be added later.

## What the Bot Does

- Accepts public media links in Telegram.
- Shows source information, available video qualities, audio, cover art, and estimated sizes for every link.
- Reuses a short-lived, bounded metadata cache for repeated links.
- Downloads media through `yt-dlp`.
- Sends the downloaded result back through Telegram.
- Reuses Telegram-uploaded files when possible.
- Checks file size before expensive work when metadata is available.
- Compresses some oversized vertical videos.
- Stores choice sessions, job state, retries, and cache metadata in PostgreSQL.

## User Flow

1. User opens the bot.
2. User sends a link.
3. Bot analyzes available formats and shows their sizes.
4. User selects a video quality, audio, or cover art.
5. Bot creates a download job.
6. Worker processes the job in the background.
7. Bot updates the status message.
8. Bot replies to the link with the final file or a short error message.

## Main Commands

- `/start` - start message.
- `/help` - usage help.
- `/legal` - legal disclaimer.
- `/donate` - donation message.

## Supported Media

The project has explicit YouTube handling and a generic fallback for other URLs supported by `yt-dlp`.
YouTube handling covers common single-video URL shapes such as watch pages, short links, Shorts, live links, embeds, and music links.
The bot does not try to bypass private content, paid access, platform restrictions, authentication, or unsupported URLs.

## Architecture Overview

```text
Telegram updates
    -> command handlers
    -> format analysis and choice session
    -> download job creation
    -> PostgreSQL queue
    -> background worker
    -> yt-dlp metadata and download
    -> optional ffmpeg preparation
    -> Telegram upload
    -> cache/job completion
```

## Stack

- Kotlin
- Spring Boot
- Spring Data JPA
- PostgreSQL
- Flyway
- Gradle
- Docker / Docker Compose
- Telegram Bot API
- `yt-dlp`
- `ffmpeg`
- JUnit 5
- MockK

## Local Development

Requirements:

- JDK
- Docker and Docker Compose
- `yt-dlp`
- `ffmpeg`
- Telegram bot token

Start the database:

```bash
docker compose up -d postgres
```

Run the app with the local profile:

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

Run tests:

```bash
./gradlew test
```

Run full checks:

```bash
./gradlew check
```

Build the application jar:

```bash
./gradlew bootJar
```

## Docker

The Docker image contains the application runtime plus the external media tools needed by the bot.

Build the jar, prepare Docker context, and build the image:

```bash
./gradlew bootJar
mkdir -p .deploy
cp build/libs/app.jar .deploy/app.jar
docker build -t kradnik:local .
```

Run with Docker Compose:

```bash
docker compose up -d
```

View logs:

```bash
docker compose logs -f app
```

## Database

The database stores:

- download jobs;
- job statuses and retry metadata;
- source metadata;
- Telegram upload metadata;
- short-lived download choice sessions;
- cache keys for Telegram file reuse;
- analytics events and dashboard views.

Schema changes are managed through Flyway migrations.

## Analytics

Runtime analytics are stored in PostgreSQL in `analytics_events`.

Metabase is an opt-in Docker Compose service under the `analytics` profile:

- development/test port: `3000`;
- production port: `3001`.

Start it locally when needed:

```bash
docker compose --profile analytics up -d metabase
```

On deployed environments, use the manual `Deploy` GitHub Actions workflow with the `analytics-start` or `analytics-stop` operation. Regular application deploys preserve its explicit on/off state and do not enable Metabase automatically. The workflow refuses to start Metabase when less than `1800 MiB` of memory is available.

Connect Metabase to PostgreSQL with the read-only role:

- host: `postgres`;
- port: `5432`;
- database: current `POSTGRES_DB`;
- user: `kradnik_analytics_reader`;
- password: `METABASE_ANALYTICS_DB_PASSWORD`.

Use the `analytics_*` views for dashboards. The role has `SELECT` access only.

## Deployment

Deployment is automated through GitHub Actions.

The workflow performs the usual production steps:

1. run tests and checks;
2. build the application jar;
3. build and publish a Docker image;
4. sync runtime configuration to the server;
5. restart services with Docker Compose;
6. verify that the application container is running.

The repository keeps separate deployment paths for development and production environments.

## Extending the Bot

To add a new media platform:

- add platform-specific download settings;
- define URL normalization and cache-key rules;
- keep generic `yt-dlp` fallback behavior;
- add tests for supported and rejected URL shapes.

To change persistence:

- add a new Flyway migration;
- keep existing migrations immutable for already deployed databases.

## Notes

- Temporary files are cleaned after job processing.
- Cached Telegram uploads depend on Telegram `file_id` validity.
- Playlists are intentionally not downloaded.
