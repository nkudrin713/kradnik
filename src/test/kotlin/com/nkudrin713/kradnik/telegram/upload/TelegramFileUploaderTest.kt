package com.nkudrin713.kradnik.telegram.upload

import com.nkudrin713.kradnik.download.domain.DownloadOutputType
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import io.mockk.every
import io.mockk.slot
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.telegram.telegrambots.meta.api.methods.send.SendAudio
import org.telegram.telegrambots.meta.api.methods.send.SendVideo
import org.telegram.telegrambots.meta.api.objects.Audio
import org.telegram.telegrambots.meta.api.objects.Video
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

        val actual = uploader.upload(100, 1, DownloadOutputType.AUDIO, downloadedFile, "Song title")

        assertEquals("file-id", actual.fileId)
        assertEquals(5, actual.fileSize)
    }

    @Test
    fun `rejects too large file`(@TempDir tempDir: Path) {
        val downloadedFile = downloadedFile(tempDir, sizeBytes = 51 * 1024 * 1024)

        assertFailsWith<TelegramFileTooLargeException> {
            uploader.upload(100, 1, DownloadOutputType.AUDIO, downloadedFile, "Song title")
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
            uploader.upload(100, 1, DownloadOutputType.AUDIO, downloadedFile, "Song title")
        }
    }

    @Test
    fun `uploads audio with source title filename`(@TempDir tempDir: Path) {
        val downloadedFile = downloadedFile(tempDir, sizeBytes = 5)
        val audio: Audio = mockk()
        val message: Message = mockk()
        val slot = slot<SendAudio>()

        every { audio.fileId } returns "file-id"
        every { audio.fileSize } returns 5
        every { message.audio } returns audio
        every { telegramClient.execute(capture(slot)) } returns message

        uploader.upload(100, 1, DownloadOutputType.AUDIO, downloadedFile, "Bad/File:Name?")

        assertEquals("attach://Bad File Name.mp3", slot.captured.audio.attachName)
    }

    @Test
    fun `uploads video`(@TempDir tempDir: Path) {
        val downloadedFile = downloadedFile(tempDir, "video.mp4", sizeBytes = 5)
        val video: Video = mockk()
        val message: Message = mockk()
        val slot = slot<SendVideo>()

        every { video.fileId } returns "video-id"
        every { video.fileSize } returns 5
        every { message.video } returns video
        every { telegramClient.execute(capture(slot)) } returns message

        val actual = uploader.upload(100, 1, DownloadOutputType.VIDEO, downloadedFile, "Video title")

        assertEquals("video-id", actual.fileId)
        assertEquals(5, actual.fileSize)
        assertEquals("attach://Video title.mp4", slot.captured.video.attachName)
        assertEquals(true, slot.captured.supportsStreaming)
    }

    private fun downloadedFile(tempDir: Path, sizeBytes: Long): DownloadedFile =
        downloadedFile(tempDir, "audio.mp3", sizeBytes)

    private fun downloadedFile(tempDir: Path, name: String, sizeBytes: Long): DownloadedFile {
        val file = tempDir.resolve(name)
        file.writeText("audio")
        return DownloadedFile(
            file = file,
            ext = file.fileName.toString().substringAfterLast("."),
            sizeBytes = sizeBytes,
            args = emptyList(),
        )
    }
}
