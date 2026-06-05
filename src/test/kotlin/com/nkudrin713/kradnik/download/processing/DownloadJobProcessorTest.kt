package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.limit.DownloadPreflightDecision
import com.nkudrin713.kradnik.download.limit.DownloadPreflightService
import com.nkudrin713.kradnik.download.request.DownloadRequest
import com.nkudrin713.kradnik.download.request.DownloadRequestFactory
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.telegram.TelegramFileSendResult
import com.nkudrin713.kradnik.download.telegram.TelegramFileSender
import com.nkudrin713.kradnik.download.video.TelegramVideoPreparer
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
    private val mediaMetadataMapper: MediaMetadataMapper = mockk()
    private val downloadJobLifecycle: DownloadJobLifecycle = mockk(relaxed = true)
    private val workDirCleaner: WorkDirCleaner = mockk()

    @Test
    fun completesCachedJob(@TempDir tempDir: Path) = runTest {
        val job = job()
        val cachedJob = job().apply {
            telegramFileId = "cached-file-id"
            downloadedFileSize = 100
        }
        every { downloadJobService.findCachedJob(job) } returns cachedJob
        every { telegramFileSender.sendCached(job, "cached-file-id", 100) } returns telegramResult()
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir, telegramFileCacheEnabled = true).process(job)

        verify { telegramFileSender.sendCached(job, "cached-file-id", 100) }
        verify { downloadJobLifecycle.markUploading(job) }
        verify { downloadJobLifecycle.complete(job, any()) }
        verify { workDirCleaner.deleteRecursively(tempDir.resolve("1")) }
    }

    @Test
    fun rejectsWhenPreflightRejects(@TempDir tempDir: Path) = runTest {
        val job = job()
        val request = request()
        every { downloadJobService.findCachedJob(job) } returns null
        every { downloadRequestFactory.create(job) } returns request
        coEvery { ytDlpService.extractMetadata(request) } returns metadata()
        every { downloadPreflightService.check(request, any()) } returns DownloadPreflightDecision.Rejected("too large")
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(job)

        verify { downloadJobLifecycle.rejectTooLarge(job, "too large") }
        verify { workDirCleaner.deleteRecursively(tempDir.resolve("1")) }
    }

    @Test
    fun downloadsAndUploadsJob(@TempDir tempDir: Path) = runTest {
        val job = job()
        val request = request()
        val downloadedFile = DownloadedFile(tempDir.resolve("downloaded.mp4"), 100)
        val preparedFile = DownloadedFile(tempDir.resolve("prepared.mp4"), 90)
        val metadata = metadata()
        every { downloadJobService.findCachedJob(job) } returns null
        every { downloadRequestFactory.create(job) } returns request
        every { downloadPreflightService.check(request, any()) } returns DownloadPreflightDecision.Allowed
        coEvery { ytDlpService.extractMetadata(request) } returns metadata
        every { mediaMetadataMapper.toMediaMetadata(metadata) } returns mediaMetadata()
        every { downloadJobService.markMetadata(1, any()) } returns job
        coEvery { ytDlpService.download(request, tempDir.resolve("1")) } returns downloadedFile
        coEvery { telegramVideoPreparer.prepare(downloadedFile, tempDir.resolve("1"), 1) } returns preparedFile
        coEvery { telegramFileSender.send(job, preparedFile) } returns telegramResult()
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(job)

        verify { downloadJobLifecycle.markDownloading(job) }
        coVerify(exactly = 1) { ytDlpService.extractMetadata(request) }
        verify { downloadJobService.markMetadata(1, any()) }
        verify { downloadJobLifecycle.markUploading(job) }
        coVerify { telegramVideoPreparer.prepare(downloadedFile, tempDir.resolve("1"), 1) }
        coVerify { telegramFileSender.send(job, preparedFile) }
        verify { downloadJobLifecycle.complete(job, any()) }
        verify { workDirCleaner.deleteRecursively(tempDir.resolve("1")) }
    }

    @Test
    fun marksFailedOrRetryOnError(@TempDir tempDir: Path) = runTest {
        val job = job()
        every { downloadRequestFactory.create(job) } throws IllegalStateException("boom")
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(job)

        verify { downloadJobLifecycle.failOrRetry(job, "boom") }
        verify { workDirCleaner.deleteRecursively(tempDir.resolve("1")) }
    }

    @Test
    fun skipsCacheWhenCachedJobIsMissing(@TempDir tempDir: Path) = runTest {
        val job = job()
        val request = request()
        every { downloadJobService.findCachedJob(job) } returns null
        every { downloadRequestFactory.create(job) } returns request
        coEvery { ytDlpService.extractMetadata(request) } returns metadata()
        every { downloadPreflightService.check(request, any()) } returns DownloadPreflightDecision.Rejected("too large")
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
        coEvery { ytDlpService.extractMetadata(request) } returns metadata()
        every { downloadPreflightService.check(request, any()) } returns DownloadPreflightDecision.Rejected("too large")
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
        val metadata = metadata()
        every { downloadRequestFactory.create(job) } returns request
        every { downloadPreflightService.check(request, any()) } returns DownloadPreflightDecision.Allowed
        coEvery { ytDlpService.extractMetadata(request) } returns metadata
        every { mediaMetadataMapper.toMediaMetadata(metadata) } returns mediaMetadata()
        every { downloadJobService.markMetadata(1, any()) } returns markedJob
        coEvery { ytDlpService.download(request, tempDir.resolve("1")) } returns downloadedFile
        coEvery { telegramFileSender.send(markedJob, downloadedFile) } returns telegramResult()
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(job)

        coVerify(exactly = 0) { telegramVideoPreparer.prepare(any(), any(), any()) }
        coVerify { telegramFileSender.send(markedJob, downloadedFile) }
    }

    @Test
    fun failsWhenMetadataExtractionFails(@TempDir tempDir: Path) = runTest {
        val job = job()
        val request = request()
        every { downloadRequestFactory.create(job) } returns request
        coEvery { ytDlpService.extractMetadata(request) } throws IllegalStateException("metadata error")
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(job)

        verify(exactly = 0) { downloadPreflightService.check(any(), any()) }
        verify(exactly = 0) { downloadJobService.markMetadata(any(), any()) }
        coVerify(exactly = 0) { ytDlpService.download(any(), any()) }
        coVerify(exactly = 0) { telegramFileSender.send(any(), any()) }
        verify { downloadJobLifecycle.failOrRetry(job, "metadata error") }
    }

    @Test
    fun marksMetadataWithoutDuration(@TempDir tempDir: Path) = runTest {
        val job = job()
        val request = request()
        val downloadedFile = DownloadedFile(tempDir.resolve("downloaded.mp4"), 100)
        val metadata = metadata(duration = null)
        every { downloadRequestFactory.create(job) } returns request
        every { downloadPreflightService.check(request, any()) } returns DownloadPreflightDecision.Allowed
        coEvery { ytDlpService.extractMetadata(request) } returns metadata
        every { mediaMetadataMapper.toMediaMetadata(metadata) } returns mediaMetadata(durationSeconds = null)
        every { downloadJobService.markMetadata(1, any()) } returns job
        coEvery { ytDlpService.download(request, tempDir.resolve("1")) } returns downloadedFile
        coEvery { telegramVideoPreparer.prepare(downloadedFile, tempDir.resolve("1"), 1) } returns downloadedFile
        coEvery { telegramFileSender.send(job, downloadedFile) } returns telegramResult()
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(job)

        verify { downloadJobService.markMetadata(1, any()) }
    }

    @Test
    fun usesExceptionClassNameWhenErrorMessageIsMissing(@TempDir tempDir: Path) = runTest {
        val job = job()
        every { downloadRequestFactory.create(job) } throws object : RuntimeException() {}
        every { workDirCleaner.deleteRecursively(any()) } just runs

        processor(tempDir).process(job)

        verify { downloadJobLifecycle.failOrRetry(job, any()) }
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
            mediaMetadataMapper = mediaMetadataMapper,
            downloadJobLifecycle = downloadJobLifecycle,
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
}
