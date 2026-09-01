package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.TelegramUserPreferenceService
import com.nkudrin713.kradnik.telegram.localization.telegramMessages
import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.Chat
import com.pengrad.telegrambot.model.User
import com.pengrad.telegrambot.model.message.MaybeInaccessibleMessage
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramLanguageSelectorTest {
    private val preferenceService: TelegramUserPreferenceService = mockk()
    private val telegramSender: TelegramSender = mockk()
    private val selector = TelegramLanguageSelector(
        preferenceService = preferenceService,
        messages = telegramMessages(),
        telegramSender = telegramSender,
    )

    @Test
    fun showsBilingualPromptWithLanguageButtons() {
        val text = slot<String>()
        val keyboard = slot<InlineKeyboardMarkup>()
        every { telegramSender.sendMessage(100, capture(text), capture(keyboard)) } just runs

        selector.show(100)

        assertEquals("Please choose your language:\nПожалуйста, выберите язык:", text.captured)
        assertEquals(
            listOf("English", "Русский"),
            keyboard.captured.inlineKeyboard().single().map { it.text },
        )
        assertEquals(
            listOf("lang:en", "lang:ru"),
            keyboard.captured.inlineKeyboard().single().map { it.callbackData },
        )
    }

    @Test
    fun savesSelectedLanguageAndReplacesPicker() {
        val callbackQuery = callbackQuery("lang:ru")
        every { preferenceService.selectLanguage(300, BotLanguage.RU) } just runs
        every { telegramSender.answerCallback("callback-id", "Русский", false) } just runs
        every { telegramSender.editMessage(100, 500, any(), any()) } just runs

        assertTrue(selector.handle(callbackQuery))

        verify { preferenceService.selectLanguage(300, BotLanguage.RU) }
        verify { telegramSender.editMessage(100, 500, "Выбран русский язык. Пришли ссылку на медиа.", any()) }
    }

    @Test
    fun ignoresUnknownCallback() {
        assertFalse(selector.handle(callbackQuery("dl:token:option")))

        verify(exactly = 0) { preferenceService.selectLanguage(any(), any()) }
    }

    private fun callbackQuery(data: String): CallbackQuery {
        val message = mockk<MaybeInaccessibleMessage> {
            every { chat() } returns mockk<Chat> { every { id() } returns 100 }
            every { messageId() } returns 500
        }
        return mockk {
            every { id() } returns "callback-id"
            every { this@mockk.data() } returns data
            every { from() } returns mockk<User> { every { id() } returns 300 }
            every { maybeInaccessibleMessage() } returns message
        }
    }
}
