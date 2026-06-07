# Kradnik

Kradnik is a Telegram bot that downloads media from links and sends the result back to the chat as video or audio.

The project is built as a backend service around Telegram Bot API, `yt-dlp`, `ffmpeg`, and PostgreSQL.
It is designed to accept user-submitted links, process them asynchronously, and deliver Telegram-friendly files.

All current bot instructions and user-facing messages are in Russian. English localization may be added later.

## What the Bot Does

- Accepts public media links in Telegram.
- Lets each chat choose video or audio mode.
- Downloads media through `yt-dlp`.
- Sends the downloaded result back through Telegram.
- Reuses Telegram-uploaded files when possible.
- Checks file size before expensive work when metadata is available.
- Compresses some oversized vertical videos.
- Stores job state, retries, settings, and cache metadata in PostgreSQL.

## User Flow

1. User opens the bot.
2. User selects video or audio mode.
3. User sends a link.
4. Bot creates a download job.
5. Worker processes the job in the background.
6. Bot updates the status message.
7. Bot sends the final file or a short error message.

## Main Commands

- `/start` - start message.
- `/help` - usage help.
- `/mode` - switch between video and audio.
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
- per-chat mode settings;
- cache keys for Telegram file reuse.

Schema changes are managed through Flyway migrations.

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
