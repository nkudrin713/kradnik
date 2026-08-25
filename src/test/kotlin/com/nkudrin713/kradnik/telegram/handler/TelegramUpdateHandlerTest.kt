package com.nkudrin713.kradnik.telegram.handler

import com.nkudrin713.kradnik.telegram.DownloadChoiceCoordinator
import com.nkudrin713.kradnik.telegram.PrepareDownloadChoiceCommand
import com.nkudrin713.kradnik.telegram.TelegramDonationSender
import com.nkudrin713.kradnik.telegram.TelegramSender
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
    fun sendsDonationLinkWhenConfigured() {
        every { donationSender.sendMessage(100, "https://example.com/donate") } just runs

        handler(donationUrl = "https://example.com/donate").handle(textUpdate("/donate"))

        verify { donationSender.sendMessage(100, "https://example.com/donate") }
    }

    @Test
    fun sendsDonationFallbackWhenLinkIsMissing() {
        every { telegramSender.sendMessage(100, any()) } just runs

        handler().handle(textUpdate("/donate"))

        verify { telegramSender.sendMessage(100, "Донат еще не настроен. Пиратская бухгалтерия спит.") }
    }

    @Test
    fun preparesDownloadChoiceForUrl() {
        every { coordinator.prepare(any()) } just runs

        handler().handle(textUpdate("https://example.com/video"))

        verify {
            coordinator.prepare(
                PrepareDownloadChoiceCommand(
                    telegramUserId = 300,
                    telegramChatId = 100,
                    telegramUpdateId = 400,
                    telegramRequestMessageId = 200,
                    url = "https://example.com/video",
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
            every { message() } returns null
            every { callbackQuery() } returns callbackQuery
        }
        every { choiceHandler.handle(callbackQuery) } just runs

        handler().handle(update)

        verify { choiceHandler.handle(callbackQuery) }
    }

    private fun handler(donationUrl: String = ""): TelegramUpdateHandler {
        return TelegramUpdateHandler(
            downloadChoiceCoordinator = coordinator,
            downloadChoiceHandler = choiceHandler,
            telegramSender = telegramSender,
            telegramDonationSender = donationSender,
            donationUrl = donationUrl,
        )
    }

    private fun updateWithPinnedMessage(chatId: Long, messageId: Int): Update {
        val message = mockk<Message> {
            every { pinnedMessage() } returns mockk()
            every { chat() } returns mockk<Chat> { every { id() } returns chatId }
            every { messageId() } returns messageId
        }
        return mockk { every { message() } returns message }
    }

    private fun textUpdate(text: String): Update {
        val message = mockk<Message> {
            every { pinnedMessage() } returns null
            every { text() } returns text
            every { chat() } returns mockk<Chat> { every { id() } returns 100 }
            every { messageId() } returns 200
            every { from() } returns mockk<User> { every { id() } returns 300 }
        }
        return mockk {
            every { message() } returns message
            every { updateId() } returns 400
        }
    }
}
