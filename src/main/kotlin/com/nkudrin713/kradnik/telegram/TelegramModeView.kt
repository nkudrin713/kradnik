package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.domain.OutputType
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import org.springframework.stereotype.Component

private const val VIDEO_CALLBACK = "mode:video"
private const val AUDIO_CALLBACK = "mode:audio"

@Component
class TelegramModeView {

    fun text(): String = "Режим загрузки"

    fun keyboard(outputType: OutputType): InlineKeyboardMarkup {
        return InlineKeyboardMarkup(
            button("Видео", OutputType.VIDEO, outputType, VIDEO_CALLBACK),
            button("Аудио", OutputType.AUDIO, outputType, AUDIO_CALLBACK),
        )
    }

    private fun button(
        text: String,
        option: OutputType,
        current: OutputType,
        callbackData: String,
    ): InlineKeyboardButton {
        val label = if (option == current) {
            "✅ $text"
        } else {
            text
        }

        return InlineKeyboardButton(label).callbackData(callbackData)
    }
}
