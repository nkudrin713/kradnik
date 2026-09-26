# Kradnik

Kradnik retrieves public media from YouTube, Instagram, and VK and delivers it directly in Telegram.

It supports:

- video quality selection, audio-only downloads, and cover images;
- YouTube playlist audio as 96 kbps MP3, up to 100 tracks per job;
- full Instagram posts with the video or photo album followed by copyable monospace post text;
- direct chats and inline guest mode; playlists and full Instagram posts are available only in direct chats;
- cancellation of queued and running downloads;
- English and Russian interfaces;
- Telegram `file_id` reuse and cloud or local Bot API delivery.

Playlist video downloads, playlists from other platforms, private content, and authentication bypasses are not supported.

## Request flow

![Kradnik request flow](docs/request-flow.svg)

The diagram shows the single-media path. Playlist jobs use `PlaylistQueueWorker` and `PlaylistJobProcessor` with the same persisted queue and job states.

- Metadata is loaded before enqueueing to build the available format menu and estimate sizes.
- Metadata work uses 2 threads and accepts at most 32 pending requests.
- YouTube playlists expose one 96 kbps MP3 option for all tracks when there are at most 100 entries; larger playlists offer the first 100 or last 100.
- Instagram videos keep the video/audio menu and add a full-post option; static posts expose one full-post option.
- Menu snapshots, ownership, and language preferences are stored in PostgreSQL, so callbacks survive restarts.

## Queue and lifecycle

- The application runs as a single instance.
- `DOWNLOAD_WORKERS` controls single-media worker loops; the default is 3.
- `DOWNLOAD_PLAYLIST_WORKERS` controls a separate pool of playlist worker loops; the default is 1, in addition to the single-media workers.
- Each playlist downloads up to `DOWNLOAD_PLAYLIST_ITEM_PARALLELISM` tracks concurrently; the default is 2.
- Pending jobs stay in PostgreSQL. Workers claim them with `FOR UPDATE SKIP LOCKED` and `UPDATE ... RETURNING`.
- Claiming and state changes use short transactions; download and Telegram upload I/O run outside them.
- Job states are `QUEUED`, `PROCESSING`, `COMPLETED`, `FAILED`, and `CANCELLED_BY_USER`.
- Failed jobs are not retried automatically; users can submit the link again. A playlist can complete with partial results when individual tracks fail.
- Startup removes abandoned work directories and returns `PROCESSING` jobs to `QUEUED`.
- Shutdown interrupts external I/O and waits up to 30 seconds for each worker pool.
- Interrupted jobs are retried after restart; playlists retain recorded track results and process only remaining entries.
- A crash after Telegram accepts a file but before `COMPLETED` can produce a duplicate message.
- Overlapping application instances are not supported.

## Code map

Paths are relative to `src/main/kotlin/com/nkudrin713/kradnik/`.

| Component | Responsibility |
| --- | --- |
| `telegram/handler/TelegramUpdateHandler.kt` | Commands, links, and callbacks |
| `download/platform/PlatformResolver.kt` | URL validation, normalization, and source routing |
| `download/choice/DownloadChoicePlanner.kt` | Format and size menu options |
| `telegram/DownloadChoiceCoordinator.kt` | Bounded metadata execution and menu publication |
| `download/service/DownloadJobService.kt` | Enqueue, claim, completion, failure, cancellation, and startup recovery |
| `download/repository/DownloadJobRepository.kt` | Queue SQL and reusable Telegram file lookup |
| `download/processing/DownloadQueueWorker.kt` | Single-media worker pool and polling loops |
| `download/processing/DownloadJobProcessor.kt` | Download-to-delivery flow and cleanup |
| `download/playlist/YouTubePlaylistPlanner.kt` | Playlist metadata, track ranges, and audio options |
| `download/processing/PlaylistQueueWorker.kt`, `download/processing/PlaylistJobProcessor.kt` | Separate playlist workers, bounded track processing, and ordered delivery |
| `download/processing/ActiveDownloadRegistry.kt` | Running coroutine registry for user cancellation |
| `download/DownloadEngine.kt` | yt-dlp, Instagram video/image, and cover branches |
| `download/instagram/InstagramEmbedDownloader.kt` | Instagram metadata and media download |
| `ytdlp/YtDlpService.kt`, `ytdlp/YtDlpPresets.kt`, `process/DefaultProcessRunner.kt` | Download presets, external commands, timeouts, diagnostics, and process termination |
| `download/telegram/TelegramFileSender.kt`, `telegram/TelegramMediaSender.kt` | Telegram delivery, cached files, and photo albums |
| `download/video/` | Video probing and Telegram compatibility normalization |

