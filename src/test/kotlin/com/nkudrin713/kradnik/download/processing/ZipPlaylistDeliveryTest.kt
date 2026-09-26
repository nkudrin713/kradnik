package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadJobStatus
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.domain.PlaylistDeliveryMode
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.download.playlist.PlaylistEntryDownloader
import com.nkudrin713.kradnik.download.playlist.PlaylistWorkspaceBudget
import com.nkudrin713.kradnik.download.playlist.PlaylistZipBuilder
import com.nkudrin713.kradnik.download.playlist.ZipPlaylistWorkflow
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.telegram.TelegramJobProgress
import com.nkudrin713.kradnik.download.telegram.TelegramPlaylistSender
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import com.nkudrin713.kradnik.telegram.TelegramMediaSender
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.config.TelegramBotProperties
import com.nkudrin713.kradnik.telegram.localization.telegramMessages
import com.nkudrin713.kradnik.ytdlp.YtDlpException
import com.nkudrin713.kradnik.ytdlp.YtDlpFileSizeLimitException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ZipPlaylistDeliveryTest {
    @TempDir lateinit var root: Path
    private val jobs = mockk<DownloadJobService>(relaxed = true)
    private val downloader = mockk<PlaylistEntryDownloader>()
    private val sender = mockk<TelegramMediaSender>()
    private val telegram = mockk<TelegramSender>(relaxed = true)
    private val job = DownloadJob(
        id = 1,
        telegramChatId = 20,
        telegramRequestMessageId = 21,
        telegramStatusMessageId = 22,
        status = DownloadJobStatus.PROCESSING,
        workloadType = DownloadWorkloadType.PLAYLIST_AUDIO,
        playlistDeliveryMode = PlaylistDeliveryMode.ZIP,
        playlistTitle = "Плейлист",
        playlistEntries = (1..3).map { PlaylistAudioEntry(it, "video-$it", "https://youtu.be/video-$it", "Track $it", 60) },
    )

    @Test
    fun sendsPartialArchiveWithActualCountAndRebuildsPreviouslyPersistedItems() = runBlocking {
        job.playlistResults = listOf(PlaylistAudioResult(1, "old-file-id"), PlaylistAudioResult(2, error = "previous error"))
        successfulDownloads()
        coEvery { downloader.download(match { it.position == 2 }, any()) } throws YtDlpException("Unavailable")
        coEvery { sender.sendDocument(20, any(), 21) } answers {
            val path = secondArg<Path>()
            assertEquals("2 – Плейлист.zip", path.fileName.toString())
            ZipFile(path.toFile()).use { zip ->
                assertEquals(listOf("001 – Track 1.mp3", "003 – Track 3.mp3"), zip.entries().toList().map { it.name })
            }
            "zip-file-id"
        }

        processor().process(job)

        coVerify(exactly = 3) { downloader.download(any(), any()) }
        verify { jobs.markCompleted(job, "zip-file-id") }
        verify(exactly = 0) { jobs.savePlaylistResult(any(), any()) }
        verify { telegram.editJobStatus(any(), TelegramDownloadStatus.PACKING, 1, job.language) }
        verify { telegram.sendMessage(20, match { it.endsWith("1") }) }
        assertFalse(Files.exists(root.resolve("1")))
    }

    @Test
    fun rejectsActualTotalSizeAndCleansWithoutUploading() = runBlocking {
        successfulDownloads(100)
        processor(limit = 250).process(job)
        verify { jobs.markFailed(job, any()) }
        verify { telegram.editStatus(any(), TelegramDownloadStatus.REJECTED_TOO_LARGE, job.language) }
        coVerify(exactly = 0) { sender.sendDocument(any(), any(), any()) }
        assertFalse(Files.exists(root.resolve("1")))
    }

    @Test
    fun rejectsYtDlpWorkspaceOverflowInsteadOfSendingPartialArchive() = runBlocking {
        successfulDownloads()
        coEvery { downloader.download(match { it.position == 2 }, any()) } throws YtDlpFileSizeLimitException()
        processor().process(job)
        verify { telegram.editStatus(any(), TelegramDownloadStatus.REJECTED_TOO_LARGE, job.language) }
        coVerify(exactly = 0) { sender.sendDocument(any(), any(), any()) }
        assertFalse(Files.exists(root.resolve("1")))
    }

    @Test
    fun diskFailureAbortsWholeJob() = runBlocking {
        successfulDownloads()
        coEvery { downloader.download(match { it.position == 2 }, any()) } throws IOException("Disk failure")
        processor().process(job)
        verify { jobs.markFailed(job, "Disk failure") }
        coVerify(exactly = 0) { sender.sendDocument(any(), any(), any()) }
        assertFalse(Files.exists(root.resolve("1")))
    }

    @Test
    fun allUnavailableItemsFailWithoutEmptyArchive() = runBlocking {
        successfulDownloads()
        coEvery { downloader.download(any(), any()) } throws YtDlpException("Unavailable")
        processor().process(job)
        verify { jobs.markFailed(job, "No playlist items could be downloaded") }
        coVerify(exactly = 0) { sender.sendDocument(any(), any(), any()) }
        assertFalse(Files.exists(root.resolve("1")))
    }

    @Test
    fun uploadFailureCleansAndDoesNotCompleteJob() = runBlocking {
        successfulDownloads()
        coEvery { sender.sendDocument(any(), any(), any()) } throws IOException("Upload failed")
        processor().process(job)
        verify { jobs.markFailed(job, "Upload failed") }
        verify(exactly = 0) { jobs.markCompleted(any(), any()) }
        assertFalse(Files.exists(root.resolve("1")))
    }

    @Test
    fun cancellationDuringPackingCleansFilesAndDoesNotUpload() = runBlocking {
        successfulDownloads()
        val builder = mockk<PlaylistZipBuilder>()
        val packing = CompletableDeferred<Unit>()
        coEvery { builder.build(any(), any(), any(), any()) } coAnswers {
            packing.complete(Unit)
            awaitCancellation()
        }
        val processing = launch { processor(builder = builder).process(job) }
        packing.await()
        every { jobs.isCancelledByUser(1) } returns true
        processing.cancelAndJoin()
        coVerify(exactly = 0) { sender.sendDocument(any(), any(), any()) }
        verify(exactly = 0) { jobs.markFailed(any(), any()) }
        assertFalse(Files.exists(root.resolve("1")))
    }

    @Test
    fun timeLimitFailsJobInsteadOfLeavingItProcessing() = runBlocking {
        successfulDownloads()
        coEvery { downloader.download(any(), any()) } coAnswers { awaitCancellation() }
        processor(timeout = Duration.ofMillis(100)).process(job)
        verify { jobs.markFailed(job, "Playlist archive exceeded its time limit") }
        assertFalse(Files.exists(root.resolve("1")))
    }

    @Test
    fun monitorsTemporaryFilesWhileDownloaderIsStillRunning() = runBlocking {
        successfulDownloads()
        coEvery { downloader.download(any(), any()) } coAnswers {
            Files.write(secondArg<Path>().resolve("incomplete.part"), ByteArray(40_000))
            awaitCancellation()
        }
        processor(limit = 10_000).process(job)
        verify { telegram.editStatus(any(), TelegramDownloadStatus.REJECTED_TOO_LARGE, job.language) }
        coVerify(exactly = 0) { sender.sendDocument(any(), any(), any()) }
        assertFalse(Files.exists(root.resolve("1")))
    }

    @Test
    fun cancellationDuringDownloadCleansWithoutFailingOrCompleting() = runBlocking {
        successfulDownloads()
        val downloading = CompletableDeferred<Unit>()
        coEvery { downloader.download(any(), any()) } coAnswers {
            Files.write(secondArg<Path>().resolve("incomplete.part"), byteArrayOf(1))
            downloading.complete(Unit)
            awaitCancellation()
        }
        val processing = launch { processor().process(job) }
        downloading.await()
        every { jobs.isCancelledByUser(1) } returns true
        processing.cancelAndJoin()
        verify(exactly = 0) { jobs.markFailed(any(), any()) }
        verify(exactly = 0) { jobs.markCompleted(any(), any()) }
        assertFalse(Files.exists(root.resolve("1")))
    }

    private fun successfulDownloads(size: Int = 10) {
        every { jobs.isProcessing(1) } returns true
        every { jobs.markCompleted(any(), any()) } returns true
        every { jobs.markFailed(any(), any()) } returns true
        coEvery { downloader.download(any(), any()) } answers {
            val path = Files.write(secondArg<Path>().resolve("track.mp3"), ByteArray(size))
            DownloadedFile(path, size.toLong())
        }
    }

    private fun processor(limit: Long = 10_000, builder: PlaylistZipBuilder = PlaylistZipBuilder(), timeout: Duration = Duration.ofHours(2)): PlaylistJobProcessor {
        val cleaner = WorkDirCleaner(root.toString())
        return PlaylistJobProcessor(
            mockk(),
            ZipPlaylistWorkflow(jobs, downloader, builder, TelegramPlaylistSender(sender, TelegramBotProperties(token = "test")), TelegramUploadLimits(limit), cleaner, PlaylistWorkspaceBudget(TelegramUploadLimits(limit)), timeout = timeout),
            JobLifecycle(jobs, TelegramJobProgress(telegram, telegramMessages())),
            TelegramJobProgress(telegram, telegramMessages()),
            cleaner,
        )
    }
}
