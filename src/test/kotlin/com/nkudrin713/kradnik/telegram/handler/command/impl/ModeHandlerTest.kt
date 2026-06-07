package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.settings.DownloadSettings
import com.nkudrin713.kradnik.settings.DownloadSettingsDto
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
        assertEquals(false, handler.supports(context("/start")))
    }

    @Test
    fun sendsModeMenu() {
        every { downloadSettingsService.getOutputType(100) } returns OutputType.VIDEO
        every { telegramSender.sendModeMenu(100, OutputType.VIDEO) } just runs

        handler.handle(context("/mode"))

        verify { telegramSender.sendModeMenu(100, OutputType.VIDEO) }
    }

    @Test
    fun changesModeAndEditsMenu() {
        every { downloadSettingsService.getOutputType(100) } returns OutputType.VIDEO
        every {
            downloadSettingsService.setMode(DownloadSettingsDto(chatId = 100, mode = OutputType.AUDIO.dbValue))
        } returns DownloadSettings(chatId = 100, mode = OutputType.AUDIO)
        every { telegramSender.answerCallback("callback-id") } just runs
        every { telegramSender.editModeMenu(100, 200, OutputType.AUDIO) } just runs

        handler.handle(context("mode:audio", callbackQuery = callbackQuery(), messageId = 200))

        verify { downloadSettingsService.setMode(DownloadSettingsDto(chatId = 100, mode = OutputType.AUDIO.dbValue)) }
        verify { telegramSender.answerCallback("callback-id") }
        verify { telegramSender.editModeMenu(100, 200, OutputType.AUDIO) }
    }

    @Test
    fun skipsEditWhenSelectedModeIsAlreadyCurrent() {
        every { downloadSettingsService.getOutputType(100) } returns OutputType.VIDEO
        every { telegramSender.answerCallback("callback-id") } just runs

        handler.handle(context("mode:video", callbackQuery = callbackQuery(), messageId = 200))

        verify { telegramSender.answerCallback("callback-id") }
        verify(exactly = 0) { downloadSettingsService.setMode(any()) }
        verify(exactly = 0) { telegramSender.editModeMenu(any(), any(), any()) }
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
