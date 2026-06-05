package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.limit.DownloadPreflightDecision
import com.nkudrin713.kradnik.download.limit.DownloadPreflightService
import com.nkudrin713.kradnik.download.request.DownloadRequest
import com.nkudrin713.kradnik.download.request.DownloadRequestFactory
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.service.DownloadedFileResult
import com.nkudrin713.kradnik.download.telegram.TelegramFileSendResult
import com.nkudrin713.kradnik.download.telegram.TelegramFileSender
import com.nkudrin713.kradnik.download.video.TelegramVideoPreparer
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DownloadJobProcessorTest {
    private val downloadJobService: DownloadJobService = mockk()
    private val downloadRequestFactory: DownloadRequestFactory = mockk()
    private val downloadPreflightService: DownloadPreflightService = mockk()
    private val telegramVideoPreparer: TelegramVideoPreparer = mockk()
    private val telegramFileSender: TelegramFileSender = mockk()
    private val ytDlpService: YtDlpService = mockk()
    private val statusReporter: DownloadStatusReporter = mockk()
    private val workDirCleaner: WorkDirCleaner = mockk()

    @Test
    fun completesCachedJob(@TempDir tempDir: Path) = runTest {
        val job = job()
        val cachedJob = job().apply {
            telegramFileId = "cached-file-id"
            downloadedFileSize = 100
        }
        every { downloadJobService.findCachedJob(job) } returns cachedJob
        every { statusReporter.setStatus(any(), any()) } just runs
        every { telegramFileSender.sendCached(job, "cached-file-id", 100) } returns telegramResult()
        every { downloadJobService.markCompleted(1, any<DownloadedFileResult>()) } returns job
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir, telegramFileCacheEnabled = true).process(job)

        verify { telegramFileSender.sendCached(job, "cached-file-id", 100) }
        verify { downloadJobService.markCompleted(1, any<DownloadedFileResult>()) }
        verify { statusReporter.setStatus(job, TelegramDownloadStatus.COMPLETED) }
        verify { workDirCleaner.deleteRecursively(tempDir.resolve("1")) }
    }

    @Test
    fun rejectsWhenPreflightRejects(@TempDir tempDir: Path) = runTest {
        val job = job()
        val request = request()
        every { downloadJobService.findCachedJob(job) } returns null
        every { downloadRequestFactory.create(job) } returns request
        coEvery { downloadPreflightService.check(request) } returns DownloadPreflightDecision.Rejected("too large")
        every { downloadJobService.markFailed(1, "too large") } returns job
        every { statusReporter.setStatus(any(), any()) } just runs
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(job)

        verify { downloadJobService.markFailed(1, "too large") }
        verify { statusReporter.setStatus(job, TelegramDownloadStatus.REJECTED_TOO_LARGE) }
        verify { workDirCleaner.deleteRecursively(tempDir.resolve("1")) }
    }

    @Test
    fun downloadsAndUploadsJob(@TempDir tempDir: Path) = runTest {
        val job = job()
        val request = request()
        val downloadedFile = DownloadedFile(tempDir.resolve("downloaded.mp4"), 100)
        val preparedFile = DownloadedFile(tempDir.resolve("prepared.mp4"), 90)
        every { downloadJobService.findCachedJob(job) } returns null
        every { downloadRequestFactory.create(job) } returns request
        coEvery { downloadPreflightService.check(request) } returns DownloadPreflightDecision.Allowed
        every { statusReporter.setStatus(any(), any()) } just runs
        coEvery { ytDlpService.extractMetadata(request) } returns metadata()
        every { downloadJobService.markMetadata(1, any()) } returns job
        coEvery { ytDlpService.download(request, tempDir.resolve("1")) } returns downloadedFile
        coEvery { telegramVideoPreparer.prepare(downloadedFile, tempDir.resolve("1"), 1) } returns preparedFile
        every { downloadJobService.markUploading(1) } returns job
        coEvery { telegramFileSender.send(job, preparedFile) } returns telegramResult()
        every { downloadJobService.markCompleted(1, any<DownloadedFileResult>()) } returns job
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(job)

        verify { statusReporter.setStatus(job, TelegramDownloadStatus.DOWNLOADING) }
        verify { downloadJobService.markMetadata(1, any()) }
        coVerify { telegramVideoPreparer.prepare(downloadedFile, tempDir.resolve("1"), 1) }
        coVerify { telegramFileSender.send(job, preparedFile) }
        verify { statusReporter.setStatus(job, TelegramDownloadStatus.COMPLETED) }
        verify { workDirCleaner.deleteRecursively(tempDir.resolve("1")) }
    }

    @Test
    fun marksFailedOrRetryOnError(@TempDir tempDir: Path) = runTest {
        val job = job()
        every { downloadRequestFactory.create(job) } throws IllegalStateException("boom")
        every { downloadJobService.markFailedOrRetry(1, "boom") } returns job
        every { statusReporter.setStatus(any(), any()) } just runs
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(job)

        verify { downloadJobService.markFailedOrRetry(1, "boom") }
        verify { statusReporter.setStatus(job, TelegramDownloadStatus.ERROR) }
        verify { workDirCleaner.deleteRecursively(tempDir.resolve("1")) }
    }

    @Test
    fun skipsCacheWhenCachedJobIsMissing(@TempDir tempDir: Path) = runTest {
        val job = job()
        val request = request()
        every { downloadJobService.findCachedJob(job) } returns null
        every { downloadRequestFactory.create(job) } returns request
        coEvery { downloadPreflightService.check(request) } returns DownloadPreflightDecision.Rejected("too large")
        every { downloadJobService.markFailed(1, "too large") } returns job
        every { statusReporter.setStatus(any(), any()) } just runs
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir, telegramFileCacheEnabled = true).process(job)

        verify { downloadJobService.findCachedJob(job) }
        verify { downloadRequestFactory.create(job) }
    }

    @Test
    fun skipsCacheWhenCachedJobHasNoTelegramFileId(@TempDir tempDir: Path) = runTest {
        val job = job()
        val cachedJob = job().apply {
            telegramFileId = null
        }
        val request = request()
        every { downloadJobService.findCachedJob(job) } returns cachedJob
        every { downloadRequestFactory.create(job) } returns request
        coEvery { downloadPreflightService.check(request) } returns DownloadPreflightDecision.Rejected("too large")
        every { downloadJobService.markFailed(1, "too large") } returns job
        every { statusReporter.setStatus(any(), any()) } just runs
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir, telegramFileCacheEnabled = true).process(job)

        verify { downloadJobService.findCachedJob(job) }
        verify { downloadRequestFactory.create(job) }
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
        val downloadedFile = DownloadedFile(tempDir.resolve("downloaded.mp3"), 100)
        every { downloadRequestFactory.create(job) } returns request
        coEvery { downloadPreflightService.check(request) } returns DownloadPreflightDecision.Allowed
        every { statusReporter.setStatus(any(), any()) } just runs
        coEvery { ytDlpService.extractMetadata(request) } returns metadata()
        every { downloadJobService.markMetadata(1, any()) } returns markedJob
        coEvery { ytDlpService.download(request, tempDir.resolve("1")) } returns downloadedFile
        every { downloadJobService.markUploading(1) } returns job
        coEvery { telegramFileSender.send(markedJob, downloadedFile) } returns telegramResult()
        every { downloadJobService.markCompleted(1, any<DownloadedFileResult>()) } returns job
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(job)

        coVerify(exactly = 0) { telegramVideoPreparer.prepare(any(), any(), any()) }
        coVerify { telegramFileSender.send(markedJob, downloadedFile) }
    }

    @Test
    fun continuesWhenMetadataExtractionFails(@TempDir tempDir: Path) = runTest {
        val job = job()
        val request = request()
        val downloadedFile = DownloadedFile(tempDir.resolve("downloaded.mp4"), 100)
        every { downloadRequestFactory.create(job) } returns request
        coEvery { downloadPreflightService.check(request) } returns DownloadPreflightDecision.Allowed
        every { statusReporter.setStatus(any(), any()) } just runs
        coEvery { ytDlpService.extractMetadata(request) } throws IllegalStateException("metadata error")
        coEvery { ytDlpService.download(request, tempDir.resolve("1")) } returns downloadedFile
        coEvery { telegramVideoPreparer.prepare(downloadedFile, tempDir.resolve("1"), 1) } returns downloadedFile
        every { downloadJobService.markUploading(1) } returns job
        coEvery { telegramFileSender.send(job, downloadedFile) } returns telegramResult()
        every { downloadJobService.markCompleted(1, any<DownloadedFileResult>()) } returns job
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(job)

        verify(exactly = 0) { downloadJobService.markMetadata(any(), any()) }
        coVerify { telegramFileSender.send(job, downloadedFile) }
    }

    @Test
    fun marksMetadataWithoutDuration(@TempDir tempDir: Path) = runTest {
        val job = job()
        val request = request()
        val downloadedFile = DownloadedFile(tempDir.resolve("downloaded.mp4"), 100)
        every { downloadRequestFactory.create(job) } returns request
        coEvery { downloadPreflightService.check(request) } returns DownloadPreflightDecision.Allowed
        every { statusReporter.setStatus(any(), any()) } just runs
        coEvery { ytDlpService.extractMetadata(request) } returns metadata(duration = null)
        every { downloadJobService.markMetadata(1, any()) } returns job
        coEvery { ytDlpService.download(request, tempDir.resolve("1")) } returns downloadedFile
        coEvery { telegramVideoPreparer.prepare(downloadedFile, tempDir.resolve("1"), 1) } returns downloadedFile
        every { downloadJobService.markUploading(1) } returns job
        coEvery { telegramFileSender.send(job, downloadedFile) } returns telegramResult()
        every { downloadJobService.markCompleted(1, any<DownloadedFileResult>()) } returns job
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(job)

        verify { downloadJobService.markMetadata(1, any()) }
    }

    @Test
    fun usesExceptionClassNameWhenErrorMessageIsMissing(@TempDir tempDir: Path) = runTest {
        val job = job()
        every { downloadRequestFactory.create(job) } throws object : RuntimeException() {}
        every { downloadJobService.markFailedOrRetry(1, any()) } returns job
        every { statusReporter.setStatus(any(), any()) } just runs
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(job)

        verify { downloadJobService.markFailedOrRetry(1, any()) }
    }

    @Test
    fun failsWhenJobIdIsMissing(@TempDir tempDir: Path) = runTest {
        assertFailsWith<IllegalArgumentException> {
            processor(tempDir).process(job().apply { id = null })
        }
    }

    private fun processor(
        workDir: Path,
        telegramFileCacheEnabled: Boolean = false,
    ): DownloadJobProcessor {
        return DownloadJobProcessor(
            downloadJobService = downloadJobService,
            downloadRequestFactory = downloadRequestFactory,
            downloadPreflightService = downloadPreflightService,
            telegramVideoPreparer = telegramVideoPreparer,
            telegramFileSender = telegramFileSender,
            ytDlpService = ytDlpService,
            statusReporter = statusReporter,
            workDirCleaner = workDirCleaner,
            workDir = workDir.toString(),
            telegramFileCacheEnabled = telegramFileCacheEnabled,
        )
    }

    private fun job(outputType: OutputType = OutputType.VIDEO): DownloadJob {
        return DownloadJob(
            id = 1,
            telegramChatId = 100,
            originalUrl = "https://example.com/video",
            normalizedUrl = "https://example.com/video",
            outputType = outputType,
        )
    }

    private fun request(outputType: OutputType = OutputType.VIDEO): DownloadRequest {
        return DownloadRequest(
            originalUrl = "https://example.com/video",
            normalizedUrl = "https://example.com/video",
            outputType = outputType,
            formatSelector = "format",
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

    private fun telegramResult(): TelegramFileSendResult {
        return TelegramFileSendResult(
            telegramFileId = "file-id",
            telegramFileSize = 90,
            downloadedFileSize = 100,
        )
    }
}
