package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.settings.DownloadMode
import com.nkudrin713.kradnik.settings.DownloadSettingsService
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.handler.TelegramUpdateContext
import com.nkudrin713.kradnik.telegram.handler.command.TelegramCommandHandler
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private const val MODE_COMMAND = "/mode"
private const val VIDEO_CALLBACK = "mode:video"
private const val AUDIO_CALLBACK = "mode:audio"
private const val ASK_CALLBACK = "mode:ask"

@Component
@Order(15)
class ModeHandler(
    private val downloadSettingsService: DownloadSettingsService,
    private val telegramSender: TelegramSender,
) : TelegramCommandHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun supports(context: TelegramUpdateContext): Boolean {
        return context.text == MODE_COMMAND ||
                context.text == VIDEO_CALLBACK ||
                context.text == AUDIO_CALLBACK ||
                context.text == ASK_CALLBACK
    }

    override fun handle(context: TelegramUpdateContext) {
        val selectedMode = selectedMode(context.text)
        if (selectedMode == null) {
            showMenu(context)
        } else {
            selectMode(context, selectedMode)
        }
    }

    private fun showMenu(context: TelegramUpdateContext) {
        val currentMode = downloadSettingsService.getMode(context.chatId)
        val menuMessageId = telegramSender.sendModeMenu(context.chatId, currentMode)
        val previousMenuMessageId = downloadSettingsService.replaceModeMenu(
            chatId = context.chatId,
            messageId = menuMessageId,
        )

        if (previousMenuMessageId != null && previousMenuMessageId != menuMessageId) {
            deleteMessageBestEffort(
                chatId = context.chatId,
                messageId = previousMenuMessageId,
                description = "previous mode menu",
            )
        }
        context.messageId?.let {
            deleteMessageBestEffort(
                chatId = context.chatId,
                messageId = it,
                description = "mode command",
            )
        }
    }

    private fun selectMode(
        context: TelegramUpdateContext,
        selectedMode: DownloadMode,
    ) {
        val callbackQuery = requireNotNull(context.callbackQuery)
        val menuMessageId = requireNotNull(context.messageId)
        val selected = downloadSettingsService.selectMode(
            chatId = context.chatId,
            menuMessageId = menuMessageId,
            mode = selectedMode,
        )
        telegramSender.answerCallback(
            callbackQueryId = callbackQuery.id(),
            text = if (selected) {
                "Режим: ${selectedMode.displayName}"
            } else {
                "Меню устарело"
            },
        )
        deleteMessageBestEffort(
            chatId = context.chatId,
            messageId = menuMessageId,
            description = "mode menu",
        )
    }

    private fun selectedMode(callback: String): DownloadMode? {
        return when (callback) {
            VIDEO_CALLBACK -> DownloadMode.VIDEO
            AUDIO_CALLBACK -> DownloadMode.AUDIO
            ASK_CALLBACK -> DownloadMode.ASK
            else -> null
        }
    }

    private fun deleteMessageBestEffort(
        chatId: Long,
        messageId: Int,
        description: String,
    ) {
        runCatching {
            telegramSender.deleteMessage(chatId, messageId)
        }.onFailure {
            logger.warn(
                "Telegram {} deletion failed: chatId={}, messageId={}",
                description,
                chatId,
                messageId,
                it,
            )
        }
    }
}
