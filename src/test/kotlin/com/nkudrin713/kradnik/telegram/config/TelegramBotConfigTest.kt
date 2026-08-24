package com.nkudrin713.kradnik.telegram.config

import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.pengrad.telegrambot.TelegramBot
import okhttp3.OkHttpClient
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TelegramBotConfigTest {
    @Test
    fun buildsClientWithConfiguredLocalEndpointsAndTimeout() {
        val properties = TelegramBotProperties(
            token = "test-token",
            apiUrl = "http://telegram-bot-api:8081/bot/",
            fileApiUrl = "http://telegram-bot-api:8081/file/bot/",
            maxUploadBytes = TelegramUploadLimits.LOCAL_MAX_UPLOAD_BYTES,
            requestTimeout = Duration.ofMinutes(30),
        )

        val config = TelegramBotConfig(properties)
        val bot = config.telegramBot()
        val uploadLimits = config.telegramUploadLimits()

        assertEquals("http://telegram-bot-api:8081/bottest-token/", bot.apiBaseUrl())
        assertEquals("http://telegram-bot-api:8081/file/bottest-token/", bot.fileApiBaseUrl())
        assertEquals(Duration.ofMinutes(30).toMillis().toInt(), bot.httpClient().callTimeoutMillis)
        assertTrue(uploadLimits.localMode)
        assertEquals(TelegramUploadLimits.LOCAL_MAX_UPLOAD_BYTES, uploadLimits.maxUploadBytes)
    }

    @Test
    fun rejectsMixedCloudAndLocalEndpoints() {
        assertFailsWith<IllegalArgumentException> {
            TelegramBotProperties(
                token = "test-token",
                apiUrl = "http://telegram-bot-api:8081/bot",
            )
        }
    }

    @Test
    fun rejectsDifferentLocalServers() {
        assertFailsWith<IllegalArgumentException> {
            TelegramBotProperties(
                token = "test-token",
                apiUrl = "http://telegram-bot-api:8081/bot",
                fileApiUrl = "http://other-bot-api:8081/file/bot",
            )
        }
    }

    @Test
    fun rejectsUploadLimitAboveTelegramMaximum() {
        assertFailsWith<IllegalArgumentException> {
            TelegramBotProperties(
                token = "test-token",
                maxUploadBytes = TelegramUploadLimits.LOCAL_MAX_UPLOAD_BYTES + 1,
            )
        }
    }

    @Test
    fun rejectsLocalUploadLimitWhenCloudModeIsEnabled() {
        assertFailsWith<IllegalArgumentException> {
            TelegramBotProperties(
                token = "test-token",
                maxUploadBytes = TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES + 1,
            )
        }
    }

    private fun TelegramBot.apiBaseUrl(): String {
        val api = field("api")
        return api.field("baseUrl") as String
    }

    private fun TelegramBot.fileApiBaseUrl(): String {
        val fileApi = field("fileApi")
        return fileApi.field("apiUrl") as String
    }

    private fun TelegramBot.httpClient(): OkHttpClient {
        val api = field("api")
        return api.field("client") as OkHttpClient
    }

    private fun Any.field(name: String): Any {
        return javaClass.getDeclaredField(name).run {
            isAccessible = true
            get(this@field)
        }
    }
}
