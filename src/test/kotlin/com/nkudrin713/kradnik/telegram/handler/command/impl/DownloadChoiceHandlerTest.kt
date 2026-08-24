package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.download.choice.DownloadChoiceOptionSnapshot
import com.nkudrin713.kradnik.download.choice.DownloadChoiceSelection
import com.nkudrin713.kradnik.download.choice.DownloadChoiceSession
import com.nkudrin713.kradnik.download.choice.DownloadChoiceSessionService
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.telegram.DownloadChoiceCallback
import com.nkudrin713.kradnik.telegram.TelegramDownloadStarter
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.handler.TelegramCallbackContext
import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.User
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadChoiceHandlerTest {
    private val sessionService: DownloadChoiceSessionService = mockk()
    private val starter: TelegramDownloadStarter = mockk()
    private val telegramSender: TelegramSender = mockk()
    private val handler = DownloadChoiceHandler(sessionService, starter, telegramSender)
    private val token = UUID.randomUUID()

    @Test
    fun supportsDownloadChoiceCallbacks() {
        assertEquals(true, handler.supports(context(callbackData())))
        assertEquals(false, handler.supports(context("mode:video")))
    }

    @Test
    fun startsSelectedDownloadAndDeletesMenu() {
        val selection = readySelection()
        every { sessionService.select(any()) } returns selection
        every { starter.startResolved(any(), any(), any(), any(), any()) } returns true
        every { telegramSender.answerCallback("callback-id", "Выбрано: 720p", false) } just runs
        every { telegramSender.deleteMessage(100, 500) } just runs

        handler.handle(context(callbackData(), callbackQuery(300)))

        verify {
            starter.startResolved(
                telegramUserId = 300,
                telegramChatId = 100,
                telegramUpdateId = 400,
                telegramRequestMessageId = 200,
                resolvedDownload = selection.option.toResolvedDownload(),
            )
            telegramSender.deleteMessage(100, 500)
        }
    }

    @Test
    fun rejectsChoiceFromAnotherUser() {
        every { sessionService.select(any()) } returns DownloadChoiceSelection.NotOwner
        every { telegramSender.answerCallback("callback-id", "Это меню другого пользователя", true) } just runs

        handler.handle(context(callbackData(), callbackQuery(301)))

        verify(exactly = 0) { starter.startResolved(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { telegramSender.deleteMessage(any(), any()) }
    }

    @Test
    fun reportsExpiredMenu() {
        every { sessionService.select(any()) } returns DownloadChoiceSelection.Expired
        every {
            telegramSender.answerCallback(
                "callback-id",
                "Меню устарело. Отправьте ссылку ещё раз",
                true,
            )
        } just runs

        handler.handle(context(callbackData(), callbackQuery(300)))
    }

    private fun readySelection(): DownloadChoiceSelection.Ready {
        val option = DownloadChoiceOptionSnapshot(
            key = "video_720",
            label = "720p",
            sizeBytes = 100_000_000,
            approximateSize = false,
            available = true,
            unavailableReason = null,
            originalUrl = URL,
            normalizedUrl = URL,
            cacheKey = "cache:720",
            outputType = OutputType.VIDEO,
            presetName = "video_720",
            formatSelector = "22",
            extraArgs = emptyList(),
        )
        return DownloadChoiceSelection.Ready(
            session = DownloadChoiceSession(
                token = token,
                telegramUserId = 300,
                telegramChatId = 100,
                telegramUpdateId = 400,
                telegramRequestMessageId = 200,
                telegramMenuMessageId = 500,
                originalUrl = URL,
                normalizedUrl = URL,
                options = listOf(option),
                expiresAt = Instant.now().plusSeconds(60),
            ),
            option = option,
        )
    }

    private fun context(
        text: String,
        callbackQuery: CallbackQuery = mockk(relaxed = true),
    ): TelegramCallbackContext {
        return TelegramCallbackContext(
            update = mockk(),
            callbackQuery = callbackQuery,
            text = text,
            chatId = 100,
            messageId = 500,
        )
    }

    private fun callbackQuery(userId: Long): CallbackQuery {
        return mockk {
            every { id() } returns "callback-id"
            every { from() } returns mockk<User> { every { id() } returns userId }
        }
    }

    private fun callbackData(): String = DownloadChoiceCallback.encode(token, "video_720")

    private companion object {
        private const val URL = "https://example.com/video"
    }
}
