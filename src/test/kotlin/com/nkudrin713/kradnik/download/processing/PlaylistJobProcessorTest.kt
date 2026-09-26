package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadJobStatus
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.playlist.AudioMessagesPlaylistWorkflow
import com.nkudrin713.kradnik.download.playlist.PlaylistCompletion
import com.nkudrin713.kradnik.download.playlist.PlaylistDeliveryResult
import com.nkudrin713.kradnik.download.playlist.PlaylistEntryDownloader
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.telegram.DeliveryContext
import com.nkudrin713.kradnik.download.telegram.TelegramJobProgress
import com.nkudrin713.kradnik.download.telegram.TelegramPlaylistSender
import com.nkudrin713.kradnik.download.telegram.TelegramResultCache
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.localization.telegramMessages
import com.nkudrin713.kradnik.ytdlp.YtDlpService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistJobProcessorTest {
    @TempDir lateinit var root: Path
    private val jobs = mockk<DownloadJobService>()
    private val ytDlp = mockk<YtDlpService>()
    private val fileSender = mockk<TelegramPlaylistSender>()
    private val telegram = mockk<TelegramSender>(relaxed = true)

    @Test
    fun reusesCachedTracksDeliversThemInOrderAndCleans() = runTest {
        val job = DownloadJob(
            id = 1,
            telegramUserId = 10,
            telegramChatId = 20,
            telegramRequestMessageId = 21,
            telegramStatusMessageId = 22,
            workloadType = DownloadWorkloadType.PLAYLIST_AUDIO,
            status = DownloadJobStatus.PROCESSING,
            playlistEntries = listOf(
                PlaylistAudioEntry(1, "one", "https://youtu.be/one", "One", 60),
                PlaylistAudioEntry(2, "two", "https://youtu.be/two", "Two", 120),
            ),
        )
        val results = Collections.synchronizedList(mutableListOf<PlaylistAudioResult>())
        every { jobs.findCachedFileId("youtube:video:one:audio:youtube_audio:audio:96K") } returns "file-one"
        every { jobs.findCachedFileId("youtube:video:two:audio:youtube_audio:audio:96K") } returns "file-two"
        every { jobs.savePlaylistResult(1, any()) } answers {
            results += secondArg<PlaylistAudioResult>()
            true
        }
        every { jobs.isProcessing(1) } returns true
        every { jobs.findJob(1) } answers { job.apply { playlistResults = results.toList() } }
        every { jobs.markCompleted(job, "playlist:2") } returns true
        coEvery { fileSender.sendPlaylistAudios(any(), any(), any()) } returns listOf("file-one", "file-two")

        processor().process(job)

        coVerify {
            fileSender.sendPlaylistAudios(
                DeliveryContext.fromJob(job),
                job.playlistEntries,
                match { it.sortedBy(PlaylistAudioResult::position).map(PlaylistAudioResult::fileId) == listOf("file-one", "file-two") },
            )
        }
        coVerify(exactly = 0) { ytDlp.download(any(), any()) }
        verify { jobs.markCompleted(job, "playlist:2") }
        verify { telegram.deleteMessage(20, 22) }
        assertFalse(root.resolve("1").exists())
    }

    private fun processor() = PlaylistJobProcessor(
        telegramDelivery = AudioMessagesPlaylistWorkflow(jobs, PlaylistEntryDownloader(ytDlp), fileSender, TelegramResultCache(jobs), WorkDirCleaner(root.toString())),
        zipDelivery = mockk(),
        lifecycle = JobLifecycle(jobs, TelegramJobProgress(telegram, telegramMessages())),
        progress = TelegramJobProgress(telegram, telegramMessages()),
        workDirCleaner = WorkDirCleaner(root.toString()),
    )

    @Test
    fun cancellationBeforeCompletionSuppressesPartialSuccessSummary() = runTest {
        val delivery = mockk<AudioMessagesPlaylistWorkflow>()
        val job = DownloadJob(id = 1, telegramChatId = 20, telegramStatusMessageId = 22, workloadType = DownloadWorkloadType.PLAYLIST_AUDIO)
        coEvery { delivery.run(1, any(), any(), any(), any()) } returns PlaylistDeliveryResult(1, 1, PlaylistCompletion.AudioMessages(1))
        every { jobs.markCompleted(job, "playlist:1") } returns false
        val processor = PlaylistJobProcessor(delivery, mockk(), JobLifecycle(jobs, TelegramJobProgress(telegram, telegramMessages())), TelegramJobProgress(telegram, telegramMessages()), WorkDirCleaner(root.toString()))

        processor.process(job)

        verify(exactly = 0) { telegram.sendMessage(any(), any()) }
        verify(exactly = 0) { telegram.deleteMessage(any(), any()) }
        verify(exactly = 0) { jobs.markFailed(any(), any()) }
        assertFalse(root.resolve("1").exists())
    }

    @Test
    fun resumesAudioResultsAndDeletesNewFilesOnlyAfterStaging() = runTest {
        val job = DownloadJob(
            id = 1,
            telegramChatId = 20,
            telegramStatusMessageId = 22,
            workloadType = DownloadWorkloadType.PLAYLIST_AUDIO,
            status = DownloadJobStatus.PROCESSING,
            playlistEntries = listOf(
                PlaylistAudioEntry(1, "one", "https://youtu.be/one", "One", 60),
                PlaylistAudioEntry(2, "two", "https://youtu.be/two", "Two", 60),
            ),
            playlistResults = listOf(PlaylistAudioResult(1, "saved-file")),
        )
        every { jobs.findCachedFileId(any()) } returns null
        every { jobs.isProcessing(1) } returns true
        every { jobs.findJob(1) } returns job
        every { jobs.markCompleted(job, "playlist:2") } returns true
        every { jobs.savePlaylistResult(1, any()) } answers {
            assertTrue(Files.exists(root.resolve("1/2/track.mp3")))
            job.playlistResults += secondArg<PlaylistAudioResult>()
            true
        }
        coEvery { ytDlp.download(any(), any()) } answers {
            val spec = firstArg<com.nkudrin713.kradnik.download.source.SourceRequest>()
            assertEquals(listOf("--audio-quality", "96K"), spec.extraArgs.drop(3).take(2))
            val path = Files.write(secondArg<Path>().resolve("track.mp3"), byteArrayOf(1))
            DownloadedFile(path, 1)
        }
        coEvery { fileSender.stagePlaylistAudio(any(), any()) } answers {
            assertTrue(firstArg<DownloadedFile>().file.exists())
            "new-file"
        }
        coEvery { fileSender.sendPlaylistAudios(any(), any(), any()) } answers {
            assertFalse(Files.exists(root.resolve("1/2")))
            listOf("saved-file", "new-file")
        }

        processor().process(job)

        coVerify(exactly = 1) { ytDlp.download(match { it.originalUrl == "https://youtu.be/two" }, any()) }
        coVerify { fileSender.sendPlaylistAudios(DeliveryContext.fromJob(job), job.playlistEntries, match { it.map(PlaylistAudioResult::fileId) == listOf("saved-file", "new-file") }) }
        verify { jobs.markCompleted(job, "playlist:2") }
        assertFalse(root.resolve("1").exists())
    }
}
