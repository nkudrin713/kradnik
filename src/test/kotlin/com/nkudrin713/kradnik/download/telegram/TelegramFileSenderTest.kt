package com.nkudrin713.kradnik.download.telegram

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.telegram.TelegramMediaSender
import com.nkudrin713.kradnik.telegram.TelegramSendResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramFileSenderTest {
    private val telegramMediaSender: TelegramMediaSender = mockk()
    private val sender = TelegramFileSender(telegramMediaSender)

    @Test
    fun sendsVideoFile(@TempDir tempDir: Path) = runTest {
        val file = DownloadedFile(tempDir.resolve("video.mp4"), sizeBytes = 123)
        coEvery {
            telegramMediaSender.sendVideo(
                chatId = 100,
                file = file.file,
                replyToMessageId = 200,
            )
        } returns TelegramSendResult("video-id", 456)

        val actual = sender.send(job(OutputType.VIDEO), file)

        assertEquals("video-id", actual.telegramFileId)
        assertEquals(456, actual.telegramFileSize)
        assertEquals(123, actual.downloadedFileSize)
        coVerify {
            telegramMediaSender.sendVideo(
                chatId = 100,
                file = file.file,
                replyToMessageId = 200,
            )
        }
    }

    @Test
    fun sendsAudioFile(@TempDir tempDir: Path) = runTest {
        val file = DownloadedFile(tempDir.resolve("audio.mp3"), sizeBytes = 123)
        coEvery {
            telegramMediaSender.sendAudio(
                chatId = 100,
                file = file.file,
                title = "audio title",
                performer = "artist",
                durationSeconds = 120,
                replyToMessageId = 200,
            )
        } returns TelegramSendResult("audio-id", 456)

        val actual = sender.send(audioJob(), file)

        assertEquals("audio-id", actual.telegramFileId)
        assertEquals(456, actual.telegramFileSize)
        assertEquals(123, actual.downloadedFileSize)
        coVerify {
            telegramMediaSender.sendAudio(
                chatId = 100,
                file = file.file,
                title = "audio title",
                performer = "artist",
                durationSeconds = 120,
                replyToMessageId = 200,
            )
        }
    }

    @Test
    fun sendsCoverFileAsDocument(@TempDir tempDir: Path) = runTest {
        val file = DownloadedFile(tempDir.resolve("cover.jpg"), sizeBytes = 123)
        coEvery {
            telegramMediaSender.sendDocument(100, file.file, replyToMessageId = 200)
        } returns TelegramSendResult("cover-id", 456)

        val actual = sender.send(job(OutputType.COVER), file)

        assertEquals("cover-id", actual.telegramFileId)
        coVerify { telegramMediaSender.sendDocument(100, file.file, replyToMessageId = 200) }
    }

    @Test
    fun sendsCachedVideo() = runTest {
        coEvery {
            telegramMediaSender.sendCachedVideo(
                chatId = 100,
                fileId = "cached-id",
                replyToMessageId = 200,
            )
        } returns TelegramSendResult("video-id", 456)

        val actual = sender.sendCached(job(OutputType.VIDEO), "cached-id", downloadedFileSize = 123)

        assertEquals("video-id", actual.telegramFileId)
        assertEquals(456, actual.telegramFileSize)
        assertEquals(123, actual.downloadedFileSize)
        coVerify {
            telegramMediaSender.sendCachedVideo(
                chatId = 100,
                fileId = "cached-id",
                replyToMessageId = 200,
            )
        }
    }

    @Test
    fun sendsCachedAudio() = runTest {
        coEvery {
            telegramMediaSender.sendCachedAudio(
                chatId = 100,
                fileId = "cached-id",
                replyToMessageId = 200,
            )
        } returns TelegramSendResult("audio-id", 456)

        val actual = sender.sendCached(job(OutputType.AUDIO), "cached-id", downloadedFileSize = 123)

        assertEquals("audio-id", actual.telegramFileId)
        assertEquals(456, actual.telegramFileSize)
        assertEquals(123, actual.downloadedFileSize)
        coVerify {
            telegramMediaSender.sendCachedAudio(
                chatId = 100,
                fileId = "cached-id",
                replyToMessageId = 200,
            )
        }
    }

    @Test
    fun sendsCachedCoverDocument() = runTest {
        coEvery {
            telegramMediaSender.sendCachedDocument(100, "cached-id", replyToMessageId = 200)
        } returns TelegramSendResult("cover-id", 456)

        val actual = sender.sendCached(job(OutputType.COVER), "cached-id", downloadedFileSize = 123)

        assertEquals("cover-id", actual.telegramFileId)
        coVerify { telegramMediaSender.sendCachedDocument(100, "cached-id", replyToMessageId = 200) }
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
            telegramRequestMessageId = 200,
            outputType = outputType,
        )
    }

    private fun audioJob(): DownloadJob {
        return job(OutputType.AUDIO).apply {
            sourceAudioTitle = "audio title"
            sourceAudioPerformer = "artist"
            sourceDurationSeconds = 120
        }
    }
}
