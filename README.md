# Kradnik

A Telegram bot for downloading public media from YouTube, Instagram, and VK. Send a link directly or invoke the bot in another chat with `@bot link`, choose video quality, audio, a cover image, or every image from a static Instagram post, and receive the media. Instagram carousels are sent directly as Telegram photo albums. English and Russian interfaces, Telegram file reuse, and cloud/local Bot API delivery are supported. Source playlists, private content, and authentication bypasses are not supported.

## Request flow

```text
TelegramPollingService -> TelegramUpdateHandler
  -> DownloadChoiceCoordinator: retrieve metadata and show the format menu
  -> DownloadChoiceHandler: accept the user's selection
  -> TelegramDownloadStarter -> DownloadJobService: persist QUEUED job

DownloadQueueWorker: one loop per worker thread
  -> DownloadJobService.claimNextQueuedJob(): QUEUED -> PROCESSING
  -> DownloadJobProcessor.process():
       cached Telegram file, or:
       DownloadEngine.prepare() -> size check -> DownloadEngine.download()
       -> optional TelegramVideoPreparer -> TelegramFileSender.send()
       -> COMPLETED / FAILED
       -> finally: delete the job's directory
```

Metadata is needed before enqueueing because it determines the available formats and estimated sizes. Instagram video posts keep the existing video/audio menu; static posts expose one option that sends every image in post order. Two metadata threads serve this step, with at most 32 pending requests; overload returns the existing preparation error. The download queue contains only accepted selections. Menu snapshots and ownership are stored in PostgreSQL so callbacks still work after a restart; language preferences are stored per user.

## Concurrency

The application is designed to run as a single instance. Download concurrency is handled by an internal fixed-size worker pool, configured by `download.workers` (`DOWNLOAD_WORKERS`, default **3**). Each of its N long-lived loops claims one job, processes it completely, and only then claims another; pending downloads remain in PostgreSQL, never in an executor queue. Claim uses a short transaction with `FOR UPDATE SKIP LOCKED` and `UPDATE ... RETURNING`, so two threads cannot claim the same queued row. Downloading and uploading happen outside the transaction; an idle worker polls every second and a failed iteration does not stop the loop. Existing suspend-based process/HTTP adapters run behind a `runBlocking` bridge; they do not create additional download jobs.

States are `QUEUED`, `PROCESSING`, `COMPLETED`, and `FAILED`. Upload progress is a Telegram message, not another database state. Ordinary failures become `FAILED` without automatic job retry; the user can submit the link again. On startup, abandoned job directories are removed and `PROCESSING` jobs return to `QUEUED` before workers start. Shutdown interrupts the loops and cancels external I/O, then waits up to 30 seconds for workers to stop.

**Restart limitation:** delivery is at least once across crashes. A crash after Telegram accepted a file but before `COMPLETED` was committed can produce a duplicate message after restart. Never overlap application instances, including during deployment: startup recovery assumes the old process has stopped. If a database outage prevents persisting a terminal state, the failure is logged and startup recovery handles the remaining `PROCESSING` row.

## Code map

Paths below are relative to `src/main/kotlin/com/nkudrin713/kradnik/`.

| Component | Responsibility |
| --- | --- |
| `telegram/handler/TelegramUpdateHandler.kt` | Commands, link input, and callbacks |
| `download/platform/PlatformResolver.kt` | URL validation, normalization, and explicit routing for three sources |
| `download/choice/DownloadChoicePlanner.kt` | Format and size options for the menu |
| `telegram/DownloadChoiceCoordinator.kt` | Bounded metadata execution and menu publication |
| `download/service/DownloadJobService.kt` | Enqueue, claim, completion, failure, and startup recovery transactions |
| `download/repository/DownloadJobRepository.kt` | Queue SQL and reusable Telegram file lookup |
| `download/processing/DownloadQueueWorker.kt` | Fixed-size pool and polling loops |
| `download/processing/DownloadJobProcessor.kt` | Linear download-to-delivery scenario and cleanup |
| `download/DownloadEngine.kt` | Explicit yt-dlp, Instagram video/image, and cover download branches |
| `download/instagram/InstagramEmbedDownloader.kt` | Instagram video metadata plus static post/carousel image extraction and download |
| `ytdlp/client/YtDlpService.kt`, `process/DefaultProcessRunner.kt` | External commands, deadlines, bounded diagnostics, process-tree termination |
| `download/telegram/TelegramFileSender.kt`, `telegram/TelegramMediaSender.kt` | Direct/guest media delivery, reusable files, and Telegram photo albums |
| `download/video/` | Probe and normalize incompatible video for Telegram |

