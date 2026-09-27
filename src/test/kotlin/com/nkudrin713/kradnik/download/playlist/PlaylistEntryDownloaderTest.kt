package com.nkudrin713.kradnik.download.playlist

import com.nkudrin713.kradnik.download.domain.DownloadRejectedException
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.source.SourceRequest
import com.nkudrin713.kradnik.ytdlp.YtDlpPresets
import com.nkudrin713.kradnik.ytdlp.YtDlpService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlaylistEntryDownloaderTest {
    @TempDir lateinit var root: Path
    private val ytDlp = mockk<YtDlpService>()
    private val downloader = PlaylistEntryDownloader(ytDlp, TelegramUploadLimits(100))
    private val entry = PlaylistAudioEntry(1, "video", "https://youtu.be/video", "Recording", null)
    private val source = SourceRequest("playlist", "playlist", DownloadPlatform.YOUTUBE, "bestaudio", YtDlpPresets.PLAYLIST_AUDIO_ARGS, "youtube_playlist_audio_320")

    @Test
    fun usesSavedSelectionAndSeparatesNewCacheFromLegacyAudio() = runTest {
        coEvery { ytDlp.download(any(), root) } answers {
            val actual = firstArg<SourceRequest>()
            assertEquals(source.copy(originalUrl = entry.url, normalizedUrl = "https://www.youtube.com/watch?v=video"), actual)
            DownloadedFile(Files.write(root.resolve("track.mp3"), ByteArray(20)), 20)
        }
        downloader.download(entry, root, source)
        assertEquals("youtube:video:video:audio:youtube_audio:audio:96K", downloader.cacheKey(entry))
        assertNotEquals(downloader.cacheKey(entry), downloader.cacheKey(entry, source))
        assertTrue(downloader.cacheKey(entry, source).contains("320K:cbr:v1"))
    }

    @Test
    fun rejectsActualOutputEvenIfReportedSizeIsWrong() = runTest {
        coEvery { ytDlp.download(any(), root) } answers {
            DownloadedFile(Files.write(root.resolve("track.mp3"), ByteArray(101)), 1)
        }
        assertFailsWith<DownloadRejectedException> { downloader.download(entry, root, source) }
    }

    @Test
    fun stopsAnUnknownSizeDownloadWhileItIsStillRunning() = runTest {
        var cancelled = false
        coEvery { ytDlp.download(any(), root) } coAnswers {
            Files.write(root.resolve("source.webm.part"), ByteArray(101))
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        assertFailsWith<DownloadRejectedException> { downloader.download(entry, root, source) }
        assertTrue(cancelled)
    }
}
