package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadJobStatus
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.telegram.TelegramFileSender
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
import java.nio.file.Path
import java.util.Collections
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertFalse

class PlaylistJobProcessorTest {
    @TempDir lateinit var root: Path
    private val jobs = mockk<DownloadJobService>()
    private val ytDlp = mockk<YtDlpService>()
    private val fileSender = mockk<TelegramFileSender>()
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
        coEvery { fileSender.sendPlaylistAudios(any(), any()) } returns listOf("file-one", "file-two")

        processor().process(job)

        coVerify {
            fileSender.sendPlaylistAudios(
                job,
                match { it.sortedBy(PlaylistAudioResult::position).map(PlaylistAudioResult::fileId) == listOf("file-one", "file-two") },
            )
        }
        coVerify(exactly = 0) { ytDlp.download(any(), any()) }
        verify { jobs.markCompleted(job, "playlist:2") }
        verify { telegram.deleteMessage(20, 22) }
        assertFalse(root.resolve("1").exists())
    }

    private fun processor() = PlaylistJobProcessor(
        downloadJobService = jobs,
        ytDlpService = ytDlp,
        telegramFileSender = fileSender,
        telegramSender = telegram,
        messages = telegramMessages(),
        workDirCleaner = WorkDirCleaner(root.toString()),
        itemParallelism = 2,
    )
}
