package com.nkudrin713.kradnik.telegram.handler

import com.nkudrin713.kradnik.telegram.DownloadChoiceCoordinator
import com.nkudrin713.kradnik.telegram.PrepareDownloadChoiceCommand
import com.nkudrin713.kradnik.telegram.TelegramDonationSender
import com.nkudrin713.kradnik.telegram.TelegramLanguageSelector
import com.nkudrin713.kradnik.telegram.TelegramMessageAddress
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.TelegramUserPreferenceService
import com.nkudrin713.kradnik.telegram.localization.telegramMessages
import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.Chat
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.User
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlin.test.Test

class TelegramUpdateHandlerTest {
    private val coordinator: DownloadChoiceCoordinator = mockk()
    private val choiceHandler: DownloadChoiceHandler = mockk()
    private val telegramSender: TelegramSender = mockk()
    private val donationSender: TelegramDonationSender = mockk()
    private val languageSelector: TelegramLanguageSelector = mockk()
    private val preferenceService: TelegramUserPreferenceService = mockk()

    @Test
    fun deletesPinServiceMessage() {
        val update = updateWithPinnedMessage(chatId = 100, messageId = 200)
        every { telegramSender.deleteMessage(100, 200) } just runs

        handler().handle(update)

        verify { telegramSender.deleteMessage(100, 200) }
        verify(exactly = 0) { coordinator.prepare(any()) }
        verify(exactly = 0) { choiceHandler.handle(any()) }
    }

    @Test
    fun handlesSimpleCommandsDirectly() {
        every { telegramSender.sendMessage(100, any()) } just runs
        every { preferenceService.selectedLanguage(300) } returns BotLanguage.RU

        handler().handle(textUpdate("/start"))
        handler().handle(textUpdate("/help"))
        handler().handle(textUpdate("/legal"))
        handler().handle(textUpdate("unknown"))

        verify { telegramSender.sendMessage(100, "Пришли ссылку на медиа") }
        verify { telegramSender.sendMessage(100, match { it.startsWith("Что умеет бот:") }) }
        verify { telegramSender.sendMessage(100, match { it.startsWith("Дисклеймер:") }) }
        verify { telegramSender.sendMessage(100, "Нужна ссылка") }
    }

    @Test
    fun asksForLanguageOnFirstStart() {
        every { preferenceService.selectedLanguage(300) } returns null
        every { languageSelector.show(100) } just runs

        handler().handle(textUpdate("/start", languageCode = null))

        verify { languageSelector.show(100) }
        verify(exactly = 0) { telegramSender.sendMessage(any(), any()) }
    }

    @Test
    fun defaultsToEnglishWhenLanguageWasNotSelected() {
        every { preferenceService.selectedLanguage(300) } returns null
        every { telegramSender.sendMessage(100, "I need a link") } just runs

        handler().handle(textUpdate("unknown", languageCode = "ru"))

        verify { telegramSender.sendMessage(100, "I need a link") }
    }

    @Test
    fun sendsDonationLinkWhenConfigured() {
        every {
            donationSender.sendMessage(100, "https://example.com/donate", BotLanguage.RU)
        } just runs
        every { preferenceService.selectedLanguage(300) } returns BotLanguage.RU

        handler(donationUrl = "https://example.com/donate").handle(textUpdate("/donate"))

        verify { donationSender.sendMessage(100, "https://example.com/donate", BotLanguage.RU) }
    }

    @Test
    fun sendsDonationFallbackWhenLinkIsMissing() {
        every { telegramSender.sendMessage(100, any()) } just runs
        every { preferenceService.selectedLanguage(300) } returns BotLanguage.RU

        handler().handle(textUpdate("/donate"))

        verify { telegramSender.sendMessage(100, "Донат еще не настроен. Пиратская бухгалтерия спит.") }
    }

