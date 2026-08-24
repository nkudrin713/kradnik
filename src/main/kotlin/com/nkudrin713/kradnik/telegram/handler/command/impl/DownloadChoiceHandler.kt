package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.download.choice.DownloadChoiceSelection
import com.nkudrin713.kradnik.download.choice.DownloadChoiceSessionService
import com.nkudrin713.kradnik.download.choice.SelectDownloadChoiceCommand
import com.nkudrin713.kradnik.telegram.DownloadChoiceCallback
import com.nkudrin713.kradnik.telegram.TelegramDownloadStarter
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.handler.TelegramCallbackContext
import com.nkudrin713.kradnik.telegram.handler.command.TelegramCallbackHandler
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Component
@Order(16)
class DownloadChoiceHandler(
    private val sessionService: DownloadChoiceSessionService,
    private val telegramDownloadStarter: TelegramDownloadStarter,
    private val telegramSender: TelegramSender,
) : TelegramCallbackHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun supports(context: TelegramCallbackContext): Boolean {
        return DownloadChoiceCallback.parse(context.text) != null
    }

    override fun handle(context: TelegramCallbackContext) {
        val callback = requireNotNull(DownloadChoiceCallback.parse(context.text))
        val callbackQuery = context.callbackQuery
        val selection = sessionService.select(
            SelectDownloadChoiceCommand(
                token = callback.sessionToken,
                optionKey = callback.optionKey,
                telegramUserId = callbackQuery.from().id(),
                telegramChatId = context.chatId,
                telegramMenuMessageId = context.messageId,
            )
        )

        when (selection) {
            is DownloadChoiceSelection.Ready -> startDownload(context, callbackQuery.id(), callback, selection)
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
        context: TelegramCallbackContext,
        callbackQueryId: String,
        callback: DownloadChoiceCallback,
        selection: DownloadChoiceSelection.Ready,
    ) {
        try {
            telegramDownloadStarter.startResolved(
                telegramUserId = selection.session.telegramUserId,
                telegramChatId = selection.session.telegramChatId,
                telegramUpdateId = selection.session.telegramUpdateId,
                telegramRequestMessageId = selection.session.telegramRequestMessageId,
                resolvedDownload = selection.option.toResolvedDownload(),
            )
        } catch (error: Exception) {
            sessionService.release(callback.sessionToken)
            throw error
        }

        answer(callbackQueryId, "Выбрано: ${selection.option.label}")
        deleteMenuBestEffort(context.chatId, context.messageId)
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
