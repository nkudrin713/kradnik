package com.nkudrin713.kradnik.download.telegram

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.telegram.TelegramSendResult
import com.nkudrin713.kradnik.telegram.TelegramSender
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramFileSenderTest {
    private val telegramSender: TelegramSender = mockk()
    private val sender = TelegramFileSender(telegramSender)

    @Test
    fun sendsVideoFile(@TempDir tempDir: Path) = runTest {
        val file = DownloadedFile(tempDir.resolve("video.mp4"), sizeBytes = 123)
        coEvery { telegramSender.sendVideo(100, file.file) } returns TelegramSendResult("video-id", 456)

        val actual = sender.send(job(OutputType.VIDEO), file)

        assertEquals("video-id", actual.telegramFileId)
        assertEquals(456, actual.telegramFileSize)
        assertEquals(123, actual.downloadedFileSize)
        coVerify { telegramSender.sendVideo(100, file.file) }
    }

    @Test
    fun sendsAudioFile(@TempDir tempDir: Path) = runTest {
        val file = DownloadedFile(tempDir.resolve("audio.mp3"), sizeBytes = 123)
        coEvery { telegramSender.sendAudio(100, file.file) } returns TelegramSendResult("audio-id", 456)

        val actual = sender.send(job(OutputType.AUDIO), file)

        assertEquals("audio-id", actual.telegramFileId)
        assertEquals(456, actual.telegramFileSize)
        assertEquals(123, actual.downloadedFileSize)
        coVerify { telegramSender.sendAudio(100, file.file) }
    }

    @Test
    fun sendsCachedVideo() {
        every { telegramSender.sendCachedVideo(100, "cached-id") } returns TelegramSendResult("video-id", 456)

        val actual = sender.sendCached(job(OutputType.VIDEO), "cached-id", downloadedFileSize = 123)

        assertEquals("video-id", actual.telegramFileId)
        assertEquals(456, actual.telegramFileSize)
        assertEquals(123, actual.downloadedFileSize)
        verify { telegramSender.sendCachedVideo(100, "cached-id") }
    }

    @Test
    fun mapsResultToDownloadedFileResult() {
        val actual = TelegramFileSendResult(
            telegramFileId = "file-id",
            telegramFileSize = 456,
            downloadedFileSize = 123,
        ).toDownloadedFileResult()

        assertEquals("file-id", actual.telegramFileId)
        assertEquals(456, actual.telegramFileSize)
        assertEquals(123, actual.downloadedFileSize)
    }

    private fun job(outputType: OutputType): DownloadJob {
        return DownloadJob(
            id = 1,
            telegramChatId = 100,
            outputType = outputType,
        )
    }
}
