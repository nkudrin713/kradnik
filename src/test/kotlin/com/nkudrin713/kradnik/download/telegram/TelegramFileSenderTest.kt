package com.nkudrin713.kradnik.download.telegram

import com.nkudrin713.kradnik.download.domain.AudioMetadata
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaArtifact
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.PostMedia
import com.nkudrin713.kradnik.download.domain.PostMediaKind
import com.nkudrin713.kradnik.telegram.TelegramMediaSender
import com.nkudrin713.kradnik.telegram.TelegramPostSender
import com.nkudrin713.kradnik.telegram.TelegramSendException
import com.nkudrin713.kradnik.telegram.TelegramSendFailureKind
import com.nkudrin713.kradnik.telegram.config.TelegramBotProperties
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TelegramFileSenderTest {
    private val telegramMediaSender: TelegramMediaSender = mockk()
    private val postSender: TelegramPostSender = mockk()
    private val sender = TelegramFileSender(
        telegramMediaSender = telegramMediaSender,
        postSender = postSender,
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

        val actual = sender.send(DeliveryContext(100, 200), MediaArtifact.Video(file.file))

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

        val actual = sender.send(DeliveryContext(100, 200), MediaArtifact.Audio(file.file, AudioMetadata("audio title", "artist", 120)))

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

        val actual = sender.send(DeliveryContext(100, 200), MediaArtifact.Document(file.file))

        assertEquals("cover-id", actual)
        coVerify { telegramMediaSender.sendDocument(100, file.file, replyToMessageId = 200) }
    }

    @Test
    fun sendsImageGroupAndEncodesEveryFileId(@TempDir tempDir: Path) = runTest {
        val first = tempDir.resolve("01.jpg")
        val second = tempDir.resolve("02.jpg")
        val file = DownloadedFile(first, sizeBytes = 123, additionalFiles = listOf(second))
        coEvery {
            telegramMediaSender.sendPhotos(100, listOf(first, second), replyToMessageId = 200)
        } returns listOf("photo-1", "photo-2")

        val actual = sender.send(DeliveryContext(100, 200), MediaArtifact.Photos(file.files))

        assertEquals("photo-group:[\"photo-1\",\"photo-2\"]", actual)
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

        val actual = sender.sendCached(DeliveryContext(100, 200), TelegramReceiptCodec.decode(OutputType.VIDEO, "cached-id"))

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
    fun attachesTextToLegacyCachedVideoPost() = runTest {
        val context = DeliveryContext(100, 200, postText = "Post <text>")
        val items = listOf(CachedPostItem(PostMediaKind.VIDEO, "cached-id"))
        coEvery { postSender.sendCached(context, items) } returns items
        val actual = sender.sendCached(context, TelegramReceiptCodec.decode(OutputType.VIDEO, "cached-id"))
        assertEquals(TelegramReceiptCodec.post(items), actual)
        coVerify(exactly = 0) { telegramMediaSender.sendMonospaceText(any(), any(), any()) }
        coVerify(exactly = 0) { telegramMediaSender.sendCachedVideo(any(), any(), any()) }
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

        val actual = sender.sendCached(DeliveryContext(100, 200), TelegramReceiptCodec.decode(OutputType.AUDIO, "cached-id"))

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

        val actual = sender.sendCached(DeliveryContext(100, 200), TelegramReceiptCodec.decode(OutputType.COVER, "cached-id"))

        assertEquals("cover-id", actual)
        coVerify { telegramMediaSender.sendCachedDocument(100, "cached-id", replyToMessageId = 200) }
    }

    @Test
    fun sendsCachedImageGroup() = runTest {
        coEvery {
            telegramMediaSender.sendCachedPhotos(
                chatId = 100,
                fileIds = listOf("cached-1", "cached-2"),
                replyToMessageId = 200,
            )
        } returns listOf("photo-1", "photo-2")

        val actual = sender.sendCached(
            DeliveryContext(100, 200),
            CachedMedia.Photos(listOf("cached-1", "cached-2")),
        )

        assertEquals("photo-group:[\"photo-1\",\"photo-2\"]", actual)
    }

    @Test
    fun rejectsImageGroupInInlineMode(@TempDir tempDir: Path) = runTest {
        val job = job(OutputType.IMAGES).apply { telegramInlineMessageId = "inline-message" }

        assertFailsWith<TelegramSendException> {
            sender.send(DeliveryContext.fromJob(job), MediaArtifact.Photos(listOf(tempDir.resolve("01.jpg"))))
        }
    }

    @Test
    fun uploadsFreshInlineVideoToStorageAndEditsGuestMessage(@TempDir tempDir: Path) = runTest {
        val file = DownloadedFile(tempDir.resolve("video.mp4"), sizeBytes = 123)
        val job = job(OutputType.VIDEO).apply { telegramInlineMessageId = "inline-message" }
        coEvery { telegramMediaSender.sendVideo(chatId = 900, file = file.file) } returns "stored-id"
        coEvery { telegramMediaSender.editInlineVideo("inline-message", "stored-id") } returns "stored-id"

        val actual = sender.send(DeliveryContext.fromJob(job), MediaArtifact.Video(file.file))

        assertEquals("stored-id", actual)
        coVerify { telegramMediaSender.sendVideo(chatId = 900, file = file.file) }
        coVerify { telegramMediaSender.editInlineVideo("inline-message", "stored-id") }
    }

    @Test
    fun editsGuestMessageWithCachedAudio() = runTest {
        val job = job(OutputType.AUDIO).apply { telegramInlineMessageId = "inline-message" }
        coEvery {
            telegramMediaSender.editInlineAudio(
                inlineMessageId = "inline-message",
                fileId = "cached-id",
                title = null,
                performer = null,
                durationSeconds = null,
            )
        } returns "cached-id"

        val actual = sender.sendCached(DeliveryContext.fromJob(job), TelegramReceiptCodec.decode(job.outputType, "cached-id"))

        assertEquals("cached-id", actual)
    }

    @Test
    fun rejectsFreshInlineFileWithoutStorageChat(@TempDir tempDir: Path) = runTest {
        val unconfiguredSender = TelegramFileSender(
            telegramMediaSender = telegramMediaSender,
            postSender = postSender,
            properties = TelegramBotProperties(token = "token"),
        )
        val job = job(OutputType.COVER).apply { telegramInlineMessageId = "inline-message" }

        val error = assertFailsWith<TelegramSendException> {
            unconfiguredSender.send(DeliveryContext.fromJob(job), MediaArtifact.Document(tempDir.resolve("cover.jpg")))
        }

        assertEquals(TelegramSendFailureKind.OTHER, error.kind)
    }

    @Test
    fun uploadsFreshInlineAudioWithMetadataBeforeEditing(@TempDir tempDir: Path) = runTest {
        val artifact = MediaArtifact.Audio(tempDir.resolve("audio.mp3"), AudioMetadata("Title", "Artist", 120))
        coEvery { telegramMediaSender.sendAudio(900, artifact.file, "Title", "Artist", 120) } returns "stored-audio"
        coEvery { telegramMediaSender.editInlineAudio("inline", "stored-audio", "Title", "Artist", 120) } returns "stored-audio"

        val receipt = sender.send(DeliveryContext(100, 200, "inline"), artifact)

        assertEquals("stored-audio", receipt)
        coVerifyOrder {
            telegramMediaSender.sendAudio(900, artifact.file, "Title", "Artist", 120)
            telegramMediaSender.editInlineAudio("inline", "stored-audio", "Title", "Artist", 120)
        }
    }

    @Test
    fun uploadsFreshInlineDocumentWithoutSendingPostText(@TempDir tempDir: Path) = runTest {
        val artifact = MediaArtifact.Document(tempDir.resolve("cover.jpg"))
        coEvery { telegramMediaSender.sendDocument(900, artifact.file) } returns "stored-document"
        coEvery { telegramMediaSender.editInlineDocument("inline", "stored-document") } returns "stored-document"

        sender.send(DeliveryContext(100, 200, "inline", "Saved text"), artifact)

        coVerifyOrder {
            telegramMediaSender.sendDocument(900, artifact.file)
            telegramMediaSender.editInlineDocument("inline", "stored-document")
        }
        coVerify(exactly = 0) { telegramMediaSender.sendMonospaceText(any(), any(), any()) }
    }

    @Test
    fun sendsLegacyPhotosAndCaptionTogether(@TempDir tempDir: Path) = runTest {
        val files = listOf(tempDir.resolve("01.jpg"), tempDir.resolve("02.jpg"))
        val context = DeliveryContext(100, 200, postText = "Saved <text>")
        val items = files.map { PostMedia(PostMediaKind.PHOTO, it) }
        val cached = listOf(CachedPostItem(PostMediaKind.PHOTO, "one"), CachedPostItem(PostMediaKind.PHOTO, "two"))
        coEvery { postSender.send(context, items) } returns cached
        assertEquals(TelegramReceiptCodec.post(cached), sender.send(context, MediaArtifact.Photos(files)))
        coVerify(exactly = 0) { telegramMediaSender.sendPhotos(any(), any(), any()) }
        coVerify(exactly = 0) { telegramMediaSender.sendMonospaceText(any(), any(), any()) }
    }

    @Test
    fun propagatesPostFailureWithoutSendingSeparateMedia(@TempDir tempDir: Path) = runTest {
        val file = tempDir.resolve("video.mp4")
        coEvery { postSender.send(any(), any()) } throws TelegramSendException("Post failed")
        assertFailsWith<TelegramSendException> {
            sender.send(DeliveryContext(100, 200, postText = "Saved text"), MediaArtifact.Video(file))
        }
        coVerify(exactly = 1) { postSender.send(any(), any()) }
        coVerify(exactly = 0) { telegramMediaSender.sendVideo(any(), any(), any()) }
    }

    private fun job(outputType: OutputType): DownloadJob {
        return DownloadJob(
            id = 1,
            telegramChatId = 100,
            telegramRequestMessageId = 200,
            outputType = outputType,
        )
    }
}