Audio metadata stays local to a processing call. Persistent state consists of `download_jobs`, `download_choice_sessions`, and `telegram_user_preferences`; Flyway manages all schema changes. The HTTP clients are shared; request objects, media metadata, processes, and numeric job directories are per job. There is no shared mutable collection of running jobs.

## Technologies

Kotlin/JVM, Java 21 executors, Spring Boot, Spring Data JPA, PostgreSQL, Flyway, yt-dlp, ffmpeg/ffprobe, Telegram Bot API, Docker Compose, JUnit/MockK, and Testcontainers. Coroutines remain inside existing external-I/O adapters for cancellation and process stream handling.

## Local development

Java 21, Docker, yt-dlp, ffmpeg, and a Telegram bot token are required.

```bash
cp .env.example .env
# Set TELEGRAM_BOT_TOKEN in .env
docker compose up -d postgres
./gradlew bootRun --args='--spring.profiles.active=local'
```

```bash
./gradlew check bootJar
```

`check` runs tests and verifies aggregate JaCoCo coverage. PostgreSQL integration tests use Testcontainers and are skipped if Docker is unavailable; a successful build with skipped tests is not database verification.

## Configuration

- `POSTGRES_*`: database connection.
- `TELEGRAM_BOT_*`, `TELEGRAM_MAX_UPLOAD_BYTES`: Telegram endpoints and file-size limit.
- `TELEGRAM_FILE_STORAGE_CHAT_ID`: private storage chat needed for a fresh guest-mode upload.
- `DOWNLOAD_WORKERS`: maximum concurrently processing download jobs, default 3.
- `DOWNLOAD_WORK_DIR`: dedicated writable media directory; each job has its own subdirectory.
- `DOWNLOAD_*_TIMEOUT`: bounded external-process and HTTP execution.
- `DOWNLOAD_YT_DLP_CLOUD_MAX_WORKSPACE_BYTES`: per-process cloud download workspace cap.
- `DOWNLOAD_CHOICE_SESSION_*`: menu retention.
- `YOUTUBE_PO_TOKEN_PROVIDER_URL`: optional YouTube PO Token Provider.

Local Bot API endpoints require a media volume shared with the application. Set `DOWNLOAD_WORK_DIR` to that shared path. Guest mode also requires BotFather enablement and permission to send files to the configured storage chat. Cached `file_id` values need no intermediate upload. `/language` changes the persisted user language; donation configuration remains in the environment.

## Docker and deployment

```bash
./gradlew bootJar
mkdir -p .deploy
cp build/libs/app.jar .deploy/app.jar
docker build -t kradnik:local .
APP_IMAGE=kradnik:local docker compose up -d
```

The image contains yt-dlp, ffmpeg, and runtime dependencies. Compose profiles `telegram-local` and `youtube-pot` enable optional services. Configuration is rendered by `scripts/render-deploy-env.sh`; `DOWNLOAD_WORKERS` is also exposed as a GitHub production environment variable. Merging to `main` does not deploy; a manual release builds and deploys an immutable version and exact image digest.

Migration `V26` removes lease/retry fields, transient audio metadata columns, and the `uploading` state. It preserves jobs and converts old in-progress rows to `queued`. Stop the old application before running the new version; do not run mixed versions or roll back only the application after this migration. Existing migrations remain unchanged.

See [architecture decisions and interview walkthrough](docs/architecture-refactor.md) for the refactoring rationale and concurrency questions.
