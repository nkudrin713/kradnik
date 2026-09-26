package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.DownloadEngine
import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaArtifact
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.limit.AudioUploadPlanner
import com.nkudrin713.kradnik.download.limit.DownloadPreflightDecision
import com.nkudrin713.kradnik.download.limit.DownloadPreflightService
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.download.repository.DownloadRequestMapper
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.single.AudioHandler
import com.nkudrin713.kradnik.download.single.CoverHandler
import com.nkudrin713.kradnik.download.single.ImagesHandler
import com.nkudrin713.kradnik.download.single.SingleMediaHandlers
import com.nkudrin713.kradnik.download.single.VideoHandler
import com.nkudrin713.kradnik.download.source.YtDlpPreparedSource
import com.nkudrin713.kradnik.download.telegram.CachedMedia
import com.nkudrin713.kradnik.download.telegram.DeliveryContext
import com.nkudrin713.kradnik.download.telegram.TelegramFileSender
import com.nkudrin713.kradnik.download.telegram.TelegramJobProgress
import com.nkudrin713.kradnik.download.telegram.TelegramMediaKind
import com.nkudrin713.kradnik.download.telegram.TelegramResultCache
import com.nkudrin713.kradnik.download.video.TelegramVideoPreparer
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import com.nkudrin713.kradnik.telegram.TelegramSendException
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.localization.telegramMessages
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DownloadJobProcessorTest {
    @TempDir lateinit var root: Path
    private val jobs = mockk<DownloadJobService>(relaxed = true)
    private val preflight = spyk(DownloadPreflightService(AudioUploadPlanner(TelegramUploadLimits(100)), TelegramUploadLimits(100)))
    private val video = mockk<TelegramVideoPreparer>()
    private val sender = mockk<TelegramFileSender>()
    private val engine = mockk<DownloadEngine>()
    private val telegram = mockk<TelegramSender>(relaxed = true)
    private val job = DownloadJob(id = 1, telegramChatId = 2, telegramStatusMessageId = 3, outputType = OutputType.AUDIO)
    private val spec = DownloadRequestMapper.single(job)
    private val prepared = YtDlpPreparedSource(mockk<MediaMetadata>(relaxed = true))

    private fun processor(preflightService: DownloadPreflightService = preflight): DownloadJobProcessor {
        val progress = TelegramJobProgress(telegram, telegramMessages())
        return DownloadJobProcessor(
            SingleMediaHandlers(VideoHandler(engine, preflightService, video), AudioHandler(engine, preflightService), CoverHandler(engine, preflightService, mockk()), ImagesHandler(engine, preflightService), mockk()),
            TelegramResultCache(jobs),
            sender,
            JobLifecycle(jobs, progress),
            progress,
            WorkDirCleaner(root.toString()),
        )
    }

    @BeforeEach
    fun setup() {
        every { jobs.markCompleted(any(), any()) } returns true
        every { jobs.markFailed(any(), any()) } returns true
        every { jobs.isCancelledByUser(any()) } returns false
        every { jobs.findCachedFileId(any()) } returns null
        coEvery { engine.prepare(spec.source) } returns prepared
        every { preflight.check(spec, prepared.metadata) } returns DownloadPreflightDecision.Allowed(spec)
        coEvery { engine.download(spec.source, any(), prepared, any()) } answers {
            val file = arg<Path>(3).resolve("audio.mp3")
            file.writeText("audio")
            DownloadedFile(file, 5)
        }
        coEvery { sender.send(any(), any()) } returns "file"
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
        coEvery { engine.download(any(), any(), any(), any()) } throws IllegalStateException("download failed")
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
        coEvery { engine.download(any(), any(), any(), any()) } returns DownloadedFile(root.resolve("large"), 101)
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
        coVerify(exactly = 0) { engine.download(any(), any(), any(), any()) }
        assertFalse(root.resolve("1").exists())
    }

    @Test
    fun cancellationCleansAndLeavesProcessingForRestart() = runTest {
        coEvery { engine.download(any(), any(), any(), any()) } throws CancellationException("stopping")
        assertFailsWith<CancellationException> { processor().process(job) }
        verify(exactly = 0) { jobs.markFailed(any(), any()) }
        assertFalse(root.resolve("1").exists())
    }

    @Test
    fun cacheHitSkipsDownload() = runTest {
        every { jobs.findCachedFileId(any()) } returns "cached"
        coEvery { sender.sendCached(DeliveryContext.fromJob(job), CachedMedia.Single(TelegramMediaKind.AUDIO, "cached")) } returns "cached"
        processor().process(job)
        verify { jobs.markCompleted(job, "cached") }
        coVerify(exactly = 0) { engine.prepare(any(), any()) }
        assertFalse(root.resolve("1").exists())
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
        every { jobs.findCachedFileId(any()) } returns "stale"
        coEvery { sender.sendCached(DeliveryContext.fromJob(job), CachedMedia.Single(TelegramMediaKind.AUDIO, "stale")) } throws TelegramSendException(
            errorCode = 400,
            description = "wrong file identifier",
        )
        processor().process(job)
        coVerify(exactly = 1) { engine.download(any(), any(), any(), any()) }
        verify { jobs.markCompleted(job, "file") }
    }

    @Test
    fun cacheNetworkFailureDoesNotDownloadAndUploadAgain() = runTest {
        every { jobs.findCachedFileId(any()) } returns "cached"
        coEvery { sender.sendCached(DeliveryContext.fromJob(job), CachedMedia.Single(TelegramMediaKind.AUDIO, "cached")) } throws TelegramSendException(
            errorCode = 500,
            description = "server unavailable",
        )
        processor().process(job)
        verify { jobs.markFailed(job, any()) }
        coVerify(exactly = 0) { engine.download(any(), any(), any(), any()) }
    }

    @Test
    fun videoPassesThroughCompatibilityCheckBeforeUpload() = runTest {
        job.outputType = OutputType.VIDEO
        val videoSpec = DownloadRequestMapper.single(job)
        coEvery { engine.prepare(videoSpec.source) } returns prepared
        every { preflight.check(videoSpec, prepared.metadata) } returns DownloadPreflightDecision.Allowed(videoSpec)
        val raw = DownloadedFile(root.resolve("raw"), 10)
        val compatible = DownloadedFile(root.resolve("compatible"), 9)
        coEvery { engine.download(videoSpec.source, any(), prepared, any()) } returns raw
        coEvery { video.prepare(raw, any(), 1) } returns compatible
        processor().process(job)
        coVerify { sender.send(DeliveryContext.fromJob(job), MediaArtifact.Video(compatible.file)) }
        verify { jobs.markCompleted(job, "file") }
    }

    @Test
    fun sourceAuthenticationFailureHasSafeUserStatus() = runTest {
        coEvery { engine.prepare(any(), any()) } throws
            com.nkudrin713.kradnik.ytdlp.YtDlpAuthenticationRequiredException("login needed")
        processor().process(job)
        verify { telegram.editStatus(2, 3, TelegramDownloadStatus.AUTHENTICATION_REQUIRED, job.language) }
        verify { jobs.markFailed(job, "login needed") }
    }

    @Test
    fun unknownAudioDurationStillRejectsKnownOversizeBeforeDownloading() = runTest {
        val metadata = mockk<MediaMetadata>(relaxed = true)
        every { metadata.duration } returns null
        every { metadata.filesize } returns 101
        coEvery { engine.prepare(spec.source) } returns YtDlpPreparedSource(metadata)
        val limits = TelegramUploadLimits(100)

        processor(DownloadPreflightService(AudioUploadPlanner(limits), limits)).process(job)

        verify { jobs.markFailed(job, any()) }
        coVerify(exactly = 0) { engine.download(any(), any(), any(), any()) }
        coVerify(exactly = 0) { sender.send(any(), any()) }
        assertFalse(root.resolve("1").exists())
    }

    @Test
    fun imagesAreNotRejectedByAggregateSingleFileSizeLimit() = runTest {
        job.outputType = OutputType.IMAGES
        coEvery { engine.prepare(any(), any()) } returns prepared
        every { preflight.check(any(), any()) } answers { DownloadPreflightDecision.Allowed(firstArg()) }
        coEvery { engine.download(any(), any(), any(), any()) } returns
            DownloadedFile(root.resolve("first.jpg"), 150, listOf(root.resolve("second.jpg")))

        processor().process(job)

        coVerify(exactly = 1) { sender.send(any(), any()) }
        coVerify(exactly = 0) { video.prepare(any(), any(), any()) }
        verify { jobs.markCompleted(job, "file") }
    }

    @Test
    fun cancelledCompletionDoesNotDeleteStatusOrMarkFailed() = runTest {
        every { jobs.markCompleted(job, "file") } returns false

        processor().process(job)

        coVerify(exactly = 1) { sender.send(any(), any()) }
        verify(exactly = 0) { telegram.deleteMessage(any(), any()) }
        verify(exactly = 0) { jobs.markFailed(any(), any()) }
        assertFalse(root.resolve("1").exists())
    }

    @Test
    fun userCancellationCleansWithoutCompletionOrFailure() = runTest {
        coEvery { engine.download(any(), any(), any(), any()) } throws CancellationException("cancelled")
        every { jobs.isCancelledByUser(1) } returns true

        processor().process(job)

        verify(exactly = 0) { jobs.markFailed(any(), any()) }
        verify(exactly = 0) { jobs.markCompleted(any(), any()) }
        assertFalse(root.resolve("1").exists())
    }

    @Test
    fun queuedJobKeepsItsPersistedFormatArgumentsAndVersionedCacheKey() = runTest {
        job.selectedFormat = "legacy-format"
        job.downloadExtraArgs = listOf("-x", "--audio-quality", "40K")
        job.downloadPreset = "legacy-preset"
        job.cacheKey = "existing:telegram-video-h264-v1"
        val savedSpec = DownloadRequestMapper.single(job)
        coEvery { engine.prepare(savedSpec.source) } returns prepared
        every { preflight.check(savedSpec, prepared.metadata) } returns DownloadPreflightDecision.Allowed(savedSpec)
        coEvery { engine.download(savedSpec.source, any(), prepared, any()) } returns DownloadedFile(root.resolve("legacy.mp3"), 10)

        processor().process(job)

        coVerify { engine.download(savedSpec.source, any(), prepared, any()) }
        verify { jobs.markCompleted(job, "file") }
        assertEquals("existing:telegram-video-h264-v1", job.cacheKey)
        assertEquals(listOf("-x", "--audio-quality", "40K"), job.downloadExtraArgs)
    }

    @Test
    fun passesAudioMetadataInArtifact() = runTest {
        every { prepared.metadata.track } returns "Track"
        every { prepared.metadata.artist } returns "Artist"
        every { prepared.metadata.duration } returns BigDecimal("120.9")
        job.sourcePostText = "Saved text"
        val postSpec = DownloadRequestMapper.single(job)
        coEvery { engine.prepare(postSpec.source) } returns prepared
        every { preflight.check(postSpec, prepared.metadata) } returns DownloadPreflightDecision.Allowed(postSpec)
        coEvery { engine.download(postSpec.source, any(), prepared, any()) } returns DownloadedFile(root.resolve("audio.mp3"), 5)

        processor().process(job)

        coVerify {
            sender.send(
                DeliveryContext(2, postText = "Saved text"),
                match {
                    it is MediaArtifact.Audio && it.metadata.title == "Track" &&
                        it.metadata.performer == "Artist" && it.metadata.durationSeconds == 120
                },
            )
        }
        coVerifyOrder {
            engine.prepare(postSpec.source)
            preflight.check(postSpec, prepared.metadata)
            engine.download(postSpec.source, any(), prepared, any())
            sender.send(any(), any())
            jobs.markCompleted(job, "file")
        }
    }
}
