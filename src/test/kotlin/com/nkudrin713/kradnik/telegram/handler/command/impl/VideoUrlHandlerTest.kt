package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import com.nkudrin713.kradnik.download.platform.UnsupportedPlatformException
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.identity.UrlIdentityResolver
import com.nkudrin713.kradnik.settings.DownloadSettingsService
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.handler.TelegramUpdateContext
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.User
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoUrlHandlerTest {
    private val downloadJobService: DownloadJobService = mockk()
    private val downloadSettingsService: DownloadSettingsService = mockk()
    private val platformResolver: PlatformResolver = mockk()
    private val urlIdentityResolver: UrlIdentityResolver = mockk()
    private val telegramSender: TelegramSender = mockk()
    private val handler = VideoUrlHandler(
        downloadJobService = downloadJobService,
        downloadSettingsService = downloadSettingsService,
        platformResolver = platformResolver,
        urlIdentityResolver = urlIdentityResolver,
        telegramSender = telegramSender,
    )

    @Test
    fun supportsHttpUrls() {
        assertEquals(true, handler.supports(context("https://example.com/video")))
        assertEquals(true, handler.supports(context("http://example.com/video")))
        assertEquals(false, handler.supports(context("text")))
    }

    @Test
    fun sendsAvailablePlatformsWhenPlatformIsDisabled() {
        every { downloadSettingsService.getOutputType(100) } returns OutputType.VIDEO
        every { platformResolver.resolve("https://www.instagram.com/reel/abc/") } throws UnsupportedPlatformException(
            "Платформа не поддерживается. Доступные платформы: YouTube."
        )
        every { telegramSender.sendMessage(100, any()) } just runs

        handler.handle(context("https://www.instagram.com/reel/abc/", message = message()))

        verify {
            telegramSender.sendMessage(
                100,
                "Платформа не поддерживается. Доступные платформы: YouTube.",
            )
        }
        verify(exactly = 0) { downloadJobService.createJob(any()) }
        verify(exactly = 0) { urlIdentityResolver.resolve(any(), any(), any()) }
    }

    private fun context(
        text: String,
        message: Message? = null,
    ): TelegramUpdateContext {
        return TelegramUpdateContext(
            update = mockk(),
            message = message,
            callbackQuery = null,
            text = text,
            chatId = 100,
            messageId = 200,
        )
    }

    private fun message(): Message {
        val user = mockk<User> {
            every { id() } returns 300
        }
        return mockk {
            every { from() } returns user
        }
    }
}
