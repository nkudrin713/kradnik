package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.telegram.DownloadChoiceCallback
import com.nkudrin713.kradnik.telegram.TelegramDownloadStarter
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.handler.TelegramUpdateContext
import com.nkudrin713.kradnik.telegram.handler.command.TelegramCommandHandler
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Component
@Order(16)
class DownloadChoiceHandler(
    private val telegramDownloadStarter: TelegramDownloadStarter,
    private val telegramSender: TelegramSender,
) : TelegramCommandHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun supports(context: TelegramUpdateContext): Boolean {
        return DownloadChoiceCallback.parse(context.text) != null
    }

    override fun handle(context: TelegramUpdateContext) {
        val choice = requireNotNull(DownloadChoiceCallback.parse(context.text))
        val callbackQuery = requireNotNull(context.callbackQuery)
        val menuMessageId = requireNotNull(context.messageId)
        val requestMessage = context.message?.replyToMessage()
        val requestUrl = requestMessage?.text()?.trim()
        val requestUser = requestMessage?.from()

        if (requestUrl.isNullOrEmpty() || requestUser == null) {
            telegramSender.answerCallback(
                callbackQueryId = callbackQuery.id(),
                text = "Ссылка недоступна. Отправьте её ещё раз",
                showAlert = true,
            )
            deleteMenuBestEffort(context.chatId, menuMessageId)
            return
        }

        if (callbackQuery.from().id() != requestUser.id()) {
            telegramSender.answerCallback(
                callbackQueryId = callbackQuery.id(),
                text = "Это меню другого пользователя",
                showAlert = true,
            )
            return
        }

        telegramSender.answerCallback(
            callbackQueryId = callbackQuery.id(),
            text = "Выбрано: ${choice.outputType.displayName()}",
        )
        telegramDownloadStarter.start(
            telegramUserId = requestUser.id(),
            telegramChatId = context.chatId,
            telegramUpdateId = choice.telegramUpdateId,
            telegramRequestMessageId = requestMessage.messageId(),
            url = requestUrl,
            outputType = choice.outputType,
        )
        deleteMenuBestEffort(context.chatId, menuMessageId)
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

    private fun OutputType.displayName(): String {
        return when (this) {
            OutputType.VIDEO -> "Видео"
            OutputType.AUDIO -> "Звук"
        }
    }
}
