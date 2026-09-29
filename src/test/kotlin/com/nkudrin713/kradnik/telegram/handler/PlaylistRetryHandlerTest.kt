package com.nkudrin713.kradnik.telegram.handler

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.PlaylistDeliveryMode
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.telegram.DownloadJobAction
import com.nkudrin713.kradnik.telegram.DownloadJobCallback
import com.nkudrin713.kradnik.telegram.TelegramDownloadStarter
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
import kotlin.test.assertTrue

class PlaylistRetryHandlerTest {
    private val jobs = mockk<DownloadJobService>()
    private val starter = mockk<TelegramDownloadStarter>()
    private val sender = mockk<TelegramSender>()
    private val preferences = mockk<TelegramUserPreferenceService>()
    private val handler = PlaylistRetryHandler(jobs, starter, sender, preferences, telegramMessages())

    @Test
    fun startsZipRetryForOwner() {
        val source = source()
        every { preferences.resolveLanguage(10) } returns BotLanguage.RU
        every { jobs.retrySource(1, 10, 20) } returns source
        every { starter.retryPlaylist(source, 10, 100, PlaylistDeliveryMode.ZIP) } returns source
        every { sender.answerCallback("callback", null, false) } just runs

        assertTrue(handler.handle(callback(DownloadJobAction.RETRY_ZIP), 100))

        verify { starter.retryPlaylist(source, 10, 100, PlaylistDeliveryMode.ZIP) }
    }

    @Test
    fun rejectsRetryForWrongUser() {
        every { preferences.resolveLanguage(10) } returns BotLanguage.RU
        every { jobs.retrySource(1, 10, 20) } returns null
        every { sender.answerCallback("callback", "Для этой задачи нет дорожек, которые можно повторить", true) } just runs

        assertTrue(handler.handle(callback(DownloadJobAction.RETRY_AUDIO), 100))

        verify(exactly = 0) { starter.retryPlaylist(any(), any(), any(), any()) }
    }

    private fun callback(action: DownloadJobAction): CallbackQuery = mockk {
        every { id() } returns "callback"
        every { data() } returns DownloadJobCallback.encode(action, 1)
        every { from() } returns mockk<User> { every { id() } returns 10 }
        every { maybeInaccessibleMessage() } returns mockk<MaybeInaccessibleMessage> {
            every { chat() } returns mockk<Chat> { every { id() } returns 20 }
        }
    }

    private fun source() = DownloadJob(id = 1, telegramUserId = 10, telegramChatId = 20, playlistDeliveryMode = PlaylistDeliveryMode.ZIP)
}
