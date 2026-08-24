package com.nkudrin713.kradnik.download.service

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadJobStatus
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.executor.DownloadStrategy
import com.nkudrin713.kradnik.download.repository.DownloadJobRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
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
    fun createsJobAtomicallyForTelegramUpdate() {
        every { repository.lockTelegramUpdate(3) } returns 1
        every { repository.findByTelegramUpdateId(3) } returns null
        every { repository.save(any()) } answers { firstArg() }

        val actual = service.createJob(command())

        assertTrue(actual is CreateDownloadJobResult.Created)
        assertEquals(1, actual.job.telegramUserId)
        assertEquals(2, actual.job.telegramChatId)
        assertEquals(3, actual.job.telegramUpdateId)
        assertEquals(4, actual.job.telegramRequestMessageId)
        assertEquals("https://example.com/raw", actual.job.originalUrl)
        assertEquals("https://example.com/normalized", actual.job.normalizedUrl)
        assertEquals("cache-key", actual.job.cacheKey)
        assertEquals(OutputType.AUDIO, actual.job.outputType)
        assertEquals(DownloadStrategy.YOUTUBE_YT_DLP, actual.job.downloadStrategy)
        assertEquals("preset", actual.job.downloadPreset)
        assertEquals("format", actual.job.selectedFormat)
        assertEquals(listOf("-x", "--audio-format", "mp3"), actual.job.downloadExtraArgs)
        assertEquals(10, actual.job.telegramStatusMessageId)
        verify { repository.lockTelegramUpdate(3) }
    }

    @Test
    fun returnsExistingJobForRepeatedTelegramUpdate() {
        val existing = job().apply { telegramUpdateId = 3 }
        every { repository.lockTelegramUpdate(3) } returns 1
        every { repository.findByTelegramUpdateId(3) } returns existing

        val actual = service.createJob(command())

        assertTrue(actual is CreateDownloadJobResult.Existing)
        assertEquals(existing, actual.job)
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun marksCompleted() {
        val job = job()
        val attempt = attempt(job)
        val downloadedAt = Instant.parse("2026-01-01T00:00:00Z")
        every { repository.markOwnedCompleted(1, LEASE_TOKEN, "file-id", 100, 200, downloadedAt) } returns 1

        val actual = service.markCompleted(
            attempt = attempt,
            result = DownloadedFileResult(
                telegramFileId = "file-id",
                telegramFileSize = 100,
                downloadedFileSize = 200,
                downloadedAt = downloadedAt,
            ),
        )

        assertEquals(DownloadJobStatus.COMPLETED, actual.status)
        assertEquals("file-id", actual.telegramFileId)
        assertEquals(100, actual.telegramFileSize)
        assertEquals(200, actual.downloadedFileSize)
        assertEquals(downloadedAt, actual.downloadedAt)
        assertNotNull(actual.completedAt)
    }

    @Test
    fun retriesFailedJobWhenAttemptsRemain() {
        val job = job(attempts = 1)
        val retryAt = Instant.parse("2026-01-01T01:00:00Z")
        every { repository.requeueOwnedJob(1, LEASE_TOKEN, "failure", retryAt) } returns 1

        val actual = service.retryAt(attempt(job), "failure", retryAt)

        assertTrue(actual is DownloadFailureResolution.RetryScheduled)
        assertEquals(DownloadJobStatus.QUEUED, actual.job.status)
        assertEquals(retryAt, actual.job.nextAttemptAt)
        assertEquals("failure", actual.job.errorMessage)
    }

    @Test
    fun failsJobWhenAttemptsExhausted() {
        val job = job(attempts = 3)
        val retryAt = Instant.parse("2026-01-01T01:00:00Z")
        every { repository.failOwnedJob(1, LEASE_TOKEN, "failure") } returns 1

        val actual = service.retryAt(attempt(job), "failure", retryAt)

        assertTrue(actual is DownloadFailureResolution.TerminalFailure)
        assertEquals(DownloadJobStatus.FAILED, actual.job.status)
        assertEquals("failure", actual.job.errorMessage)
        assertNotNull(actual.job.completedAt)
    }

    @Test
    fun defersWithoutConsumingAttempt() {
        val retryAt = Instant.parse("2026-01-01T01:00:00Z")
        val job = job(attempts = 1)
        every { repository.deferOwnedJob(1, LEASE_TOKEN, "rate limited", retryAt) } returns 1

        val actual = service.deferBeforeAttempt(attempt(job), retryAt, "rate limited")

        assertEquals(DownloadJobStatus.QUEUED, actual.status)
        assertEquals(0, actual.attempts)
        assertEquals(retryAt, actual.nextAttemptAt)
        assertEquals("rate limited", actual.errorMessage)
    }

    @Test
    fun marksMetadata() {
        val job = job()
        val metadata = MediaMetadata(
            title = "title",
            extractor = "youtube",
            durationSeconds = 120,
            audioTitle = "audio title",
            audioPerformer = "artist",
            width = 1080,
            height = 1920,
            webpageUrl = "https://example.com",
        )
        every {
            repository.updateOwnedMetadata(
                1,
                LEASE_TOKEN,
                "title",
                "youtube",
                120,
                "audio title",
                "artist",
            )
        } returns 1

        val actual = service.markMetadata(attempt(job), metadata)

        assertEquals("title", actual.sourceTitle)
        assertEquals("youtube", actual.sourceExtractor)
        assertEquals(120, actual.sourceDurationSeconds)
        assertEquals("audio title", actual.sourceAudioTitle)
        assertEquals("artist", actual.sourceAudioPerformer)
    }

    @Test
    fun marksUploading() {
        val job = job()
        every { repository.markOwnedUploading(1, LEASE_TOKEN) } returns 1

        val actual = service.markUploading(attempt(job))

        assertEquals(DownloadJobStatus.UPLOADING, actual.status)
        assertNotNull(actual.uploadingStartedAt)
    }

    @Test
    fun marksFailed() {
        val job = job()
        every { repository.failOwnedJob(1, LEASE_TOKEN, "failure") } returns 1

        val actual = service.markFailed(attempt(job), "failure")

        assertEquals(DownloadJobStatus.FAILED, actual.status)
        assertEquals("failure", actual.errorMessage)
        assertNotNull(actual.completedAt)
    }

    @Test
    fun rejectsStateChangeAfterLeaseIsLost() {
        every { repository.markOwnedUploading(1, LEASE_TOKEN) } returns 0

        assertFailsWith<DownloadJobLeaseLostException> {
            service.markUploading(attempt(job()))
        }
    }

    @Test
    fun findsCachedJob() {
        val job = job().apply { cacheKey = "cache-key" }
        val cachedJob = job()
        every { repository.findCachedCompletedJob("cache-key") } returns cachedJob

        val actual = service.findCachedJob(job)

        assertEquals(cachedJob, actual)
    }

    @Test
    fun claimsNextQueuedJob() {
        val job = job()
        every { repository.claimNextQueuedJob(3, LEASE_TOKEN, 300_000) } returns job

        val actual = service.claimNextQueuedJob(LEASE_TOKEN, 300_000)

        assertEquals(ClaimedDownloadJob(job, LEASE_TOKEN), actual)
    }

    @Test
    fun renewsOwnedLease() {
        every { repository.renewLease(1, LEASE_TOKEN, 300_000) } returns 1

        val renewed = service.renewLease(1, LEASE_TOKEN, 300_000)

        assertTrue(renewed)
    }

    @Test
    fun recoversExpiredLeases() {
        every { repository.requeueStaleInProgressJobs(3) } returns 2
        every { repository.failStaleInProgressJobs(3) } returns 1

        val actual = service.recoverExpiredLeases()

        assertEquals(2, actual.requeued)
        assertEquals(1, actual.failed)
    }

    private fun command(): CreateDownloadJobCommand {
        return CreateDownloadJobCommand(
            telegramUserId = 1,
            telegramChatId = 2,
            telegramUpdateId = 3,
            telegramRequestMessageId = 4,
            originalUrl = "https://example.com/raw",
            normalizedUrl = "https://example.com/normalized",
            cacheKey = "cache-key",
            outputType = OutputType.AUDIO,
            downloadStrategy = DownloadStrategy.YOUTUBE_YT_DLP,
            downloadPreset = "preset",
            selectedFormat = "format",
            downloadExtraArgs = listOf("-x", "--audio-format", "mp3"),
            telegramStatusMessageId = 10,
        )
    }

    private fun job(attempts: Int = 0): DownloadJob {
        return DownloadJob(
            id = 1,
            telegramChatId = 2,
            attempts = attempts,
        )
    }

    private fun attempt(job: DownloadJob): ClaimedDownloadJob =
        ClaimedDownloadJob(job, LEASE_TOKEN)

    private companion object {
        val LEASE_TOKEN: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
