package com.nkudrin713.kradnik.telegram.handler

import com.nkudrin713.kradnik.download.choice.DownloadChoiceSelection
import com.nkudrin713.kradnik.download.choice.DownloadChoiceSessionService
import com.nkudrin713.kradnik.download.choice.SelectDownloadChoiceCommand
import com.nkudrin713.kradnik.telegram.DownloadChoiceCallback
import com.nkudrin713.kradnik.telegram.TelegramDownloadStarter
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.pengrad.telegrambot.model.CallbackQuery
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Consumes a persisted menu selection and releases it if job creation fails. */
@Component
class DownloadChoiceHandler(
    private val sessionService: DownloadChoiceSessionService,
    private val telegramDownloadStarter: TelegramDownloadStarter,
    private val telegramSender: TelegramSender,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun handle(callbackQuery: CallbackQuery) {
        val callback = DownloadChoiceCallback.parse(callbackQuery.data().trim()) ?: return
        val message = callbackQuery.maybeInaccessibleMessage()
        val chatId = message.chat().id()
        val messageId = message.messageId()
        val selection = sessionService.select(
            SelectDownloadChoiceCommand(
                token = callback.sessionToken,
                optionKey = callback.optionKey,
                telegramUserId = callbackQuery.from().id(),
                telegramChatId = chatId,
                telegramMenuMessageId = messageId,
            )
        )

        when (selection) {
            is DownloadChoiceSelection.Ready -> startDownload(
                chatId = chatId,
                messageId = messageId,
                callbackQueryId = callbackQuery.id(),
                callback = callback,
                selection = selection,
            )
            is DownloadChoiceSelection.Unavailable -> answer(callbackQuery.id(), selection.reason, showAlert = true)
            DownloadChoiceSelection.Expired -> answer(
                callbackQuery.id(),
                "Меню устарело. Отправьте ссылку ещё раз",
                showAlert = true,
            )
            DownloadChoiceSelection.NotOwner -> answer(
                callbackQuery.id(),
                "Это меню другого пользователя",
                showAlert = true,
            )
            DownloadChoiceSelection.AlreadySelected -> answer(callbackQuery.id(), "Загрузка уже выбрана")
            DownloadChoiceSelection.Invalid -> answer(callbackQuery.id(), "Меню недействительно", showAlert = true)
        }
    }

    private fun startDownload(
        chatId: Long,
        messageId: Int,
        callbackQueryId: String,
        callback: DownloadChoiceCallback,
        selection: DownloadChoiceSelection.Ready,
    ) {
        try {
            telegramDownloadStarter.start(
                telegramUserId = selection.session.telegramUserId,
                telegramChatId = selection.session.telegramChatId,
                telegramUpdateId = selection.session.telegramUpdateId,
                telegramRequestMessageId = selection.session.telegramRequestMessageId,
                spec = selection.option.spec,
            )
        } catch (error: Exception) {
            sessionService.release(callback.sessionToken)
            throw error
        }

        answer(callbackQueryId, "Выбрано: ${selection.option.label}")
        deleteMenuBestEffort(chatId, messageId)
    }

    private fun answer(callbackQueryId: String, text: String, showAlert: Boolean = false) {
        telegramSender.answerCallback(callbackQueryId, text, showAlert)
    }

    private fun deleteMenuBestEffort(chatId: Long, messageId: Int) {
        runCatching {
            telegramSender.deleteMessage(chatId, messageId)
        }.onFailure {
            logger.warn(
                "Download choice menu deletion failed: chatId={}, messageId={}",
                chatId,
                messageId,
                it,
            )
        }
    }
}
