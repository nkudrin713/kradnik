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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class DownloadJobServiceTest {
    private val repository: DownloadJobRepository = mockk()
    private val service = DownloadJobService(repository)

    @Test
    fun createsJob() {
        every { repository.save(any()) } answers { firstArg() }

        val actual = service.createJob(
            CreateDownloadJobCommand(
                telegramUserId = 1,
                telegramChatId = 2,
                originalUrl = "https://example.com/raw",
                normalizedUrl = "https://example.com/normalized",
                outputType = OutputType.AUDIO,
                downloadPreset = "preset",
                selectedFormat = "format",
                telegramStatusMessageId = 10,
            )
        )

        assertEquals(1, actual.telegramUserId)
        assertEquals(2, actual.telegramChatId)
        assertEquals("https://example.com/raw", actual.originalUrl)
        assertEquals("https://example.com/normalized", actual.normalizedUrl)
        assertEquals(OutputType.AUDIO, actual.outputType)
        assertEquals("preset", actual.downloadPreset)
        assertEquals("format", actual.selectedFormat)
        assertEquals(10, actual.telegramStatusMessageId)
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

        assertEquals(DownloadJobStatus.QUEUED, actual.status)
        assertEquals("failure", actual.errorMessage)
    }

    @Test
    fun failsJobWhenAttemptsExhausted() {
        val job = job(attempts = 3)
        every { repository.findById(1) } returns Optional.of(job)

        val actual = service.markFailedOrRetry(1, "failure")

        assertEquals(DownloadJobStatus.FAILED, actual.status)
        assertEquals("failure", actual.errorMessage)
        assertNotNull(actual.completedAt)
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
            outputType = OutputType.VIDEO
        }
        val cachedJob = job()
        every { repository.findCachedCompletedJob("https://example.com/video", OutputType.VIDEO) } returns cachedJob

        val actual = service.findCachedJob(job)

        assertEquals(cachedJob, actual)
    }

    @Test
    fun claimsNextQueuedJob() {
        val job = job()
        every { repository.claimNextQueuedJob(3) } returns job

        val actual = service.claimNextQueuedJob()

        assertEquals(job, actual)
    }

    @Test
    fun recoversStaleJobs() {
        val staleBefore = Instant.parse("2026-01-01T00:00:00Z")
        every { repository.requeueStaleInProgressJobs(staleBefore, 3) } returns 2
        every { repository.failStaleInProgressJobs(staleBefore, 3) } returns 1

        val actual = service.recoverStaleInProgressJobs(staleBefore)

        assertEquals(2, actual.requeued)
        assertEquals(1, actual.failed)
        verify { repository.requeueStaleInProgressJobs(staleBefore, 3) }
        verify { repository.failStaleInProgressJobs(staleBefore, 3) }
    }

    @Test
    fun recoversNoStaleJobs() {
        val staleBefore = Instant.parse("2026-01-01T00:00:00Z")
        every { repository.requeueStaleInProgressJobs(staleBefore, 3) } returns 0
        every { repository.failStaleInProgressJobs(staleBefore, 3) } returns 0

        val actual = service.recoverStaleInProgressJobs(staleBefore)

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
