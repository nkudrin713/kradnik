package com.nkudrin713.kradnik.telegram.handler

import com.nkudrin713.kradnik.download.domain.PlaylistDeliveryMode
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.telegram.DownloadJobAction
import com.nkudrin713.kradnik.telegram.DownloadJobCallback
import com.nkudrin713.kradnik.telegram.TelegramDownloadStarter
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessages
import com.nkudrin713.kradnik.telegram.localization.TelegramUserPreferenceService
import com.pengrad.telegrambot.model.CallbackQuery
import org.springframework.stereotype.Component

@Component
class PlaylistRetryHandler(
    private val jobs: DownloadJobService,
    private val starter: TelegramDownloadStarter,
    private val sender: TelegramSender,
    private val preferences: TelegramUserPreferenceService,
    private val messages: TelegramMessages,
) {
    fun handle(callbackQuery: CallbackQuery, updateId: Int): Boolean {
        val callback = DownloadJobCallback.parse(callbackQuery.data().trim()) ?: return false
        val mode = when (callback.action) {
            DownloadJobAction.RETRY_AUDIO -> PlaylistDeliveryMode.AUDIO_MESSAGES
            DownloadJobAction.RETRY_ZIP -> PlaylistDeliveryMode.ZIP
            else -> return false
        }
        val userId = callbackQuery.from().id()
        val language = preferences.resolveLanguage(userId)
        val chatId = callbackQuery.maybeInaccessibleMessage()?.chat()?.id()
        val source = chatId?.let { jobs.retrySource(callback.jobId, userId, it) }
        if (source == null || (mode == PlaylistDeliveryMode.ZIP && source.playlistDeliveryMode != PlaylistDeliveryMode.ZIP)) {
            sender.answerCallback(callbackQuery.id(), messages.text(language, TelegramMessage.RETRY_UNAVAILABLE), true)
            return true
        }
        val created = starter.retryPlaylist(source, userId, updateId, mode)
        sender.answerCallback(
            callbackQuery.id(),
            if (created == null) messages.text(language, TelegramMessage.RETRY_ALREADY_STARTED) else null,
        )
        return true
    }
}
