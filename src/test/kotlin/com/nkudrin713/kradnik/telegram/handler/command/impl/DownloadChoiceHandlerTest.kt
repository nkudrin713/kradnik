package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.telegram.DownloadChoiceCallback
import com.nkudrin713.kradnik.telegram.TelegramDownloadStarter
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.handler.TelegramUpdateContext
import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.User
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadChoiceHandlerTest {
    private val telegramDownloadStarter: TelegramDownloadStarter = mockk()
    private val telegramSender: TelegramSender = mockk()
    private val handler = DownloadChoiceHandler(
        telegramDownloadStarter = telegramDownloadStarter,
        telegramSender = telegramSender,
    )

    @Test
    fun supportsDownloadChoiceCallbacks() {
        assertEquals(true, handler.supports(context(callbackData(OutputType.VIDEO))))
        assertEquals(true, handler.supports(context(callbackData(OutputType.AUDIO))))
        assertEquals(false, handler.supports(context("mode:video")))
    }

    @Test
    fun startsSelectedDownloadAndDeletesMenu() {
        val context = context(
            text = callbackData(OutputType.AUDIO),
            menuMessage = menuMessage(requestMessage()),
            callbackQuery = callbackQuery(userId = 300),
        )
        every {
            telegramSender.answerCallback(
                callbackQueryId = "callback-id",
                text = "Выбрано: Звук",
            )
        } just runs
        every {
            telegramDownloadStarter.start(
                telegramUserId = 300,
                telegramChatId = 100,
                telegramUpdateId = 400,
                telegramRequestMessageId = 200,
                url = URL,
                outputType = OutputType.AUDIO,
            )
        } returns true
        every { telegramSender.deleteMessage(100, 500) } just runs

        handler.handle(context)

        verify {
            telegramDownloadStarter.start(
                telegramUserId = 300,
                telegramChatId = 100,
                telegramUpdateId = 400,
                telegramRequestMessageId = 200,
                url = URL,
                outputType = OutputType.AUDIO,
            )
        }
        verify { telegramSender.deleteMessage(100, 500) }
    }

    @Test
    fun reportsMissingOriginalLinkAndDeletesMenu() {
        val context = context(
            text = callbackData(OutputType.VIDEO),
            menuMessage = menuMessage(requestMessage = null),
            callbackQuery = callbackQuery(userId = 300),
        )
        every {
            telegramSender.answerCallback(
                callbackQueryId = "callback-id",
                text = "Ссылка недоступна. Отправьте её ещё раз",
                showAlert = true,
            )
        } just runs
        every { telegramSender.deleteMessage(100, 500) } just runs

        handler.handle(context)

        verify(exactly = 0) { telegramDownloadStarter.start(any(), any(), any(), any(), any(), any()) }
        verify { telegramSender.deleteMessage(100, 500) }
    }

    @Test
    fun rejectsChoiceFromAnotherUserWithoutDeletingMenu() {
        val context = context(
            text = callbackData(OutputType.VIDEO),
            menuMessage = menuMessage(requestMessage()),
            callbackQuery = callbackQuery(userId = 301),
        )
        every {
            telegramSender.answerCallback(
                callbackQueryId = "callback-id",
                text = "Это меню другого пользователя",
                showAlert = true,
            )
        } just runs

        handler.handle(context)

        verify(exactly = 0) { telegramDownloadStarter.start(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { telegramSender.deleteMessage(any(), any()) }
    }

    private fun context(
        text: String,
        menuMessage: Message? = null,
        callbackQuery: CallbackQuery? = null,
    ): TelegramUpdateContext {
        return TelegramUpdateContext(
            update = mockk(),
            message = menuMessage,
            callbackQuery = callbackQuery,
            text = text,
            chatId = 100,
            messageId = 500,
        )
    }

    private fun requestMessage(): Message {
        return mockk {
            every { text() } returns URL
            every { messageId() } returns 200
            every { from() } returns user(300)
        }
    }

    private fun menuMessage(requestMessage: Message?): Message {
        return mockk {
            every { replyToMessage() } returns requestMessage
        }
    }

    private fun callbackQuery(userId: Long): CallbackQuery {
        return mockk {
            every { id() } returns "callback-id"
            every { from() } returns user(userId)
        }
    }

    private fun user(id: Long): User {
        return mockk {
            every { id() } returns id
        }
    }

    private fun callbackData(outputType: OutputType): String {
        return DownloadChoiceCallback.encode(
            telegramUpdateId = 400,
            outputType = outputType,
        )
    }

    private companion object {
        private const val URL = "https://example.com/video"
    }
}
