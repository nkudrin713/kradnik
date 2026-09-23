# Architecture simplification

## Audit before changes

The core was Telegram input, URL normalization, format selection, a PostgreSQL queue, source downloading, Telegram delivery, upload limits, and temporary-file cleanup. Useful features that remain are language selection, direct/guest delivery, audio/cover options, cached Telegram file IDs, and video compatibility checks.

The bottleneck was `DownloadQueueWorker.processNextJob()`: one scheduled invocation claimed a row and blocked inside `runBlocking` until download and upload ended. The scheduler never submitted another download during that time. Metadata already ran in a separate two-thread executor, whose pending queue was unbounded.

The queue carried lease tokens, lease expiration, a heartbeat coroutine, stale-lease recovery, attempt counters, retry deadlines, exponential job backoff, and a separate upload state. `DownloadJobLifecycle` translated processor outcomes into database transitions and status messages. `DownloadPreparation` plus `PreparedDownloadSession` introduced anonymous downloader implementations and five preparation outcomes, primarily to accommodate Instagram rate limiting and rescheduling. A periodic orphan-directory scan queried lease ownership before deletion. Disk-capacity interfaces added separate components without reserving space atomically between workers.

Platform routing used a Spring-injected handler list and an interface for three fixed branches. Audio metadata was written to the database even though it was only needed during delivery. These mechanisms made the download path substantially longer than the actual business operation.

## Decisions and removals

- Replace the scheduled, blocking single consumer with a Java fixed-size pool of N long-lived consumers. No dispatcher, semaphore, or runtime job queue is needed: each consumer requests work only when free.
- Remove leases, heartbeats, attempt ownership wrappers, expired-lease recovery, scheduled retries, retry counters/deadlines, and the persisted upload state.
- Fold lifecycle outcomes and best-effort Telegram statuses into `DownloadJobProcessor`.
- Replace preparation result hierarchies and executable sessions with `PreparedDownload`, containing metadata and an optional direct Instagram result. `DownloadEngine` explicitly routes the download.
- Remove `InstagramDownloader`, its local cooldown/rate limiter, and retry-header plumbing. Source errors are reported without automatic rescheduling. Instagram may still reject requests; submitting a new link is the explicit retry.
- Merge YouTube, Instagram, and VK normalization into `PlatformResolver`; remove their handler classes and `PlatformDownloadHandler`.
- Remove the disk-capacity guard, file-store provider abstraction, and periodic lease-aware orphan cleaner. Use a concrete `WorkDirCleaner` before startup and in each job's `finally`.
- Keep audio metadata in the claimed job's transient fields, without an extra database update.
- Bound metadata preparation to two active and 32 pending requests. Reject excess work with the existing preparation error.
- Make Telegram media requests cancellable and JDK HTTP operations interruptible. Finish process cleanup in a non-cancellable block so stopping a workspace monitor cannot skip child-process termination.

Migration V26 removes the obsolete columns and preserves the rows. No application table is dropped. Historical Flyway migrations remain unchanged. The old application must be stopped before applying the migration; the new schema is not compatible with the old worker.

## Remaining state and boundaries

| State | Owner and reason |
| --- | --- |
| `download_jobs` | Persistent accepted requests and four statuses; pending jobs survive a restart. Completed rows also provide Telegram file reuse. |
| `download_choice_sessions` | Persisted menu options and ownership; old callbacks can still be validated after restart. Retention bounds unused menus. |
| `telegram_user_preferences` | User-selected language across restarts. |
| Audio metadata, direct source URLs, process handles | Local to one processing call. They need no persistence or cross-thread registry. |
| HTTP clients and configured JSON mappers | Shared, initialized once, and used with independent requests. |
| Job directories | One numeric directory per job ID. Startup removes old directories before recovery; a leftover directory that could not be deleted is never reused. |

The process runner retains bounded output, execution deadlines, process-tree termination, and the cloud workspace byte cap. These protect finite resources while invoking external programs. The byte cap is a per-process check, not disk reservation or a distributed quota system. Parallel jobs can still exhaust available disk space; the filesystem error fails the job and cleanup runs.

`ProcessRunner` and `InstagramHttpClient` remain interfaces at external boundaries. JPA repositories remain framework interfaces. Video probing/transcoding, upload-size checks, localization, and guest delivery remain because removing them would break existing user behavior. Telegram file reuse is a single indexed lookup and a send-by-ID path; an invalid cached ID falls back to a fresh source download.

## New flow

```text
Telegram update
  -> validate/normalize URL and retrieve catalog
  -> show options; user selects
  -> create QUEUED row in PostgreSQL
  -> free worker atomically claims the row as PROCESSING
  -> prepare source -> check size -> download -> normalize video if needed
  -> Telegram upload -> COMPLETED
  -> on ordinary error: FAILED
  -> finally: remove workspace
```

For a source download, start reading at `DownloadJobProcessor.process`. `DownloadEngine` contains explicit source branches, `YtDlpService` builds the external command, and `TelegramFileSender` selects the appropriate Telegram media operation. The worker and queue service sit around this scenario rather than adding stages inside it.

## Concurrency and failure semantics

`Executors.newFixedThreadPool(workers)` runs exactly N consumer loops. Its tasks are the loops themselves; download jobs are never preloaded into its internal queue. A loop waits for its current `process(job)` call before claiming again. With three busy consumers and 100 accepted jobs, 97 remain `QUEUED` in PostgreSQL.

