package com.nkudrin713.kradnik.telegram.handler

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadJobStatus
import com.nkudrin713.kradnik.download.service.DownloadCancellation
import com.nkudrin713.kradnik.download.service.DownloadCancellationService
import com.nkudrin713.kradnik.telegram.DownloadChoiceCoordinator
import com.nkudrin713.kradnik.telegram.DownloadJobAction
import com.nkudrin713.kradnik.telegram.DownloadJobCallback
import com.nkudrin713.kradnik.telegram.PrepareDownloadChoiceCommand
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
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadCancellationHandlerTest {
    private val cancellations = mockk<DownloadCancellationService>()
    private val coordinator = mockk<DownloadChoiceCoordinator>()
    private val sender = mockk<TelegramSender>()
    private val preferences = mockk<TelegramUserPreferenceService>()
    private val handler = DownloadCancellationHandler(
        cancellations,
        coordinator,
        sender,
        preferences,
        telegramMessages(),
    )

    @Test
    fun cancelsJobAndShowsBackButton() {
        val job = job()
        every { preferences.resolveLanguage(10) } returns BotLanguage.RU
        every {
            cancellations.cancel(1, 10, TelegramMessageAddress.Chat(20, 30))
        } returns DownloadCancellation.Cancelled(job)
        every { sender.answerCallback("callback", null, false) } just runs
        every { sender.editCancelledJob(TelegramMessageAddress.Chat(20, 30), 1, BotLanguage.RU) } just runs

        assertTrue(handler.handle(callback(DownloadJobAction.CANCEL), 100))

        verify { sender.editCancelledJob(TelegramMessageAddress.Chat(20, 30), 1, BotLanguage.RU) }
    }

    @Test
    fun preparesOriginalUrlAgainFromCancelledJob() {
        val job = job().apply { status = DownloadJobStatus.CANCELLED_BY_USER }
        every { preferences.resolveLanguage(10) } returns BotLanguage.RU
        every {
            cancellations.cancelledJob(1, 10, TelegramMessageAddress.Chat(20, 30))
        } returns job
        every { sender.answerCallback("callback", null, false) } just runs
        every { coordinator.prepareAgain(any(), TelegramMessageAddress.Chat(20, 30)) } just runs

        assertTrue(handler.handle(callback(DownloadJobAction.BACK), 100))

        verify {
            coordinator.prepareAgain(
                PrepareDownloadChoiceCommand(
                    telegramUserId = 10,
                    telegramChatId = 20,
                    telegramUpdateId = 100,
                    telegramRequestMessageId = 40,
                    url = "https://youtu.be/video",
                    language = BotLanguage.RU,
                ),
                TelegramMessageAddress.Chat(20, 30),
            )
        }
    }

    @Test
    fun ignoresUnrelatedCallback() {
        val query = callback(DownloadJobAction.CANCEL, "other:value")

        assertFalse(handler.handle(query, 100))
    }

    private fun callback(action: DownloadJobAction, data: String = DownloadJobCallback.encode(action, 1)): CallbackQuery {
        val message = mockk<MaybeInaccessibleMessage> {
            every { chat() } returns mockk<Chat> { every { id() } returns 20 }
            every { messageId() } returns 30
        }
        return mockk {
            every { id() } returns "callback"
            every { this@mockk.data() } returns data
            every { from() } returns mockk<User> { every { id() } returns 10 }
            every { inlineMessageId() } returns null
            every { maybeInaccessibleMessage() } returns message
        }
    }

    private fun job() = DownloadJob(
        id = 1,
        telegramUserId = 10,
        telegramChatId = 20,
        telegramStatusMessageId = 30,
        telegramRequestMessageId = 40,
        originalUrl = "https://youtu.be/video",
        language = BotLanguage.RU,
    )
}
