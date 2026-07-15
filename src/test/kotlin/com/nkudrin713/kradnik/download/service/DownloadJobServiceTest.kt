package com.nkudrin713.kradnik.download.service

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadJobStatus
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.repository.DownloadJobRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.Optional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DownloadJobServiceTest {
    private val repository: DownloadJobRepository = mockk()
    private val service = DownloadJobService(repository)

    @Test
    fun createsJob() {
        every { repository.findByTelegramUpdateId(3) } returns null
        every { repository.save(any()) } answers { firstArg() }

        val actual = service.createJob(
            CreateDownloadJobCommand(
                telegramUserId = 1,
                telegramChatId = 2,
                telegramUpdateId = 3,
                originalUrl = "https://example.com/raw",
                normalizedUrl = "https://example.com/normalized",
                cacheKey = "cache-key",
                outputType = OutputType.AUDIO,
                downloadPreset = "preset",
                selectedFormat = "format",
                downloadExtraArgs = listOf("-x", "--audio-format", "mp3"),
                telegramStatusMessageId = 10,
            )
        )

        assertTrue(actual is CreateDownloadJobResult.Created)
        assertEquals(1, actual.job.telegramUserId)
        assertEquals(2, actual.job.telegramChatId)
        assertEquals(3, actual.job.telegramUpdateId)
        assertEquals("https://example.com/raw", actual.job.originalUrl)
        assertEquals("https://example.com/normalized", actual.job.normalizedUrl)
        assertEquals("cache-key", actual.job.cacheKey)
        assertEquals(OutputType.AUDIO, actual.job.outputType)
        assertEquals("preset", actual.job.downloadPreset)
        assertEquals("format", actual.job.selectedFormat)
        assertEquals(listOf("-x", "--audio-format", "mp3"), actual.job.downloadExtraArgs)
        assertEquals(10, actual.job.telegramStatusMessageId)
    }

    @Test
    fun returnsExistingJobForRepeatedTelegramUpdate() {
        val existing = job().apply { telegramUpdateId = 3 }
        every { repository.findByTelegramUpdateId(3) } returns existing

        val actual = service.createJob(
            CreateDownloadJobCommand(
                telegramUserId = 1,
                telegramChatId = 2,
                telegramUpdateId = 3,
                originalUrl = "https://example.com/raw",
                normalizedUrl = "https://example.com/normalized",
                cacheKey = "cache-key",
                outputType = OutputType.VIDEO,
                downloadPreset = "preset",
                selectedFormat = "format",
            )
        )

        assertTrue(actual is CreateDownloadJobResult.Existing)
        assertEquals(existing, actual.job)
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun marksCompleted() {
        val job = job()
        val downloadedAt = Instant.parse("2026-01-01T00:00:00Z")
        every { repository.findById(1) } returns Optional.of(job)

        val actual = service.markCompleted(
            jobId = 1,
            result = DownloadedFileResult(
                telegramFileId = "file-id",
                telegramFileSize = 100,
                downloadedFileSize = 200,
                downloadedAt = downloadedAt,
            )
        )

        assertEquals(DownloadJobStatus.COMPLETED, actual.status)
        assertEquals("file-id", actual.telegramFileId)
        assertEquals(100, actual.telegramFileSize)
        assertEquals(200, actual.downloadedFileSize)
        assertEquals(null, actual.errorMessage)
        assertEquals(downloadedAt, actual.downloadedAt)
        assertNotNull(actual.completedAt)
    }

    @Test
    fun marksCompletedWithCurrentDownloadTimeWhenResultHasNoDownloadTime() {
        val job = job()
        every { repository.findById(1) } returns Optional.of(job)

        val actual = service.markCompleted(
            jobId = 1,
            result = DownloadedFileResult(
                telegramFileId = "file-id",
                telegramFileSize = 100,
                downloadedFileSize = 200,
            )
        )

        assertNotNull(actual.downloadedAt)
    }

    @Test
    fun retriesFailedJobWhenAttemptsRemain() {
        val job = job(attempts = 1)
        every { repository.findById(1) } returns Optional.of(job)

        val actual = service.markFailedOrRetry(1, "failure")

        assertTrue(actual is DownloadFailureResolution.RetryScheduled)
        assertEquals(DownloadJobStatus.QUEUED, actual.job.status)
        assertEquals("failure", actual.job.errorMessage)
    }

    @Test
    fun schedulesRetryAtRequestedTime() {
        val job = job(attempts = 1)
        val retryAt = Instant.parse("2026-01-01T01:00:00Z")
        every { repository.findById(1) } returns Optional.of(job)

        val actual = service.retryAt(1, "throttled", retryAt)

        assertTrue(actual is DownloadFailureResolution.RetryScheduled)
        assertEquals(DownloadJobStatus.QUEUED, actual.job.status)
        assertEquals(1, actual.job.attempts)
        assertEquals(retryAt, actual.job.nextAttemptAt)
        assertEquals("throttled", actual.job.errorMessage)
    }

    @Test
    fun defersWithoutConsumingAttempt() {
        val leaseToken = UUID.randomUUID()
        val retryAt = Instant.parse("2026-01-01T01:00:00Z")
        val job = job(attempts = 1).apply {
            status = DownloadJobStatus.PROCESSING
            this.leaseToken = leaseToken
            leaseExpiresAt = retryAt
        }
        every { repository.findById(1) } returns Optional.of(job)

        val actual = service.deferBeforeAttempt(1, retryAt, "rate limited")

        assertEquals(DownloadJobStatus.QUEUED, actual.status)
        assertEquals(0, actual.attempts)
        assertEquals(retryAt, actual.nextAttemptAt)
        assertEquals("rate limited", actual.errorMessage)
        assertEquals(null, actual.leaseToken)
        assertEquals(null, actual.leaseExpiresAt)
    }

    @Test
    fun failsJobWhenAttemptsExhausted() {
        val job = job(attempts = 3)
        every { repository.findById(1) } returns Optional.of(job)

        val actual = service.markFailedOrRetry(1, "failure")

        assertTrue(actual is DownloadFailureResolution.TerminalFailure)
        assertEquals(DownloadJobStatus.FAILED, actual.job.status)
        assertEquals("failure", actual.job.errorMessage)
        assertNotNull(actual.job.completedAt)
    }

    @Test
    fun marksMetadata() {
        val job = job()
        every { repository.findById(1) } returns Optional.of(job)

        val actual = service.markMetadata(
            jobId = 1,
            metadata = MediaMetadata(
                title = "title",
                extractor = "youtube",
                durationSeconds = 120,
                audioTitle = "audio title",
                audioPerformer = "artist",
                width = 1080,
                height = 1920,
                webpageUrl = "https://example.com",
            )
        )

        assertEquals("title", actual.sourceTitle)
        assertEquals("youtube", actual.sourceExtractor)
        assertEquals(120, actual.sourceDurationSeconds)
        assertEquals("audio title", actual.sourceAudioTitle)
        assertEquals("artist", actual.sourceAudioPerformer)
    }

    @Test
    fun marksMetadataWithoutDuration() {
        val job = job()
        every { repository.findById(1) } returns Optional.of(job)

        val actual = service.markMetadata(
            jobId = 1,
            metadata = MediaMetadata(
                title = "title",
                extractor = "youtube",
                durationSeconds = null,
                audioTitle = null,
                audioPerformer = null,
                width = null,
                height = null,
                webpageUrl = "https://example.com",
            )
        )

        assertEquals(null, actual.sourceDurationSeconds)
    }

    @Test
    fun marksUploading() {
        val job = job()
        every { repository.findById(1) } returns Optional.of(job)

        val actual = service.markUploading(1)

        assertEquals(DownloadJobStatus.UPLOADING, actual.status)
        assertNotNull(actual.uploadingStartedAt)
    }

    @Test
    fun marksFailed() {
        val job = job()
        every { repository.findById(1) } returns Optional.of(job)

        val actual = service.markFailed(1, "failure")

        assertEquals(DownloadJobStatus.FAILED, actual.status)
        assertEquals("failure", actual.errorMessage)
        assertNotNull(actual.completedAt)
    }

    @Test
    fun findsCachedJob() {
        val job = job().apply {
            normalizedUrl = "https://example.com/video"
            cacheKey = "cache-key"
            outputType = OutputType.VIDEO
        }
        val cachedJob = job()
        every { repository.findCachedCompletedJob("cache-key") } returns cachedJob

        val actual = service.findCachedJob(job)

        assertEquals(cachedJob, actual)
    }

    @Test
    fun claimsNextQueuedJob() {
        val job = job()
        val leaseToken = UUID.randomUUID()
        val leaseExpiresAt = Instant.parse("2026-01-01T01:00:00Z")
        every { repository.claimNextQueuedJob(3, leaseToken, leaseExpiresAt) } returns job

        val actual = service.claimNextQueuedJob(leaseToken, leaseExpiresAt)

        assertEquals(job, actual)
    }

    @Test
    fun renewsOwnedLease() {
        val leaseToken = UUID.randomUUID()
        val leaseExpiresAt = Instant.parse("2026-01-01T01:00:00Z")
        every { repository.renewLease(1, leaseToken, leaseExpiresAt) } returns 1

        val renewed = service.renewLease(1, leaseToken, leaseExpiresAt)

        assertTrue(renewed)
    }

    @Test
    fun recoversExpiredLeases() {
        val expiredBefore = Instant.parse("2026-01-01T00:00:00Z")
        every { repository.requeueStaleInProgressJobs(expiredBefore, 3) } returns 2
        every { repository.failStaleInProgressJobs(expiredBefore, 3) } returns 1

        val actual = service.recoverExpiredLeases(expiredBefore)

        assertEquals(2, actual.requeued)
        assertEquals(1, actual.failed)
        verify { repository.requeueStaleInProgressJobs(expiredBefore, 3) }
        verify { repository.failStaleInProgressJobs(expiredBefore, 3) }
    }

    @Test
    fun recoversNoExpiredLeases() {
        val expiredBefore = Instant.parse("2026-01-01T00:00:00Z")
        every { repository.requeueStaleInProgressJobs(expiredBefore, 3) } returns 0
        every { repository.failStaleInProgressJobs(expiredBefore, 3) } returns 0

        val actual = service.recoverExpiredLeases(expiredBefore)

        assertEquals(0, actual.requeued)
        assertEquals(0, actual.failed)
    }

    @Test
    fun getsJob() {
        val job = job()
        every { repository.findById(1) } returns Optional.of(job)

        val actual = service.getJob(1)

        assertEquals(job, actual)
    }

    @Test
    fun throwsWhenJobMissing() {
        every { repository.findById(1) } returns Optional.empty()

        assertFailsWith<DownloadJobNotFoundException> {
            service.getJob(1)
        }
    }

    private fun job(attempts: Int = 0): DownloadJob {
        return DownloadJob(
            id = 1,
            telegramChatId = 2,
            attempts = attempts,
        )
    }
}
