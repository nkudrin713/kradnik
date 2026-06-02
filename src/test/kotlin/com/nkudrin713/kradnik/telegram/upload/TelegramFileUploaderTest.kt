package com.nkudrin713.kradnik.telegram.upload

import com.nkudrin713.kradnik.download.domain.DownloadOutputType
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.telegram.telegrambots.meta.api.methods.send.SendAudio
import org.telegram.telegrambots.meta.api.objects.Audio
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TelegramFileUploaderTest {
    private val telegramClient: TelegramClient = mock()
    private val uploader = TelegramFileUploader(telegramClient)

    @Test
    fun `uploads audio`(@TempDir tempDir: Path) {
        val downloadedFile = downloadedFile(tempDir, sizeBytes = 5)
        val audio: Audio = mock()
        val message: Message = mock()

        whenever(audio.fileId).thenReturn("file-id")
        whenever(audio.fileSize).thenReturn(5)
        whenever(message.audio).thenReturn(audio)
        whenever(telegramClient.execute(any<SendAudio>())).thenReturn(message)

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

        verify(telegramClient, never()).execute(any<SendAudio>())
    }

    @Test
    fun `fails when response does not contain audio`(@TempDir tempDir: Path) {
        val downloadedFile = downloadedFile(tempDir, sizeBytes = 5)
        val message: Message = mock()

        whenever(message.audio).thenReturn(null)
        whenever(telegramClient.execute(any<SendAudio>())).thenReturn(message)

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
