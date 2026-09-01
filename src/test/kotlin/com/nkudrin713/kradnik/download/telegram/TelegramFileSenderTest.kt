package com.nkudrin713.kradnik.download.telegram

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.telegram.TelegramMediaSender
import com.nkudrin713.kradnik.telegram.TelegramSendException
import com.nkudrin713.kradnik.telegram.TelegramSendFailureKind
import com.nkudrin713.kradnik.telegram.config.TelegramBotProperties
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TelegramFileSenderTest {
    private val telegramMediaSender: TelegramMediaSender = mockk()
    private val sender = TelegramFileSender(
        telegramMediaSender = telegramMediaSender,
        properties = TelegramBotProperties(token = "token", fileStorageChatId = 900),
    )

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

    @Test
    fun uploadsFreshInlineVideoToStorageAndEditsGuestMessage(@TempDir tempDir: Path) = runTest {
        val file = DownloadedFile(tempDir.resolve("video.mp4"), sizeBytes = 123)
        val job = job(OutputType.VIDEO).apply { telegramInlineMessageId = "inline-message" }
        coEvery { telegramMediaSender.sendVideo(chatId = 900, file = file.file) } returns "stored-id"
        coEvery { telegramMediaSender.editInlineVideo("inline-message", "stored-id") } returns "stored-id"

        val actual = sender.send(job, file)

        assertEquals("stored-id", actual)
        coVerify { telegramMediaSender.sendVideo(chatId = 900, file = file.file) }
        coVerify { telegramMediaSender.editInlineVideo("inline-message", "stored-id") }
    }

    @Test
    fun editsGuestMessageWithCachedAudio() = runTest {
        val job = audioJob().apply { telegramInlineMessageId = "inline-message" }
        coEvery {
            telegramMediaSender.editInlineAudio(
                inlineMessageId = "inline-message",
                fileId = "cached-id",
                title = "audio title",
                performer = "artist",
                durationSeconds = 120,
            )
        } returns "cached-id"

        val actual = sender.sendCached(job, "cached-id")

        assertEquals("cached-id", actual)
    }

    @Test
    fun rejectsFreshInlineFileWithoutStorageChat(@TempDir tempDir: Path) = runTest {
        val unconfiguredSender = TelegramFileSender(
            telegramMediaSender = telegramMediaSender,
            properties = TelegramBotProperties(token = "token"),
        )
        val job = job(OutputType.COVER).apply { telegramInlineMessageId = "inline-message" }

        val error = assertFailsWith<TelegramSendException> {
            unconfiguredSender.send(job, DownloadedFile(tempDir.resolve("cover.jpg"), sizeBytes = 123))
        }

        assertEquals(TelegramSendFailureKind.TERMINAL, error.kind)
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
