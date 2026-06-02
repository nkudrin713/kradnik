package com.nkudrin713.kradnik.telegram.upload

import com.nkudrin713.kradnik.download.domain.DownloadOutputType
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.telegram.telegrambots.meta.api.methods.send.SendAudio
import org.telegram.telegrambots.meta.api.objects.Audio
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TelegramFileUploaderTest {
    private val telegramClient: TelegramClient = mockk()
    private val uploader = TelegramFileUploader(telegramClient)

    @Test
    fun `uploads audio`(@TempDir tempDir: Path) {
        val downloadedFile = downloadedFile(tempDir, sizeBytes = 5)
        val audio: Audio = mockk()
        val message: Message = mockk()

        every { audio.fileId } returns "file-id"
        every { audio.fileSize } returns 5
        every { message.audio } returns audio
        every { telegramClient.execute(any<SendAudio>()) } returns message

        val actual = uploader.upload(100, DownloadOutputType.AUDIO, downloadedFile)

        assertEquals("file-id", actual.fileId)
        assertEquals(5, actual.fileSize)
    }

    @Test
    fun `rejects too large file`(@TempDir tempDir: Path) {
        val downloadedFile = downloadedFile(tempDir, sizeBytes = 51 * 1024 * 1024)

        assertFailsWith<TelegramFileTooLargeException> {
            uploader.upload(100, DownloadOutputType.AUDIO, downloadedFile)
        }

        verify(exactly = 0) { telegramClient.execute(any<SendAudio>()) }
    }

    @Test
    fun `fails when response does not contain audio`(@TempDir tempDir: Path) {
        val downloadedFile = downloadedFile(tempDir, sizeBytes = 5)
        val message: Message = mockk()

        every { message.audio } returns null
        every { telegramClient.execute(any<SendAudio>()) } returns message

        assertFailsWith<TelegramUploadException> {
            uploader.upload(100, DownloadOutputType.AUDIO, downloadedFile)
        }
    }

    private fun downloadedFile(tempDir: Path, sizeBytes: Long): DownloadedFile {
        val file = tempDir.resolve("audio.mp3")
        file.writeText("audio")
        return DownloadedFile(
            file = file,
            ext = "mp3",
            sizeBytes = sizeBytes,
            args = emptyList(),
        )
    }
}
