package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.analytics.DownloadAnalytics
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.identity.DownloadIdentity
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import com.nkudrin713.kradnik.download.platform.ResolvedDownload
import com.nkudrin713.kradnik.download.platform.UnsupportedPlatformException
import com.nkudrin713.kradnik.download.request.DownloadRequest
import com.nkudrin713.kradnik.download.service.CreateDownloadJobCommand
import com.nkudrin713.kradnik.download.service.CreateDownloadJobResult
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.settings.DownloadSettingsService
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.handler.TelegramUpdateContext
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.User
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VideoUrlHandlerTest {
    private val downloadJobService: DownloadJobService = mockk()
    private val downloadSettingsService: DownloadSettingsService = mockk()
    private val platformResolver: PlatformResolver = mockk()
    private val telegramSender: TelegramSender = mockk()
    private val downloadAnalytics: DownloadAnalytics = mockk(relaxed = true)
    private val handler = VideoUrlHandler(
        downloadJobService = downloadJobService,
        downloadSettingsService = downloadSettingsService,
        platformResolver = platformResolver,
        telegramSender = telegramSender,
        downloadAnalytics = downloadAnalytics,
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
        every {
            platformResolver.resolve(
                "https://www.instagram.com/reel/abc/",
                OutputType.VIDEO,
            )
        } throws UnsupportedPlatformException(
            "Платформа не поддерживается. Доступные платформы: YouTube.",
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
    }

    @Test
    fun deletesNewStatusForRepeatedTelegramUpdate() {
        val context = context("https://example.com/video", message = message())
        every { downloadSettingsService.getOutputType(100) } returns OutputType.VIDEO
        every { platformResolver.resolve(context.text, OutputType.VIDEO) } returns resolvedDownload()
        every { telegramSender.sendStatus(100, any()) } returns 500
        every { downloadJobService.createJob(any()) } returns CreateDownloadJobResult.Existing(DownloadJob(id = 1))
        every { telegramSender.deleteMessage(100, 500) } just runs

        handler.handle(context)

        verify { telegramSender.deleteMessage(100, 500) }
        verify(exactly = 0) { downloadAnalytics.recordDownloadRequested(any(), any()) }
    }

    @Test
    fun recordsNewJobWithoutDeletingStatus() {
        val context = context("https://example.com/video", message = message())
        val job = DownloadJob(id = 1)
        val command = slot<CreateDownloadJobCommand>()
        every { downloadSettingsService.getOutputType(100) } returns OutputType.VIDEO
        every { platformResolver.resolve(context.text, OutputType.VIDEO) } returns resolvedDownload()
        every { telegramSender.sendStatus(100, any()) } returns 500
        every { downloadJobService.createJob(capture(command)) } returns CreateDownloadJobResult.Created(job)

        handler.handle(context)

        assertEquals("video:telegram-video-h264-v1", command.captured.cacheKey)
        verify { downloadAnalytics.recordDownloadRequested(any(), job) }
        verify(exactly = 0) { telegramSender.deleteMessage(any(), any()) }
    }

    @Test
    fun keepsAudioCacheKeyUnchanged() {
        val context = context("https://example.com/video", message = message())
        val command = slot<CreateDownloadJobCommand>()
        every { downloadSettingsService.getOutputType(100) } returns OutputType.AUDIO
        every {
            platformResolver.resolve(context.text, OutputType.AUDIO)
        } returns resolvedDownload(OutputType.AUDIO)
        every { telegramSender.sendStatus(100, any()) } returns 500
        every {
            downloadJobService.createJob(capture(command))
        } returns CreateDownloadJobResult.Created(DownloadJob(id = 1))

        handler.handle(context)

        assertEquals("video", command.captured.cacheKey)
    }

    @Test
    fun deletesStatusWhenJobCreationFails() {
        val context = context("https://example.com/video", message = message())
        every { downloadSettingsService.getOutputType(100) } returns OutputType.VIDEO
        every { platformResolver.resolve(context.text, OutputType.VIDEO) } returns resolvedDownload()
        every { telegramSender.sendStatus(100, any()) } returns 500
        every { downloadJobService.createJob(any()) } throws IllegalStateException("database error")
        every { telegramSender.deleteMessage(100, 500) } just runs

        assertFailsWith<IllegalStateException> {
            handler.handle(context)
        }

        verify { telegramSender.deleteMessage(100, 500) }
    }

    private fun context(
        text: String,
        message: Message? = null,
    ): TelegramUpdateContext {
        val update = mockk<Update> {
            every { updateId() } returns 400
        }
        return TelegramUpdateContext(
            update = update,
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

    private fun resolvedDownload(outputType: OutputType = OutputType.VIDEO): ResolvedDownload {
        return ResolvedDownload(
            identity = DownloadIdentity(
                originalUrl = "https://example.com/video",
                normalizedUrl = "https://example.com/video",
                cacheKey = "video",
            ),
            request = DownloadRequest(
                originalUrl = "https://example.com/video",
                normalizedUrl = "https://example.com/video",
                outputType = outputType,
                formatSelector = "format",
                presetName = "preset",
            ),
        )
    }
}
