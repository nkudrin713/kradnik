package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.DownloadEngine
import com.nkudrin713.kradnik.download.PreparedDownload
import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.limit.DownloadPreflightDecision
import com.nkudrin713.kradnik.download.limit.DownloadPreflightService
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.telegram.TelegramFileSender
import com.nkudrin713.kradnik.download.video.TelegramVideoPreparer
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.TelegramSendException
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class DownloadJobProcessorTest {
    @TempDir lateinit var root: Path
    private val jobs = mockk<DownloadJobService>(relaxed = true)
    private val preflight = mockk<DownloadPreflightService>()
    private val video = mockk<TelegramVideoPreparer>()
    private val sender = mockk<TelegramFileSender>()
    private val engine = mockk<DownloadEngine>()
    private val telegram = mockk<TelegramSender>(relaxed = true)
    private val job = DownloadJob(id = 1, telegramChatId = 2, telegramStatusMessageId = 3, outputType = OutputType.AUDIO)
    private val spec = DownloadSpec.fromJob(job)
    private val prepared = PreparedDownload(mockk<YtDlpMetadataDto>(relaxed = true))

    private fun processor() = DownloadJobProcessor(
        downloadJobService = jobs,
        downloadPreflightService = preflight,
        telegramVideoPreparer = video,
        telegramFileSender = sender,
        downloadEngine = engine,
        telegramSender = telegram,
        workDirCleaner = WorkDirCleaner(root.toString()),
        uploadLimits = TelegramUploadLimits(100),
    )

    @BeforeEach
    fun setup() {
        every { jobs.markCompleted(any(), any()) } returns true
        every { jobs.markFailed(any(), any()) } returns true
        every { jobs.isCancelledByUser(any()) } returns false
        every { jobs.findCachedJob(job) } returns null
        coEvery { engine.prepare(spec) } returns prepared
        every { preflight.check(spec, prepared.metadata) } returns DownloadPreflightDecision.Allowed(spec)
        coEvery { engine.download(spec, prepared, any()) } answers {
            val file = thirdArg<Path>().resolve("audio.mp3")
            file.writeText("audio")
            DownloadedFile(file, 5)
        }
        coEvery { sender.send(job, any()) } returns "file"
    }

    @Test
    fun sendsAndCompletesThenCleans() = runTest {
        processor().process(job)
        verify { jobs.markCompleted(job, "file") }
        verify { telegram.deleteMessage(2, 3) }
        assertFalse(root.resolve("1").exists())
    }

    @Test
    fun downloaderFailureMarksFailedAndCleans() = runTest {
        coEvery { engine.download(any(), any(), any()) } throws IllegalStateException("download failed")
        processor().process(job)
        verify { jobs.markFailed(job, "download failed") }
        coVerify(exactly = 0) { sender.send(any(), any()) }
        assertFalse(root.resolve("1").exists())
    }

    @Test
    fun telegramFailureMarksFailedWithoutRetry() = runTest {
        coEvery { sender.send(any(), any()) } throws IllegalStateException("Telegram unavailable")
        processor().process(job)
        verify { jobs.markFailed(job, "Telegram unavailable") }
        coVerify(exactly = 1) { sender.send(any(), any()) }
        assertFalse(root.resolve("1").exists())
    }

    @Test
    fun rejectsOversizeFileBeforeSending() = runTest {
        coEvery { engine.download(any(), any(), any()) } returns DownloadedFile(root.resolve("large"), 101)
        processor().process(job)
        verify { jobs.markFailed(job, any()) }
        verify { telegram.editStatus(2, 3, TelegramDownloadStatus.REJECTED_TOO_LARGE, job.language) }
        coVerify(exactly = 0) { sender.send(any(), any()) }
        assertFalse(root.resolve("1").exists())
    }

    @Test
    fun rejectsKnownOversizeBeforeDownload() = runTest {
        every { preflight.check(any(), any()) } returns DownloadPreflightDecision.Rejected("too large")
        processor().process(job)
        verify { jobs.markFailed(job, "too large") }
        coVerify(exactly = 0) { engine.download(any(), any(), any()) }
        assertFalse(root.resolve("1").exists())
    }

    @Test
    fun cancellationCleansAndLeavesProcessingForRestart() = runTest {
        coEvery { engine.download(any(), any(), any()) } throws CancellationException("stopping")
        assertFailsWith<CancellationException> { processor().process(job) }
        verify(exactly = 0) { jobs.markFailed(any(), any()) }
        assertFalse(root.resolve("1").exists())
    }

    @Test
    fun cacheHitSkipsDownload() = runTest {
        every { jobs.findCachedJob(job) } returns DownloadJob(telegramFileId = "cached")
        coEvery { sender.sendCached(job, "cached") } returns "cached"
        processor().process(job)
        verify { jobs.markCompleted(job, "cached") }
        coVerify(exactly = 0) { engine.prepare(any(), any()) }
    }

    @Test
    fun statusUpdateFailureDoesNotTurnSuccessfulDeliveryIntoFailure() = runTest {
        every { telegram.deleteMessage(any(), any()) } throws IllegalStateException("message gone")
        processor().process(job)
        verify { jobs.markCompleted(job, "file") }
        verify(exactly = 0) { jobs.markFailed(any(), any()) }
    }
    @Test
    fun invalidCachedFileFallsBackToFreshDownload() = runTest {
        every { jobs.findCachedJob(job) } returns DownloadJob(telegramFileId = "stale")
        coEvery { sender.sendCached(job, "stale") } throws TelegramSendException(
            errorCode = 400, description = "wrong file identifier",
        )
        processor().process(job)
        coVerify(exactly = 1) { engine.download(any(), any(), any()) }
        verify { jobs.markCompleted(job, "file") }
    }

    @Test
    fun cacheNetworkFailureDoesNotDownloadAndUploadAgain() = runTest {
        every { jobs.findCachedJob(job) } returns DownloadJob(telegramFileId = "cached")
        coEvery { sender.sendCached(job, "cached") } throws TelegramSendException(
            errorCode = 500, description = "server unavailable",
        )
        processor().process(job)
        verify { jobs.markFailed(job, any()) }
        coVerify(exactly = 0) { engine.download(any(), any(), any()) }
    }

    @Test
    fun videoPassesThroughCompatibilityCheckBeforeUpload() = runTest {
        job.outputType = OutputType.VIDEO
        val videoSpec = DownloadSpec.fromJob(job)
        coEvery { engine.prepare(videoSpec) } returns prepared
        every { preflight.check(videoSpec, prepared.metadata) } returns DownloadPreflightDecision.Allowed(videoSpec)
        val raw = DownloadedFile(root.resolve("raw"), 10)
        val compatible = DownloadedFile(root.resolve("compatible"), 9)
        coEvery { engine.download(videoSpec, prepared, any()) } returns raw
        coEvery { video.prepare(raw, any(), 1) } returns compatible
        processor().process(job)
        coVerify { sender.send(job, compatible) }
        verify { jobs.markCompleted(job, "file") }
    }

    @Test
    fun sourceAuthenticationFailureHasSafeUserStatus() = runTest {
        coEvery { engine.prepare(any(), any()) } throws
            com.nkudrin713.kradnik.ytdlp.client.YtDlpAuthenticationRequiredException("login needed")
        processor().process(job)
        verify { telegram.editStatus(2, 3, TelegramDownloadStatus.AUTHENTICATION_REQUIRED, job.language) }
        verify { jobs.markFailed(job, "login needed") }
    }
}
