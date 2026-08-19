package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.domain.OutputType
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import org.springframework.stereotype.Component

private const val DOWNLOAD_CALLBACK_PREFIX = "download"

@Component
class TelegramDownloadChoiceView {

    fun text(): String = "Что скачать?"

    fun keyboard(telegramUpdateId: Int): InlineKeyboardMarkup {
        return InlineKeyboardMarkup(
            button("Видео", telegramUpdateId, OutputType.VIDEO),
            button("Звук", telegramUpdateId, OutputType.AUDIO),
        )
    }

    private fun button(
        text: String,
        telegramUpdateId: Int,
        outputType: OutputType,
    ): InlineKeyboardButton {
        return InlineKeyboardButton(text)
            .callbackData(DownloadChoiceCallback.encode(telegramUpdateId, outputType))
    }
}

data class DownloadChoiceCallback(
    val telegramUpdateId: Int,
    val outputType: OutputType,
) {
    companion object {
        fun encode(
            telegramUpdateId: Int,
            outputType: OutputType,
        ): String = "$DOWNLOAD_CALLBACK_PREFIX:$telegramUpdateId:${outputType.dbValue}"

        fun parse(value: String): DownloadChoiceCallback? {
            val parts = value.split(':')
            if (parts.size != 3 || parts[0] != DOWNLOAD_CALLBACK_PREFIX) {
                return null
            }

            val telegramUpdateId = parts[1].toIntOrNull() ?: return null
            val outputType = when (parts[2]) {
                OutputType.VIDEO.dbValue -> OutputType.VIDEO
                OutputType.AUDIO.dbValue -> OutputType.AUDIO
                else -> return null
            }

            return DownloadChoiceCallback(
                telegramUpdateId = telegramUpdateId,
                outputType = outputType,
            )
        }
    }
}
