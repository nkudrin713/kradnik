package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.telegram.DownloadChoiceCoordinator
import com.nkudrin713.kradnik.telegram.PrepareDownloadChoiceCommand
import com.nkudrin713.kradnik.telegram.handler.TelegramMessageContext
import com.nkudrin713.kradnik.telegram.handler.command.TelegramCommandHandler
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Component
@Order(20)
class VideoUrlHandler(
    private val downloadChoiceCoordinator: DownloadChoiceCoordinator,
) : TelegramCommandHandler {

    override fun supports(context: TelegramMessageContext): Boolean {
        return context.text.startsWith("http://") ||
                context.text.startsWith("https://")
    }

    override fun handle(context: TelegramMessageContext) {
        val message = context.message
        downloadChoiceCoordinator.prepare(
            PrepareDownloadChoiceCommand(
                telegramUserId = message.from().id(),
                telegramChatId = context.chatId,
                telegramUpdateId = context.update.updateId(),
                telegramRequestMessageId = context.messageId,
                url = context.text,
            )
        )
    }
}
