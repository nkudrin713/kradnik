package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.settings.DownloadSettingsDto
import com.nkudrin713.kradnik.settings.DownloadSettingsService
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.handler.TelegramUpdateContext
import com.nkudrin713.kradnik.telegram.handler.command.TelegramCommandHandler
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private const val MODE_COMMAND = "/mode"
private const val VIDEO_CALLBACK = "mode:video"
private const val AUDIO_CALLBACK = "mode:audio"

@Component
@Order(15)
class ModeHandler(
    private val downloadSettingsService: DownloadSettingsService,
    private val telegramSender: TelegramSender,
) : TelegramCommandHandler {

    override fun supports(context: TelegramUpdateContext): Boolean {
        return context.text == MODE_COMMAND ||
                context.text == VIDEO_CALLBACK ||
                context.text == AUDIO_CALLBACK
    }

    override fun handle(context: TelegramUpdateContext) {
        val selected = when (context.text) {
            VIDEO_CALLBACK -> OutputType.VIDEO
            AUDIO_CALLBACK -> OutputType.AUDIO
            else -> null
        }

        if (selected != null) {
            downloadSettingsService.setMode(
                DownloadSettingsDto(
                    chatId = context.chatId,
                    mode = selected.dbValue,
                )
            )
        }

        val current = selected ?: downloadSettingsService.getOutputType(context.chatId)
        val callbackQuery = context.callbackQuery

        if (callbackQuery != null) {
            telegramSender.answerCallback(callbackQuery.id())
            telegramSender.editModeMenu(
                context.chatId,
                requireNotNull(context.messageId),
                current,
            )
        } else {
            telegramSender.sendModeMenu(context.chatId, current)
        }
    }
}
