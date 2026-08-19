package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.settings.DownloadSettingsService
import com.nkudrin713.kradnik.telegram.TelegramDownloadStarter
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.handler.TelegramUpdateContext
import com.nkudrin713.kradnik.telegram.handler.command.TelegramCommandHandler
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Component
@Order(20)
class VideoUrlHandler(
    private val downloadSettingsService: DownloadSettingsService,
    private val telegramDownloadStarter: TelegramDownloadStarter,
    private val telegramSender: TelegramSender,
) : TelegramCommandHandler {

    override fun supports(context: TelegramUpdateContext): Boolean {
        return context.text.startsWith("http://") ||
                context.text.startsWith("https://")
    }

    override fun handle(context: TelegramUpdateContext) {
        val message = requireNotNull(context.message)
        val outputType = downloadSettingsService.getMode(context.chatId).outputType
        if (outputType == null) {
            if (telegramDownloadStarter.validate(context.chatId, context.text)) {
                telegramSender.sendDownloadChoice(
                    chatId = context.chatId,
                    replyToMessageId = requireNotNull(context.messageId),
                    telegramUpdateId = context.update.updateId(),
                )
            }
            return
        }

        telegramDownloadStarter.start(
            telegramUserId = message.from().id(),
            telegramChatId = context.chatId,
            telegramUpdateId = context.update.updateId(),
            telegramRequestMessageId = requireNotNull(context.messageId),
            url = context.text,
            outputType = outputType,
        )
    }
}