    @Test
    fun preparesDownloadChoiceForUrl() {
        every { coordinator.prepare(any()) } just runs
        every { preferenceService.selectedLanguage(300) } returns BotLanguage.RU

        handler().handle(textUpdate("https://example.com/video"))

        verify {
            coordinator.prepare(
                PrepareDownloadChoiceCommand(
                    telegramUserId = 300,
                    telegramChatId = 100,
                    telegramUpdateId = 400,
                    telegramRequestMessageId = 200,
                    url = "https://example.com/video",
                    language = BotLanguage.RU,
                )
            )
        }
    }

    @Test
    fun delegatesCallbackUpdates() {
        val callbackQuery = mockk<CallbackQuery> {
            every { data() } returns "dl:token:option"
        }
        val update = mockk<Update> {
            every { guestMessage() } returns null
            every { message() } returns null
            every { callbackQuery() } returns callbackQuery
        }
        every { languageSelector.handle(callbackQuery) } returns false
        every { choiceHandler.handle(callbackQuery) } just runs

        handler().handle(update)

        verify { choiceHandler.handle(callbackQuery) }
    }

    @Test
    fun preparesGuestDownloadFromMentionAndUrl() {
        every { coordinator.prepare(any()) } just runs
        every { preferenceService.resolveLanguage(300) } returns BotLanguage.RU

        handler().handle(guestUpdate("@kradnik_bot https://example.com/video"))

        verify {
            coordinator.prepare(
                PrepareDownloadChoiceCommand(
                    telegramUserId = 300,
                    telegramChatId = 100,
                    telegramUpdateId = 400,
                    telegramRequestMessageId = 200,
                    url = "https://example.com/video",
                    language = BotLanguage.RU,
                    guestQueryId = "guest-query",
                )
            )
        }
    }

    @Test
    fun answersInvalidGuestQueryOnce() {
        every { preferenceService.resolveLanguage(300) } returns BotLanguage.RU
        every {
            telegramSender.answerGuestMessage("guest-query", "Нужна ссылка", BotLanguage.RU)
        } returns TelegramMessageAddress.Inline("inline-message")

        handler().handle(guestUpdate("@kradnik_bot не-ссылка"))

        verify { telegramSender.answerGuestMessage("guest-query", "Нужна ссылка", BotLanguage.RU) }
        verify(exactly = 0) { coordinator.prepare(any()) }
    }

    private fun handler(donationUrl: String = ""): TelegramUpdateHandler {
        return TelegramUpdateHandler(
            downloadChoiceCoordinator = coordinator,
            downloadChoiceHandler = choiceHandler,
            telegramSender = telegramSender,
            telegramDonationSender = donationSender,
            languageSelector = languageSelector,
            preferenceService = preferenceService,
            messages = telegramMessages(),
            donationUrl = donationUrl,
        )
    }

    private fun updateWithPinnedMessage(chatId: Long, messageId: Int): Update {
        val message = mockk<Message> {
            every { pinnedMessage() } returns mockk()
            every { chat() } returns mockk<Chat> { every { id() } returns chatId }
            every { messageId() } returns messageId
        }
        return mockk {
            every { guestMessage() } returns null
            every { message() } returns message
        }
    }

    private fun textUpdate(text: String, languageCode: String? = "ru"): Update {
        val message = mockk<Message> {
            every { pinnedMessage() } returns null
            every { text() } returns text
            every { chat() } returns mockk<Chat> { every { id() } returns 100 }
            every { messageId() } returns 200
            every { from() } returns mockk<User> {
                every { id() } returns 300
                every { languageCode() } returns languageCode
            }
        }
        return mockk {
            every { guestMessage() } returns null
            every { message() } returns message
            every { updateId() } returns 400
        }
    }

    private fun guestUpdate(text: String): Update {
        val message = mockk<Message> {
            every { text() } returns text
            every { guestQueryId() } returns "guest-query"
            every { chat() } returns mockk<Chat> { every { id() } returns 100 }
            every { messageId() } returns 200
            every { from() } returns mockk<User> {
                every { id() } returns 300
                every { languageCode() } returns "ru"
            }
        }
        return mockk {
            every { guestMessage() } returns message
            every { message() } returns null
            every { updateId() } returns 400
        }
    }
}