Runtime ownership:

- PostgreSQL stores jobs, choice sessions, and user language preferences.
- Flyway owns schema changes.
- HTTP clients are shared; metadata is loaded before enqueueing, while download processes and work directories belong to a job or playlist entry.
- PostgreSQL owns job state; `ActiveDownloadRegistry` holds running coroutine handles in memory so cancellation can reach active I/O.

## Stack

- Application: Kotlin, Spring Boot, Spring Data JPA, Java 21 executors.
- Storage: PostgreSQL and Flyway.
- Media: yt-dlp, ffmpeg, and ffprobe.
- Delivery: Telegram Bot API.
- Deployment: Docker Compose.
- Tests: JUnit, MockK, JaCoCo, and Testcontainers.
- I/O model: executor worker loops bridge to coroutines for job cancellation, parallel playlist processing, and external I/O.

## Local development

Requirements: Java 21, Docker, yt-dlp, ffmpeg/ffprobe, Deno for YouTube JavaScript challenges, and a Telegram bot token. See `Dockerfile` for the packaged media dependencies.

```bash
cp .env.example .env
# Set TELEGRAM_BOT_TOKEN in .env
APP_IMAGE=kradnik:local docker compose up -d postgres
./gradlew bootRun --args='--spring.profiles.active=local'
```

Run the complete verification:

```bash
./gradlew check bootJar
```

- `check` runs ktlint, tests, and aggregate JaCoCo coverage verification.
- PostgreSQL integration tests use Testcontainers and are skipped when Docker is unavailable.
- A successful build with skipped Testcontainers tests is not database verification.

## Configuration

- `POSTGRES_*`: database connection.
- `TELEGRAM_BOT_*`, `TELEGRAM_MAX_UPLOAD_BYTES`: Telegram endpoints and file-size limit.
- `TELEGRAM_FILE_STORAGE_CHAT_ID`: storage chat for fresh guest-mode uploads and playlist tracks.
- `DOWNLOAD_WORKERS`: concurrent single-media jobs; default 3.
- `DOWNLOAD_PLAYLIST_WORKERS`: additional concurrent playlist jobs; default 1.
- `DOWNLOAD_PLAYLIST_ITEM_PARALLELISM`: concurrent track downloads inside one playlist; default 2.
- `DOWNLOAD_WORK_DIR`: writable media directory with one subdirectory per job.
- `DOWNLOAD_*_TIMEOUT`: external-process and HTTP timeouts.
- `DOWNLOAD_YT_DLP_CLOUD_MAX_WORKSPACE_BYTES`: per-process cloud download workspace cap.
- `DOWNLOAD_CHOICE_SESSION_TTL`: retention after selection or cancellation; default 30 minutes.
- `DOWNLOAD_CHOICE_SESSION_MAX_AGE`: maximum lifetime of an unselected menu; default 30 days.
- `DOWNLOAD_CHOICE_SESSION_CLEANUP_DELAY_MS`: cleanup interval; default 600000 ms.
- `YOUTUBE_PO_TOKEN_PROVIDER_URL`: optional YouTube PO Token Provider.

Operational notes:

- Local Bot API endpoints require a media volume shared with the application; point `DOWNLOAD_WORK_DIR` to it.
- Guest mode requires BotFather enablement and permission to use the configured storage chat.
- Playlist downloads also require access to the storage chat for uploading new tracks before ordered delivery.
- Cached `file_id` values do not need an intermediate upload.
- `/language` changes the persisted user language.

## Docker and releases

```bash
./gradlew bootJar
mkdir -p .deploy
cp build/libs/app.jar .deploy/app.jar
docker build -t kradnik:local .
APP_IMAGE=kradnik:local docker compose up -d
```

- The image includes yt-dlp, ffmpeg, and runtime dependencies.
- Compose profiles `telegram-local` and `youtube-pot` enable optional services.
- `scripts/render-deploy-env.sh` renders production configuration.
- Merging to `main` does not deploy. A manual release builds and deploys an immutable version and image digest.
- Migration `V26` requires the old application to be stopped; mixed versions and application-only rollback are unsafe.
- Applied Flyway migrations are immutable.