Claim selects the oldest available row, ordered by creation time and ID, using `FOR UPDATE SKIP LOCKED`. The same SQL statement changes it to `PROCESSING` and returns the row. A Spring transaction commits before the processor starts network or process I/O. Concurrent workers skip rows locked by another claim, and committed `PROCESSING` rows are excluded from the next query. Claim order is FIFO among unlocked rows; completion order is naturally concurrent.

An ordinary source/upload exception is persisted as `FAILED`; there is no automatic job retry. The loop catches iteration failures and continues after a short polling delay. If the database itself prevents recording failure, the row remains `PROCESSING` until startup recovery. There is no live watchdog. Shutdown interrupts the consumers, cancels in-flight suspend I/O, waits up to 30 seconds, and lets startup recovery handle interrupted jobs.

Startup cleans old numeric job directories and requeues `PROCESSING` before submitting any consumers. This assumes a single application instance and no overlapping deploy. It guarantees exclusive live processing within that model, not exactly-once delivery across crashes: Telegram and PostgreSQL do not share a transaction, so a crash after successful upload can cause a duplicate delivery after restart.

The worker pool is the only download concurrency mechanism. Existing coroutines remain at the I/O boundary for subprocess stdout/stderr handling and cancellation; each consumer waits for its call through `runBlocking`. They do not increase the number of active download jobs.

## Interview walkthrough

“TelegramPollingService receives updates and passes them to TelegramUpdateHandler. A link goes through PlatformResolver and metadata preparation to a format menu. On selection, TelegramDownloadStarter asks DownloadJobService to create a QUEUED row. DownloadQueueWorker owns a fixed number of threads; each claims one row through a short PostgreSQL transaction, then calls DownloadJobProcessor outside the transaction. The processor prepares source data, checks limits, uses DownloadEngine and yt-dlp to download the file, sends it with TelegramFileSender, records the outcome, and cleans up in finally. While one thread waits for a download or upload, other threads handle other jobs.”

## Interview concurrency questions

| Question | Answer |
| --- | --- |
| Why PostgreSQL instead of RabbitMQ/Kafka? | PostgreSQL is already required for application state. One queue table and a short atomic claim are enough for this single-instance project; a broker would add deployment and failure modes without a current requirement. |
| Why a fixed thread pool? | It gives an explicit resource budget and a familiar Java execution model. The default is three complete download/upload operations at once. |
| What if two workers want the same job? | The claim locks the candidate row with `SKIP LOCKED` and changes its status in one transaction. The other worker skips the lock or sees a non-queued row. |
| Why release the transaction before download? | Network and media operations can take minutes. Holding a connection and row lock for that long would waste database resources. |
| What happens if processing throws? | The processor records `FAILED` and cleans files. An unexpected loop error is logged and the loop continues; a database outage may leave a row for startup recovery. |
| What happens after application restart? | Before workers start, abandoned workspaces are cleaned and interrupted `PROCESSING` jobs become `QUEUED`. A previously delivered file can be sent again if completion was not committed. |
| What if 100 links arrive? | Metadata admission is separately bounded to two active and 32 pending requests; excess raw links receive a preparation error. Once 100 selections are accepted as jobs, three process and 97 wait in PostgreSQL. |
| Why not create 100 threads? | Threads, child processes, disk I/O, transcoding CPU, and network bandwidth are finite. More concurrent downloads can reduce throughput and reliability. |
| What is shared between threads? | The database, immutable configuration, and HTTP clients. Job objects, metadata, requests, process handles, and directories belong to one processing call. |
| How is the concurrency limit enforced? | Exactly N loops run in a fixed-size pool, and each loop claims again only after its current processing call returns. No separate capacity counter is necessary. |

## Verification

Unit tests cover simultaneous execution, bounded concurrency, continued polling after failures, source and Telegram errors, upload limits, cache fallback, cancellation, file cleanup, and Telegram HTTP cancellation. The worker test uses latches and a semaphore, without timing sleeps. PostgreSQL integration tests cover concurrent claims, one-row contention, idempotent enqueue, migrations, terminal transitions, and startup recovery; these require a running PostgreSQL test environment.

The build also runs the existing platform, menu, localization, media, process, and Spring wiring tests. Real Telegram delivery and real source downloads are separate E2E checks; unit tests do not establish external-service availability.

## Scope and measured result

Compared with the starting `main` commit `eaf8b96639a8e8c44d9d9d8874cc83fd1e7b71fa`:

| Production Kotlin metric | Before | After |
| --- | ---: | ---: |
| Files | 65 | 57 |
| Physical lines, including comments and blank lines | 6,476 | 5,222 |
| Named class declarations, including nested/data/enum classes | 125 | 106 |
| Interface declarations, including sealed interfaces | 18 | 11 |

This removes 1,254 production lines (19.4%), with a net reduction of 19 class declarations and seven interfaces. No package disappeared: remaining files in those packages still own distinct behavior. No new production dependency was added.

Local verification: `./gradlew check bootJar --rerun-tasks --no-build-cache` passed with **264 tests, zero failures, and zero skips**. This includes eight PostgreSQL 17 integration tests executed through Docker/Testcontainers: Flyway migrations, concurrent claims, one-row contention across eight threads, idempotent enqueue, terminal transitions, and startup recovery. JaCoCo reports 87.2% instruction and 89.6% line coverage for the configured scope. There are no Kotlin compiler warnings in the build output; JVM class-data-sharing notices come from test instrumentation.

Shell syntax, YAML syntax, and cloud/local deployment environment rendering with dummy values were checked; the new worker setting propagates correctly. Real Telegram/source E2E was not run. No production database, deployment, Git commit, or push was performed.
