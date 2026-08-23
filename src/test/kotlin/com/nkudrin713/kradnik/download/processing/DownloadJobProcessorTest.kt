package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.analytics.DownloadAnalytics
import com.nkudrin713.kradnik.download.cleanup.WorkDirCapacityGuard
import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.executor.DownloadExecutor
import com.nkudrin713.kradnik.download.executor.DownloadExecutorResolver
import com.nkudrin713.kradnik.download.executor.DownloadPreparation
import com.nkudrin713.kradnik.download.executor.PreparedDownloadSession
import com.nkudrin713.kradnik.download.executor.YtDlpDownloadExecutor
import com.nkudrin713.kradnik.download.instagram.InstagramMediaTooLargeException
import com.nkudrin713.kradnik.download.limit.DownloadPreflightDecision
import com.nkudrin713.kradnik.download.limit.DownloadPreflightService
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.download.request.DownloadRequest
import com.nkudrin713.kradnik.download.service.ClaimedDownloadJob
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.telegram.TelegramFileSendResult
import com.nkudrin713.kradnik.download.telegram.TelegramFileSender
import com.nkudrin713.kradnik.download.video.TelegramVideoPreparer
import com.nkudrin713.kradnik.download.video.VideoTooLargeException
import com.nkudrin713.kradnik.telegram.TelegramSendException
import com.nkudrin713.kradnik.ytdlp.client.YtDlpAuthenticationRequiredException
import com.nkudrin713.kradnik.ytdlp.client.YtDlpFileSizeLimitException
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DownloadJobProcessorTest {
    private val downloadJobService: DownloadJobService = mockk()
    private val downloadPreflightService: DownloadPreflightService = mockk()
    private val telegramVideoPreparer: TelegramVideoPreparer = mockk()
    private val telegramFileSender: TelegramFileSender = mockk()
    private val ytDlpService: YtDlpService = mockk()
    private val instagramExecutor: DownloadExecutor = mockk {
        every { supports(any()) } returns false
    }
    private val downloadExecutorResolver = DownloadExecutorResolver(
        listOf(instagramExecutor, YtDlpDownloadExecutor(ytDlpService))
    )
    private val mediaMetadataMapper: MediaMetadataMapper = mockk()
    private val downloadJobLifecycle: DownloadJobLifecycle = mockk(relaxed = true)
    private val downloadAnalytics: DownloadAnalytics = mockk(relaxed = true)
    private val workDirCleaner: WorkDirCleaner = mockk()
    private val workDirCapacityGuard: WorkDirCapacityGuard = mockk(relaxed = true)
    private val uploadLimits = TelegramUploadLimits(TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES)

    @Test
    fun completesCachedJob(@TempDir tempDir: Path) = runTest {
        val job = job()
        val cachedJob = job().apply {
            telegramFileId = "cached-file-id"
            downloadedFileSize = 100
        }
        every { downloadJobService.findCachedJob(job) } returns cachedJob
        coEvery { telegramFileSender.sendCached(job, "cached-file-id", 100) } returns telegramResult()
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir, telegramFileCacheEnabled = true).process(attempt(job))

        coVerify { telegramFileSender.sendCached(job, "cached-file-id", 100) }
        verify { downloadJobLifecycle.markUploading(attempt(job)) }
        verify { downloadJobLifecycle.complete(attempt(job), any()) }
        verify { workDirCleaner.deleteRecursively(jobRoot(tempDir)) }
    }

    @Test
    fun downloadsSourceWhenCachedTelegramFileIsInvalid(@TempDir tempDir: Path) = runTest {
        val job = job()
        val cachedJob = job().apply {
            telegramFileId = "invalid-file-id"
            downloadedFileSize = 100
        }
        val request = request()
        val downloadedFile = DownloadedFile(tempDir.resolve("downloaded.mp4"), 100)
        val metadata = metadata()
        every { downloadJobService.findCachedJob(job) } returns cachedJob
        coEvery {
            telegramFileSender.sendCached(job, "invalid-file-id", 100)
        } throws TelegramSendException(400, "Bad Request: wrong file identifier")
        coEvery { ytDlpService.extractMetadata(request) } returns metadata
        every { downloadPreflightService.check(request, metadata) } returns DownloadPreflightDecision.Allowed(request)
        every { mediaMetadataMapper.toMediaMetadata(metadata) } returns mediaMetadata()
        every { downloadJobService.markMetadata(attempt(job), any()) } returns job
        coEvery { ytDlpService.download(request, jobDir(tempDir)) } returns downloadedFile
        coEvery { telegramVideoPreparer.prepare(downloadedFile, jobDir(tempDir), 1) } returns downloadedFile
        coEvery { telegramFileSender.send(job, downloadedFile) } returns telegramResult()
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir, telegramFileCacheEnabled = true).process(attempt(job))

        coVerify { ytDlpService.download(request, jobDir(tempDir)) }
        coVerify { telegramFileSender.send(job, downloadedFile) }
        verify { downloadJobLifecycle.complete(attempt(job), any()) }
    }

    @Test
    fun failsTerminallyWhenTelegramRejectsRequest(@TempDir tempDir: Path) = runTest {
        val job = job()
        val cachedJob = job().apply {
            telegramFileId = "cached-file-id"
        }
        every { downloadJobService.findCachedJob(job) } returns cachedJob
        coEvery {
            telegramFileSender.sendCached(job, "cached-file-id", null)
        } throws TelegramSendException(403, "Forbidden: bot was blocked by the user")
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir, telegramFileCacheEnabled = true).process(attempt(job))

        verify { downloadJobLifecycle.failTerminal(attempt(job), any()) }
        verify(exactly = 0) { downloadJobLifecycle.failOrRetry(any(), any(), any()) }
    }

    @Test
    fun retriesWhenTelegramIsTemporarilyUnavailable(@TempDir tempDir: Path) = runTest {
        val job = job()
        val retryAfter = Duration.ofSeconds(42)
        val cachedJob = job().apply {
            telegramFileId = "cached-file-id"
        }
        every { downloadJobService.findCachedJob(job) } returns cachedJob
        coEvery {
            telegramFileSender.sendCached(job, "cached-file-id", null)
        } throws TelegramSendException(429, "Too Many Requests", retryAfter)
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir, telegramFileCacheEnabled = true).process(attempt(job))

        verify { downloadJobLifecycle.failOrRetry(attempt(job), any(), retryAfter) }
        verify(exactly = 0) { downloadJobLifecycle.failTerminal(any(), any()) }
    }

    @Test
    fun rejectsWhenPreflightRejects(@TempDir tempDir: Path) = runTest {
        val job = job()
        val request = request()
        every { downloadJobService.findCachedJob(job) } returns null
        coEvery { ytDlpService.extractMetadata(request) } returns metadata()
        every { downloadPreflightService.check(request, any()) } returns DownloadPreflightDecision.Rejected("too large")
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(attempt(job))

        verify { downloadJobLifecycle.rejectTooLarge(attempt(job), "too large") }
        verify { workDirCleaner.deleteRecursively(jobRoot(tempDir)) }
    }

    @Test
    fun downloadsAndUploadsJob(@TempDir tempDir: Path) = runTest {
        val job = job()
        val request = request()
        val downloadedFile = DownloadedFile(tempDir.resolve("downloaded.mp4"), 100)
        val preparedFile = DownloadedFile(tempDir.resolve("prepared.mp4"), 90)
        val metadata = metadata()
        every { downloadJobService.findCachedJob(job) } returns null
        every { downloadPreflightService.check(request, any()) } returns DownloadPreflightDecision.Allowed(request)
        coEvery { ytDlpService.extractMetadata(request) } returns metadata
        every { mediaMetadataMapper.toMediaMetadata(metadata) } returns mediaMetadata()
        every { downloadJobService.markMetadata(attempt(job), any()) } returns job
        coEvery { ytDlpService.download(request, jobDir(tempDir)) } returns downloadedFile
        coEvery { telegramVideoPreparer.prepare(downloadedFile, jobDir(tempDir), 1) } returns preparedFile
        coEvery { telegramFileSender.send(job, preparedFile) } returns telegramResult()
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(attempt(job))

        verify { downloadJobLifecycle.markDownloading(attempt(job)) }
        coVerify(exactly = 1) { ytDlpService.extractMetadata(request) }
        verify { downloadJobService.markMetadata(attempt(job), any()) }
        verify { downloadJobLifecycle.markUploading(attempt(job)) }
        coVerify { telegramVideoPreparer.prepare(downloadedFile, jobDir(tempDir), 1) }
        coVerify { telegramFileSender.send(job, preparedFile) }
        verify { downloadJobLifecycle.complete(attempt(job), any()) }
        verify { workDirCleaner.deleteRecursively(jobRoot(tempDir)) }
    }

    @Test
    fun rejectsDownloadedAudioAboveLimitBeforeUpload(@TempDir tempDir: Path) = runTest {
        val job = job(outputType = OutputType.AUDIO)
        val request = request(outputType = OutputType.AUDIO)
        val downloadedFile = DownloadedFile(
            tempDir.resolve("downloaded.mp3"),
            uploadLimits.maxUploadBytes + 1,
        )
        val metadata = metadata()
        every { downloadJobService.findCachedJob(job) } returns null
        coEvery { ytDlpService.extractMetadata(request) } returns metadata
        every { downloadPreflightService.check(request, metadata) } returns DownloadPreflightDecision.Allowed(request)
        every { mediaMetadataMapper.toMediaMetadata(metadata) } returns mediaMetadata()
        every { downloadJobService.markMetadata(attempt(job), any()) } returns job
        coEvery { ytDlpService.download(request, jobDir(tempDir)) } returns downloadedFile
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(attempt(job))

        verify { downloadJobLifecycle.rejectTooLarge(attempt(job), match { it.contains("limitMb=45.00") }) }
        coVerify(exactly = 0) { telegramFileSender.send(any(), any()) }
        verify { workDirCleaner.deleteRecursively(jobRoot(tempDir)) }
    }

    @Test
    fun rejectsVideoThatRemainsAboveLimitAfterPreparation(@TempDir tempDir: Path) = runTest {
        val job = job()
        val request = request()
        val downloadedFile = DownloadedFile(tempDir.resolve("downloaded.mp4"), 100)
        val metadata = metadata()
        every { downloadJobService.findCachedJob(job) } returns null
        coEvery { ytDlpService.extractMetadata(request) } returns metadata
        every { downloadPreflightService.check(request, metadata) } returns DownloadPreflightDecision.Allowed(request)
        every { mediaMetadataMapper.toMediaMetadata(metadata) } returns mediaMetadata()
        every { downloadJobService.markMetadata(attempt(job), any()) } returns job
        coEvery { ytDlpService.download(request, jobDir(tempDir)) } returns downloadedFile
        coEvery {
            telegramVideoPreparer.prepare(downloadedFile, jobDir(tempDir), 1)
        } throws VideoTooLargeException(uploadLimits.maxUploadBytes + 1)
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(attempt(job))

        verify { downloadJobLifecycle.rejectTooLarge(attempt(job), match { it.contains("limitMb=45.00") }) }
        coVerify(exactly = 0) { telegramFileSender.send(any(), any()) }
        verify { workDirCleaner.deleteRecursively(jobRoot(tempDir)) }
    }

    @Test
    fun rejectsYtDlpDownloadWhenSafetyLimitIsExceeded(@TempDir tempDir: Path) = runTest {
        val job = job()
        val request = request()
        val metadata = metadata()
        coEvery { ytDlpService.extractMetadata(request) } returns metadata
        every { downloadPreflightService.check(request, metadata) } returns DownloadPreflightDecision.Allowed(request)
        every { mediaMetadataMapper.toMediaMetadata(metadata) } returns mediaMetadata()
        every { downloadJobService.markMetadata(attempt(job), any()) } returns job
        coEvery {
            ytDlpService.download(request, jobDir(tempDir))
        } throws YtDlpFileSizeLimitException(uploadLimits.maxUploadBytes)
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(attempt(job))

        verify { downloadJobLifecycle.rejectTooLarge(attempt(job), match { it.contains("limitMb=45.00") }) }
        verify(exactly = 0) { downloadJobLifecycle.failOrRetry(any(), any(), any()) }
    }

    @Test
    fun rejectsInstagramDownloadWhenStreamExceedsLimit(@TempDir tempDir: Path) = runTest {
        val url = "https://www.instagram.com/reel/ABC_123/"
        val job = job(url = url)
        val request = request(url = url)
        val metadata = metadata()
        val session: PreparedDownloadSession = mockk()
        every { instagramExecutor.supports(request) } returns true
        every { session.metadata } returns metadata
        coEvery { instagramExecutor.prepare(request) } returns DownloadPreparation.Ready(session)
        every { downloadPreflightService.check(request, metadata) } returns DownloadPreflightDecision.Allowed(request)
        every { mediaMetadataMapper.toMediaMetadata(metadata) } returns mediaMetadata()
        every { downloadJobService.markMetadata(attempt(job), any()) } returns job
        coEvery {
            session.download(request, jobDir(tempDir))
        } throws InstagramMediaTooLargeException(uploadLimits.maxUploadBytes + 1)
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(attempt(job))

        verify { downloadJobLifecycle.rejectTooLarge(attempt(job), match { it.contains("limitMb=45.00") }) }
        verify(exactly = 0) { downloadJobLifecycle.failOrRetry(any(), any(), any()) }
    }

    @Test
    fun downloadsInstagramVideoWithoutYtDlp(@TempDir tempDir: Path) = runTest {
        val url = "https://www.instagram.com/reel/ABC_123/"
        val job = job(url = url)
        val request = request(url = url)
        val metadata = metadata()
        val session: PreparedDownloadSession = mockk()
        val downloadedFile = DownloadedFile(tempDir.resolve("downloaded.mp4"), 100)
        every { instagramExecutor.supports(request) } returns true
        every { session.metadata } returns metadata
        coEvery { instagramExecutor.prepare(request) } returns DownloadPreparation.Ready(session)
        every { downloadPreflightService.check(request, metadata) } returns DownloadPreflightDecision.Allowed(request)
        every { mediaMetadataMapper.toMediaMetadata(metadata) } returns mediaMetadata()
        every { downloadJobService.markMetadata(attempt(job), any()) } returns job
        coEvery { session.download(request, jobDir(tempDir)) } returns downloadedFile
        coEvery { telegramVideoPreparer.prepare(downloadedFile, jobDir(tempDir), 1) } returns downloadedFile
        coEvery { telegramFileSender.send(job, downloadedFile) } returns telegramResult()
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(attempt(job))

        coVerify(exactly = 0) { ytDlpService.extractMetadata(any()) }
        coVerify(exactly = 0) { ytDlpService.download(any(), any()) }
        coVerify(exactly = 1) { instagramExecutor.prepare(request) }
        coVerify(exactly = 1) { session.download(request, jobDir(tempDir)) }
    }

    @Test
    fun doesNotFallBackToYtDlpWhenInstagramEmbedExtractionFails(@TempDir tempDir: Path) = runTest {
        val url = "https://www.instagram.com/reel/ABC_123/"
        val job = job(url = url)
        val request = request(url = url)
        every { instagramExecutor.supports(request) } returns true
        coEvery { instagramExecutor.prepare(request) } returns DownloadPreparation.TerminalFailure("embed failed")
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(attempt(job))

        coVerify(exactly = 1) { instagramExecutor.prepare(request) }
        coVerify(exactly = 0) { ytDlpService.extractMetadata(any()) }
        coVerify(exactly = 0) { ytDlpService.download(any(), any()) }
        verify { downloadJobLifecycle.failTerminal(attempt(job), "embed failed") }
    }

    @Test
    fun reportsUnavailableInstagramContentWithoutRetry(@TempDir tempDir: Path) = runTest {
        val url = "https://www.instagram.com/reel/ABC_123/"
        val job = job(url = url)
        val request = request(url = url)
        every { instagramExecutor.supports(request) } returns true
        coEvery { instagramExecutor.prepare(request) } returns DownloadPreparation.SourceUnavailable(
            "Instagram content is unavailable without authentication"
        )
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(attempt(job))

        verify {
            downloadJobLifecycle.failSourceUnavailable(
                attempt(job),
                "Instagram content is unavailable without authentication",
            )
        }
        verify(exactly = 0) { downloadJobLifecycle.failOrRetry(any(), any(), any()) }
        verify(exactly = 0) { downloadPreflightService.check(any(), any()) }
    }

    @Test
    fun defersInstagramJobBeforeAttemptWhenLimiterIsBusy(@TempDir tempDir: Path) = runTest {
        val url = "https://www.instagram.com/reel/ABC_123/"
        val job = job(url = url)
        val request = request(url = url)
        val retryAt = Instant.parse("2026-07-15T10:00:30Z")
        every { instagramExecutor.supports(request) } returns true
        coEvery { instagramExecutor.prepare(request) } returns DownloadPreparation.NotReady(
            retryAt = retryAt,
            reason = "rate limited",
        )
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(attempt(job))

        verify { downloadJobLifecycle.deferBeforeAttempt(attempt(job), retryAt, "rate limited") }
        verify(exactly = 0) { downloadPreflightService.check(any(), any()) }
        coVerify(exactly = 0) { ytDlpService.extractMetadata(any()) }
    }

    @Test
    fun schedulesInstagramRetryAfterThrottle(@TempDir tempDir: Path) = runTest {
        val url = "https://www.instagram.com/reel/ABC_123/"
        val job = job(url = url)
        val request = request(url = url)
        val retryAt = Instant.parse("2026-07-15T10:30:00Z")
        every { instagramExecutor.supports(request) } returns true
        coEvery { instagramExecutor.prepare(request) } returns DownloadPreparation.RetryableFailure(
            retryAt = retryAt,
            reason = "Instagram throttled request",
        )
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(attempt(job))

        verify {
            downloadJobLifecycle.retryAt(
                attempt(job),
                retryAt,
                "Instagram throttled request",
            )
        }
        verify(exactly = 0) { downloadPreflightService.check(any(), any()) }
        coVerify(exactly = 0) { ytDlpService.extractMetadata(any()) }
    }

    @Test
    fun doesNotUseYtDlpForUnsupportedInstagramRequest(@TempDir tempDir: Path) = runTest {
        val url = "https://www.instagram.com/stories/user/123/"
        val job = job(outputType = OutputType.AUDIO, url = url)
        val request = request(outputType = OutputType.AUDIO, url = url)
        every { instagramExecutor.supports(request) } returns true
        coEvery { instagramExecutor.prepare(request) } returns DownloadPreparation.TerminalFailure(
            "Instagram request is not supported by embed downloader"
        )
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(attempt(job))

        coVerify(exactly = 1) { instagramExecutor.prepare(request) }
        coVerify(exactly = 0) { ytDlpService.extractMetadata(any()) }
        coVerify(exactly = 0) { ytDlpService.download(any(), any()) }
        verify {
            downloadJobLifecycle.failTerminal(
                attempt(job),
                "Instagram request is not supported by embed downloader",
            )
        }
    }

    @Test
    fun marksFailedOrRetryOnError(@TempDir tempDir: Path) = runTest {
        val job = job().apply { selectedFormat = null }
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(attempt(job))

        verify {
            downloadJobLifecycle.failOrRetry(
                attempt(job),
                "Download job selected format is missing",
                null,
            )
        }
        verify { workDirCleaner.deleteRecursively(jobRoot(tempDir)) }
    }

    @Test
    fun skipsCacheWhenCachedJobIsMissing(@TempDir tempDir: Path) = runTest {
        val job = job()
        val request = request()
        every { downloadJobService.findCachedJob(job) } returns null
        coEvery { ytDlpService.extractMetadata(request) } returns metadata()
        every { downloadPreflightService.check(request, any()) } returns DownloadPreflightDecision.Rejected("too large")
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir, telegramFileCacheEnabled = true).process(attempt(job))

        verify { downloadJobService.findCachedJob(job) }
        coVerify { ytDlpService.extractMetadata(request) }
    }

    @Test
    fun skipsCacheWhenCachedJobHasNoTelegramFileId(@TempDir tempDir: Path) = runTest {
        val job = job()
        val cachedJob = job().apply {
            telegramFileId = null
        }
        val request = request()
        every { downloadJobService.findCachedJob(job) } returns cachedJob
        coEvery { ytDlpService.extractMetadata(request) } returns metadata()
        every { downloadPreflightService.check(request, any()) } returns DownloadPreflightDecision.Rejected("too large")
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir, telegramFileCacheEnabled = true).process(attempt(job))

        verify { downloadJobService.findCachedJob(job) }
        coVerify { ytDlpService.extractMetadata(request) }
    }

    @Test
    fun downloadsAndUploadsAudioWithoutVideoPreparation(@TempDir tempDir: Path) = runTest {
        val job = job(outputType = OutputType.AUDIO)
        val markedJob = job(outputType = OutputType.AUDIO).apply {
            sourceAudioTitle = "track"
            sourceAudioPerformer = "artist"
            sourceDurationSeconds = 120
        }
        val request = request(outputType = OutputType.AUDIO)
        val downloadRequest = request.copy(
            extraArgs = listOf("-x", "--audio-format", "mp3", "--audio-quality", "40K"),
        )
        val downloadedFile = DownloadedFile(tempDir.resolve("downloaded.mp3"), 100)
        val metadata = metadata()
        every { downloadPreflightService.check(request, any()) } returns DownloadPreflightDecision.Allowed(downloadRequest)
        coEvery { ytDlpService.extractMetadata(request) } returns metadata
        every { mediaMetadataMapper.toMediaMetadata(metadata) } returns mediaMetadata()
        every { downloadJobService.markMetadata(attempt(job), any()) } returns markedJob
        coEvery { ytDlpService.download(downloadRequest, jobDir(tempDir)) } returns downloadedFile
        coEvery { telegramFileSender.send(markedJob, downloadedFile) } returns telegramResult()
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(attempt(job))

        coVerify(exactly = 0) { telegramVideoPreparer.prepare(any(), any(), any()) }
        coVerify { ytDlpService.download(downloadRequest, jobDir(tempDir)) }
        coVerify { telegramFileSender.send(markedJob, downloadedFile) }
    }

    @Test
    fun failsWhenMetadataExtractionFails(@TempDir tempDir: Path) = runTest {
        val job = job()
        val request = request()
        coEvery { ytDlpService.extractMetadata(request) } throws IllegalStateException("metadata error")
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(attempt(job))

        verify(exactly = 0) { downloadPreflightService.check(any(), any()) }
        verify(exactly = 0) { downloadJobService.markMetadata(any(), any()) }
        coVerify(exactly = 0) { ytDlpService.download(any(), any()) }
        coVerify(exactly = 0) { telegramFileSender.send(any(), any()) }
        verify { downloadJobLifecycle.failOrRetry(attempt(job), "metadata error", null) }
    }

    @Test
    fun failsAuthenticationRequiredWithoutRetry(@TempDir tempDir: Path) = runTest {
        val job = job()
        val request = request()
        coEvery { ytDlpService.extractMetadata(request) } throws YtDlpAuthenticationRequiredException("auth required")
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(attempt(job))

        verify { downloadJobLifecycle.failAuthenticationRequired(attempt(job), "auth required") }
        verify(exactly = 0) { downloadJobLifecycle.failOrRetry(any(), any(), any()) }
        verify { workDirCleaner.deleteRecursively(jobRoot(tempDir)) }
    }

    @Test
    fun propagatesCancellationWithoutChangingJobState(@TempDir tempDir: Path) = runTest {
        val job = job()
        coEvery { ytDlpService.extractMetadata(request()) } throws CancellationException("lease lost")
        every { workDirCleaner.deleteRecursively(any()) } just runs

        assertFailsWith<CancellationException> {
            processor(tempDir).process(attempt(job))
        }

        verify(exactly = 0) { downloadJobLifecycle.failOrRetry(any(), any(), any()) }
        verify { workDirCleaner.deleteRecursively(jobRoot(tempDir)) }
    }

    @Test
    fun marksMetadataWithoutDuration(@TempDir tempDir: Path) = runTest {
        val job = job()
        val request = request()
        val downloadedFile = DownloadedFile(tempDir.resolve("downloaded.mp4"), 100)
        val metadata = metadata(duration = null)
        every { downloadPreflightService.check(request, any()) } returns DownloadPreflightDecision.Allowed(request)
        coEvery { ytDlpService.extractMetadata(request) } returns metadata
        every { mediaMetadataMapper.toMediaMetadata(metadata) } returns mediaMetadata(durationSeconds = null)
        every { downloadJobService.markMetadata(attempt(job), any()) } returns job
        coEvery { ytDlpService.download(request, jobDir(tempDir)) } returns downloadedFile
        coEvery { telegramVideoPreparer.prepare(downloadedFile, jobDir(tempDir), 1) } returns downloadedFile
        coEvery { telegramFileSender.send(job, downloadedFile) } returns telegramResult()
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(attempt(job))

        verify { downloadJobService.markMetadata(attempt(job), any()) }
    }

    @Test
    fun usesExceptionClassNameWhenErrorMessageIsMissing(@TempDir tempDir: Path) = runTest {
        val job = job()
        coEvery { ytDlpService.extractMetadata(request()) } throws object : RuntimeException() {}
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(attempt(job))

        verify { downloadJobLifecycle.failOrRetry(attempt(job), any(), null) }
    }

    @Test
    fun failsWhenJobIdIsMissing(@TempDir tempDir: Path) = runTest {
        assertFailsWith<IllegalArgumentException> {
            processor(tempDir).process(attempt(job().apply { id = null }))
        }
    }

    private fun processor(
        workDir: Path,
        telegramFileCacheEnabled: Boolean = false,
    ): DownloadJobProcessor {
        return DownloadJobProcessor(
            downloadJobService = downloadJobService,
            downloadPreflightService = downloadPreflightService,
            telegramVideoPreparer = telegramVideoPreparer,
            telegramFileSender = telegramFileSender,
            downloadExecutorResolver = downloadExecutorResolver,
            mediaMetadataMapper = mediaMetadataMapper,
            downloadJobLifecycle = downloadJobLifecycle,
            downloadAnalytics = downloadAnalytics,
            workDirCleaner = workDirCleaner,
            workDirCapacityGuard = workDirCapacityGuard,
            uploadLimits = uploadLimits,
            workDir = workDir.toString(),
            telegramFileCacheEnabled = telegramFileCacheEnabled,
        )
    }

    private fun attempt(job: DownloadJob): ClaimedDownloadJob =
        ClaimedDownloadJob(job, LEASE_TOKEN)

    private fun jobDir(workDir: Path): Path =
        workDir.resolve("1").resolve(LEASE_TOKEN.toString())

    private fun jobRoot(workDir: Path): Path = workDir.resolve("1")

    private fun job(
        outputType: OutputType = OutputType.VIDEO,
        url: String = "https://example.com/video",
    ): DownloadJob {
        return DownloadJob(
            id = 1,
            telegramChatId = 100,
            originalUrl = url,
            normalizedUrl = url,
            outputType = outputType,
            downloadPreset = "preset",
            selectedFormat = "format",
            downloadExtraArgs = listOf("--arg"),
        )
    }

    private fun request(
        outputType: OutputType = OutputType.VIDEO,
        url: String = "https://example.com/video",
    ): DownloadRequest {
        return DownloadRequest(
            originalUrl = url,
            normalizedUrl = url,
            outputType = outputType,
            formatSelector = "format",
            extraArgs = listOf("--arg"),
            presetName = "preset",
        )
    }

    private fun metadata(duration: Int? = 120): YtDlpMetadataDto {
        return YtDlpMetadataDto(
            id = "id",
            title = "title",
            extractor = "youtube",
            webpageUrl = "https://example.com/video",
            thumbnail = null,
            duration = duration?.let { BigDecimal.valueOf(it.toLong()) },
            ext = "mp4",
            width = 1080,
            height = 1920,
            fps = null,
            filesize = 100,
            vcodec = null,
            acodec = null,
            filesizeApprox = null,
            formatId = "format",
            format = null,
            track = "track",
            artist = "artist",
            creator = null,
            uploader = "uploader",
            channel = "channel",
            requestedFormats = null,
        )
    }

    private fun mediaMetadata(durationSeconds: Long? = 120): MediaMetadata {
        return MediaMetadata(
            title = "title",
            extractor = "youtube",
            durationSeconds = durationSeconds,
            audioTitle = "track",
            audioPerformer = "artist",
            width = 1080,
            height = 1920,
            webpageUrl = "https://example.com/video",
        )
    }

    private fun telegramResult(): TelegramFileSendResult {
        return TelegramFileSendResult(
            telegramFileId = "file-id",
            telegramFileSize = 90,
            downloadedFileSize = 100,
        )
    }

    private companion object {
        val LEASE_TOKEN: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
