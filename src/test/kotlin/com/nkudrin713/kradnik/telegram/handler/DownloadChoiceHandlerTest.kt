package com.nkudrin713.kradnik.telegram.handler

import com.nkudrin713.kradnik.download.choice.DownloadChoiceOptionSnapshot
import com.nkudrin713.kradnik.download.choice.DownloadChoiceSelection
import com.nkudrin713.kradnik.download.choice.DownloadChoiceSession
import com.nkudrin713.kradnik.download.choice.DownloadChoiceSessionService
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.telegram.DownloadChoiceCallback
import com.nkudrin713.kradnik.telegram.TelegramDownloadStarter
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.Chat
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.User
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import kotlin.test.Test

class DownloadChoiceHandlerTest {
    private val sessionService: DownloadChoiceSessionService = mockk()
    private val starter: TelegramDownloadStarter = mockk()
    private val telegramSender: TelegramSender = mockk()
    private val handler = DownloadChoiceHandler(sessionService, starter, telegramSender)
    private val token = UUID.randomUUID()

    @Test
    fun ignoresUnrelatedCallbacks() {
        handler.handle(callbackQuery(userId = 300, callbackData = "mode:video"))

        verify(exactly = 0) { sessionService.select(any()) }
    }

    @Test
    fun startsSelectedDownloadAndDeletesMenu() {
        val selection = readySelection()
        every { sessionService.select(any()) } returns selection
        every { starter.start(any(), any(), any(), any(), any()) } returns true
        every { telegramSender.answerCallback("callback-id", "Выбрано: 720p", false) } just runs
        every { telegramSender.deleteMessage(100, 500) } just runs

        handler.handle(callbackQuery(userId = 300))

        verify {
            starter.start(
                telegramUserId = 300,
                telegramChatId = 100,
                telegramUpdateId = 400,
                telegramRequestMessageId = 200,
                spec = selection.option.spec,
            )
            telegramSender.deleteMessage(100, 500)
        }
    }

    @Test
    fun rejectsChoiceFromAnotherUser() {
        every { sessionService.select(any()) } returns DownloadChoiceSelection.NotOwner
        every { telegramSender.answerCallback("callback-id", "Это меню другого пользователя", true) } just runs

        handler.handle(callbackQuery(userId = 301))

        verify(exactly = 0) { starter.start(any(), any(), any(), any(), any()) }
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

        handler.handle(callbackQuery(userId = 300))
    }

    private fun readySelection(): DownloadChoiceSelection.Ready {
        val option = DownloadChoiceOptionSnapshot(
            key = "video_720",
            label = "720p",
            sizeBytes = 100_000_000,
            approximateSize = false,
            available = true,
            unavailableReason = null,
            spec = DownloadSpec(
                originalUrl = URL,
                normalizedUrl = URL,
                cacheKey = "cache:720",
                outputType = OutputType.VIDEO,
                platform = DownloadPlatform.YOUTUBE,
                presetName = "video_720",
                formatSelector = "22",
            ),
        )
        return DownloadChoiceSelection.Ready(
            session = DownloadChoiceSession(
                token = token,
                telegramUserId = 300,
                telegramChatId = 100,
                telegramUpdateId = 400,
                telegramRequestMessageId = 200,
                telegramMenuMessageId = 500,
                options = listOf(option),
                expiresAt = Instant.now().plusSeconds(60),
            ),
            option = option,
        )
    }

    private fun callbackQuery(
        userId: Long,
        callbackData: String = DownloadChoiceCallback.encode(token, "video_720"),
    ): CallbackQuery {
        val message = mockk<Message> {
            every { chat() } returns mockk<Chat> { every { id() } returns 100 }
            every { messageId() } returns 500
        }
        return mockk {
            every { id() } returns "callback-id"
            every { data() } returns callbackData
            every { from() } returns mockk<User> { every { id() } returns userId }
            every { message() } returns message
        }
    }

    private companion object {
        private const val URL = "https://example.com/video"
    }
}
