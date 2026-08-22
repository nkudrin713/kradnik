package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.telegram.config.TelegramBotProperties
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramMediaRequestFactoryTest {
    @Test
    fun createsMultipartRequestsForCloudApi(@TempDir tempDir: Path) {
        val file = tempDir.resolve("video.mp4")
        val factory = TelegramMediaRequestFactory(TelegramBotProperties(token = "test-token"))

        val video = factory.video(chatId = 1, file = file)
        val audio = factory.audio(chatId = 1, file = file)

        assertTrue(video.isMultipart)
        assertTrue(audio.isMultipart)
    }

    @Test
    fun createsLocalFileRequestsWithoutMultipart(@TempDir tempDir: Path) {
        val file = tempDir.resolve("media file.mp4")
        val properties = TelegramBotProperties(
            token = "test-token",
            localMode = true,
            apiUrl = "http://telegram-bot-api:8081/bot",
            fileApiUrl = "http://telegram-bot-api:8081/file/bot",
        )
        val factory = TelegramMediaRequestFactory(properties)

        val video = factory.video(chatId = 1, file = file)
        val audio = factory.audio(chatId = 1, file = file)

        assertFalse(video.isMultipart)
        assertFalse(audio.isMultipart)
        assertEquals(file.toAbsolutePath().toUri().toString(), video.parameters["video"])
        assertEquals(file.toAbsolutePath().toUri().toString(), audio.parameters["audio"])
    }
}
