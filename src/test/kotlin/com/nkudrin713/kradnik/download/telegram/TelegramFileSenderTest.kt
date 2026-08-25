package com.nkudrin713.kradnik.download.telegram

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.telegram.TelegramMediaSender
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
        } returns "video-id"

        val actual = sender.send(job(OutputType.VIDEO), file)

        assertEquals("video-id", actual)
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
        } returns "audio-id"

        val actual = sender.send(audioJob(), file)

        assertEquals("audio-id", actual)
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
        } returns "cover-id"

        val actual = sender.send(job(OutputType.COVER), file)

        assertEquals("cover-id", actual)
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
        } returns "video-id"

        val actual = sender.sendCached(job(OutputType.VIDEO), "cached-id")

        assertEquals("video-id", actual)
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
        } returns "audio-id"

        val actual = sender.sendCached(job(OutputType.AUDIO), "cached-id")

        assertEquals("audio-id", actual)
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
        } returns "cover-id"

        val actual = sender.sendCached(job(OutputType.COVER), "cached-id")

        assertEquals("cover-id", actual)
        coVerify { telegramMediaSender.sendCachedDocument(100, "cached-id", replyToMessageId = 200) }
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
