package com.nkudrin713.kradnik.ytdlp.youtube

import com.nkudrin713.kradnik.download.domain.DownloadOutputType
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.telegram.upload.TELEGRAM_UPLOAD_LIMIT_BYTES
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class YouTubeMediaServiceTest {
    private val ytDlpService: YtDlpService = mockk()
    private val service = YouTubeMediaService(
        ytDlpService = ytDlpService,
    )

    @Test
    fun `downloads audio with 128k preset by default`(@TempDir tempDir: Path) = runTest {
        val downloadedFile = downloadedFile(tempDir, "audio-128.mp3", TELEGRAM_UPLOAD_LIMIT_BYTES)
        coEvery { ytDlpService.downloadAudio("url", tempDir, "128K") } returns downloadedFile

        val actual = service.download("url", metadata(durationSeconds = 60), DownloadOutputType.AUDIO, tempDir, 100, 1)

        assertEquals(downloadedFile, actual)
        coVerify { ytDlpService.downloadAudio("url", tempDir, "128K") }
    }

    @Test
    fun `tries next audio preset when downloaded file is too large`(@TempDir tempDir: Path) = runTest {
        val tooLargeFile = downloadedFile(tempDir, "audio-128.mp3", TELEGRAM_UPLOAD_LIMIT_BYTES + 1)
        val validFile = downloadedFile(tempDir, "audio-96.mp3", TELEGRAM_UPLOAD_LIMIT_BYTES)

        coEvery { ytDlpService.downloadAudio("url", tempDir, "128K") } returns tooLargeFile
        coEvery { ytDlpService.downloadAudio("url", tempDir, "96K") } returns validFile

        val actual = service.download("url", metadata(durationSeconds = 60), DownloadOutputType.AUDIO, tempDir, 100, 1)

        assertEquals(validFile, actual)
        coVerify { ytDlpService.downloadAudio("url", tempDir, "128K") }
        coVerify { ytDlpService.downloadAudio("url", tempDir, "96K") }
    }

    @Test
    fun `skips audio preset when expected file is too large`(@TempDir tempDir: Path) = runTest {
        val validFile = downloadedFile(tempDir, "audio-96.mp3", TELEGRAM_UPLOAD_LIMIT_BYTES)
        coEvery { ytDlpService.downloadAudio("url", tempDir, "96K") } returns validFile

        val actual = service.download("url", metadata(durationSeconds = 3_600), DownloadOutputType.AUDIO, tempDir, 100, 1)

        assertEquals(validFile, actual)
        coVerify(exactly = 0) { ytDlpService.downloadAudio("url", tempDir, "128K") }
        coVerify { ytDlpService.downloadAudio("url", tempDir, "96K") }
    }

    @Test
    fun `fails before download when all audio presets are expected to be too large`(@TempDir tempDir: Path) = runTest {
        assertFailsWith<ExpectedAudioFileTooLargeException> {
            service.download("url", metadata(durationSeconds = 7_000), DownloadOutputType.AUDIO, tempDir, 100, 1)
        }

        coVerify(exactly = 0) { ytDlpService.downloadAudio(any(), any(), any()) }
    }

    @Test
    fun `downloads video with default compression preset`(@TempDir tempDir: Path) = runTest {
        val downloadedFile = downloadedFile(tempDir, "video.mp4", TELEGRAM_UPLOAD_LIMIT_BYTES)
        coEvery { ytDlpService.downloadVideo("url", tempDir, 28, "96k") } returns downloadedFile

        val actual = service.download("url", metadata(durationSeconds = 60), DownloadOutputType.VIDEO, tempDir, 100, 1)

        assertEquals(downloadedFile, actual)
        coVerify { ytDlpService.downloadVideo("url", tempDir, 28, "96k") }
    }

    @Test
    fun `tries next video preset when downloaded file is too large`(@TempDir tempDir: Path) = runTest {
        val tooLargeFile = downloadedFile(tempDir, "video-28.mp4", TELEGRAM_UPLOAD_LIMIT_BYTES + 1)
        val validFile = downloadedFile(tempDir, "video-30.mp4", TELEGRAM_UPLOAD_LIMIT_BYTES)

        coEvery { ytDlpService.downloadVideo("url", tempDir, 28, "96k") } returns tooLargeFile
        coEvery { ytDlpService.downloadVideo("url", tempDir, 30, "80k") } returns validFile

        val actual = service.download("url", metadata(durationSeconds = 60), DownloadOutputType.VIDEO, tempDir, 100, 1)

        assertEquals(validFile, actual)
        coVerify { ytDlpService.downloadVideo("url", tempDir, 28, "96k") }
        coVerify { ytDlpService.downloadVideo("url", tempDir, 30, "80k") }
    }

    @Test
    fun `skips video preset when expected file is too large`(@TempDir tempDir: Path) = runTest {
        val validFile = downloadedFile(tempDir, "video-30.mp4", TELEGRAM_UPLOAD_LIMIT_BYTES)
        coEvery { ytDlpService.downloadVideo("url", tempDir, 30, "80k") } returns validFile

        val actual = service.download("url", metadata(durationSeconds = 400), DownloadOutputType.VIDEO, tempDir, 100, 1)

        assertEquals(validFile, actual)
        coVerify(exactly = 0) { ytDlpService.downloadVideo("url", tempDir, 28, "96k") }
        coVerify { ytDlpService.downloadVideo("url", tempDir, 30, "80k") }
    }

    @Test
    fun `fails before download when all video presets are expected to be too large`(@TempDir tempDir: Path) = runTest {
        assertFailsWith<ExpectedVideoFileTooLargeException> {
            service.download("url", metadata(durationSeconds = 701), DownloadOutputType.VIDEO, tempDir, 100, 1)
        }

        coVerify(exactly = 0) { ytDlpService.downloadVideo(any(), any(), any(), any()) }
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
