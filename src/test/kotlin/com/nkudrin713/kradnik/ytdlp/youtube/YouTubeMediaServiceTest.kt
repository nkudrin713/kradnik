package com.nkudrin713.kradnik.ytdlp.youtube

import com.nkudrin713.kradnik.download.domain.DownloadOutputType
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.telegram.upload.TELEGRAM_UPLOAD_LIMIT_BYTES
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class YouTubeMediaServiceTest {
    private val ytDlpService: YtDlpService = mock()
    private val service = YouTubeMediaService(
        ytDlpService = ytDlpService,
    )

    @Test
    fun `downloads audio with 128k preset by default`(@TempDir tempDir: Path) = runTest {
        val downloadedFile = downloadedFile(tempDir, "audio-128.mp3", TELEGRAM_UPLOAD_LIMIT_BYTES)
        whenever(ytDlpService.downloadAudio("url", tempDir, "128K")).thenReturn(downloadedFile)

        val actual = service.download("url", metadata(durationSeconds = 60), DownloadOutputType.AUDIO, tempDir)

        assertEquals(downloadedFile, actual)
        verify(ytDlpService).downloadAudio("url", tempDir, "128K")
    }

    @Test
    fun `tries next audio preset when downloaded file is too large`(@TempDir tempDir: Path) = runTest {
        val tooLargeFile = downloadedFile(tempDir, "audio-128.mp3", TELEGRAM_UPLOAD_LIMIT_BYTES + 1)
        val validFile = downloadedFile(tempDir, "audio-96.mp3", TELEGRAM_UPLOAD_LIMIT_BYTES)

        whenever(ytDlpService.downloadAudio("url", tempDir, "128K")).thenReturn(tooLargeFile)
        whenever(ytDlpService.downloadAudio("url", tempDir, "96K")).thenReturn(validFile)

        val actual = service.download("url", metadata(durationSeconds = 60), DownloadOutputType.AUDIO, tempDir)

        assertEquals(validFile, actual)
        verify(ytDlpService).downloadAudio("url", tempDir, "128K")
        verify(ytDlpService).downloadAudio("url", tempDir, "96K")
    }

    @Test
    fun `skips audio preset when expected file is too large`(@TempDir tempDir: Path) = runTest {
        val validFile = downloadedFile(tempDir, "audio-96.mp3", TELEGRAM_UPLOAD_LIMIT_BYTES)
        whenever(ytDlpService.downloadAudio("url", tempDir, "96K")).thenReturn(validFile)

        val actual = service.download("url", metadata(durationSeconds = 3_600), DownloadOutputType.AUDIO, tempDir)

        assertEquals(validFile, actual)
        verify(ytDlpService, never()).downloadAudio("url", tempDir, "128K")
        verify(ytDlpService).downloadAudio("url", tempDir, "96K")
    }

    @Test
    fun `fails before download when all audio presets are expected to be too large`(@TempDir tempDir: Path) = runTest {
        assertFailsWith<ExpectedAudioFileTooLargeException> {
            service.download("url", metadata(durationSeconds = 7_000), DownloadOutputType.AUDIO, tempDir)
        }

        verify(ytDlpService, never()).downloadAudio(any(), any(), any())
    }

    private fun metadata(durationSeconds: Long): MediaMetadata =
        MediaMetadata(
            title = "title",
            extractor = "youtube",
            durationSeconds = durationSeconds,
            webpageUrl = "url",
        )

    private fun downloadedFile(tempDir: Path, name: String, sizeBytes: Long): DownloadedFile {
        val file = tempDir.resolve(name)
        file.writeText("audio")
        return DownloadedFile(
            file = file,
            ext = "mp3",
            sizeBytes = sizeBytes,
            args = emptyList(),
        )
    }
}
