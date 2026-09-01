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
import com.nkudrin713.kradnik.telegram.TelegramMessageAddress
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.TelegramUserPreferenceService
import com.nkudrin713.kradnik.telegram.localization.telegramMessages
import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.Chat
import com.pengrad.telegrambot.model.User
import com.pengrad.telegrambot.model.message.MaybeInaccessibleMessage
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
    private val preferenceService: TelegramUserPreferenceService = mockk()
    private val handler = DownloadChoiceHandler(
        sessionService = sessionService,
        telegramDownloadStarter = starter,
        telegramSender = telegramSender,
        preferenceService = preferenceService,
        messages = telegramMessages(),
    )
    private val token = UUID.randomUUID()

    @Test
    fun ignoresUnrelatedCallbacks() {
        handler.handle(callbackQuery(userId = 300, callbackData = "mode:video"))

        verify(exactly = 0) { sessionService.select(any()) }
    }

    @Test
    fun startsSelectedDownloadAndDeletesMenu() {
        val selection = readySelection()
        every { preferenceService.resolveLanguage(300) } returns BotLanguage.RU
        every { sessionService.select(any()) } returns selection
        every { starter.start(any(), any(), any(), any(), any(), any(), any()) } just runs
        every { telegramSender.answerCallback("callback-id", "Выбрано: 720p", false) } just runs
        every { telegramSender.deleteMessage(100, 500) } just runs

        handler.handle(callbackQuery(userId = 300))

        verify {
            starter.start(
                telegramUserId = 300,
                telegramChatId = 100,
                telegramUpdateId = 400,
                telegramRequestMessageId = 200,
                messageAddress = TelegramMessageAddress.Chat(100, 500),
                spec = selection.option.spec,
                language = BotLanguage.RU,
            )
            telegramSender.deleteMessage(100, 500)
        }
    }

    @Test
    fun rejectsChoiceFromAnotherUser() {
        every { preferenceService.resolveLanguage(301) } returns BotLanguage.RU
        every { sessionService.select(any()) } returns DownloadChoiceSelection.NotOwner
        every { telegramSender.answerCallback("callback-id", "Это меню другого пользователя", true) } just runs

        handler.handle(callbackQuery(userId = 301))

        verify(exactly = 0) { starter.start(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { telegramSender.deleteMessage(any(), any()) }
    }

    @Test
    fun startsInlineDownloadWithoutDeletingGuestMessage() {
        val selection = readySelection().apply {
            session.telegramMenuMessageId = null
            session.telegramInlineMessageId = "inline-message"
        }
        every { preferenceService.resolveLanguage(300) } returns BotLanguage.RU
        every { sessionService.select(any()) } returns selection
        every { starter.start(any(), any(), any(), any(), any(), any(), any()) } just runs
        every { telegramSender.answerCallback("callback-id", "Выбрано: 720p", false) } just runs

        handler.handle(callbackQuery(userId = 300, inlineMessageId = "inline-message"))

        verify {
            starter.start(
                telegramUserId = 300,
                telegramChatId = 100,
                telegramUpdateId = 400,
                telegramRequestMessageId = 200,
                messageAddress = TelegramMessageAddress.Inline("inline-message"),
                spec = selection.option.spec,
                language = BotLanguage.RU,
            )
        }
        verify(exactly = 0) { telegramSender.deleteMessage(any(), any()) }
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
                language = BotLanguage.RU,
                options = listOf(option),
                cleanupAfter = Instant.now().plusSeconds(60),
            ),
            option = option,
        )
    }

    private fun callbackQuery(
        userId: Long,
        callbackData: String = DownloadChoiceCallback.encode(token, "video_720"),
        inlineMessageId: String? = null,
    ): CallbackQuery {
        val message = mockk<MaybeInaccessibleMessage> {
            every { chat() } returns mockk<Chat> { every { id() } returns 100 }
            every { messageId() } returns 500
        }
        val user = mockk<User> {
            every { id() } returns userId
            every { languageCode() } returns "ru"
        }
        return mockk {
            every { id() } returns "callback-id"
            every { data() } returns callbackData
            every { from() } returns user
            every { inlineMessageId() } returns inlineMessageId
            every { maybeInaccessibleMessage() } returns if (inlineMessageId == null) message else null
        }
    }

    private companion object {
        private const val URL = "https://example.com/video"
    }
}
