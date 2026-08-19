package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.settings.DownloadMode
import com.nkudrin713.kradnik.settings.DownloadSettingsService
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.handler.TelegramUpdateContext
import com.pengrad.telegrambot.model.CallbackQuery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals

class ModeHandlerTest {
    private val downloadSettingsService: DownloadSettingsService = mockk()
    private val telegramSender: TelegramSender = mockk()
    private val handler = ModeHandler(
        downloadSettingsService = downloadSettingsService,
        telegramSender = telegramSender,
    )

    @Test
    fun supportsModeCommandAndCallbacks() {
        assertEquals(true, handler.supports(context("/mode")))
        assertEquals(true, handler.supports(context("mode:video")))
        assertEquals(true, handler.supports(context("mode:audio")))
        assertEquals(true, handler.supports(context("mode:ask")))
        assertEquals(false, handler.supports(context("/start")))
    }

    @Test
    fun replacesPreviousMenuAndDeletesCommand() {
        every { downloadSettingsService.getMode(100) } returns DownloadMode.ASK
        every { telegramSender.sendModeMenu(100, DownloadMode.ASK) } returns 300
        every {
            downloadSettingsService.replaceModeMenu(
                chatId = 100,
                messageId = 300,
            )
        } returns 250
        every { telegramSender.deleteMessage(any(), any()) } just runs

        handler.handle(context("/mode", messageId = 200))

        verify { telegramSender.deleteMessage(100, 250) }
        verify { telegramSender.deleteMessage(100, 200) }
    }

    @Test
    fun selectsModeShowsToastAndDeletesMenu() {
        every {
            downloadSettingsService.selectMode(
                chatId = 100,
                menuMessageId = 200,
                mode = DownloadMode.AUDIO,
            )
        } returns true
        every {
            telegramSender.answerCallback(
                callbackQueryId = "callback-id",
                text = "Режим: Звук",
            )
        } just runs
        every { telegramSender.deleteMessage(100, 200) } just runs

        handler.handle(
            context(
                text = "mode:audio",
                callbackQuery = callbackQuery(),
                messageId = 200,
            )
        )

        verify {
            telegramSender.answerCallback(
                callbackQueryId = "callback-id",
                text = "Режим: Звук",
            )
        }
        verify { telegramSender.deleteMessage(100, 200) }
    }

    @Test
    fun ignoresStaleMenuAndDeletesIt() {
        every {
            downloadSettingsService.selectMode(
                chatId = 100,
                menuMessageId = 200,
                mode = DownloadMode.VIDEO,
            )
        } returns false
        every {
            telegramSender.answerCallback(
                callbackQueryId = "callback-id",
                text = "Меню устарело",
            )
        } just runs
        every { telegramSender.deleteMessage(100, 200) } just runs

        handler.handle(
            context(
                text = "mode:video",
                callbackQuery = callbackQuery(),
                messageId = 200,
            )
        )

        verify(exactly = 0) { downloadSettingsService.replaceModeMenu(any(), any()) }
        verify { telegramSender.deleteMessage(100, 200) }
    }

    private fun context(
        text: String,
        callbackQuery: CallbackQuery? = null,
        messageId: Int? = null,
    ): TelegramUpdateContext {
        return TelegramUpdateContext(
            update = mockk(),
            message = null,
            callbackQuery = callbackQuery,
            text = text,
            chatId = 100,
            messageId = messageId,
        )
    }

    private fun callbackQuery(): CallbackQuery {
        return mockk {
            every { id() } returns "callback-id"
        }
    }
}
