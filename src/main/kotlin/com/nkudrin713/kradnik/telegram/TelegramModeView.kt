package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.settings.DownloadMode
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import org.springframework.stereotype.Component

private const val VIDEO_CALLBACK = "mode:video"
private const val AUDIO_CALLBACK = "mode:audio"
private const val ASK_CALLBACK = "mode:ask"

@Component
class TelegramModeView {

    fun text(): String = "Режим загрузки"

    fun keyboard(mode: DownloadMode): InlineKeyboardMarkup {
        return InlineKeyboardMarkup(
            button("Видео", DownloadMode.VIDEO, mode, VIDEO_CALLBACK),
            button("Звук", DownloadMode.AUDIO, mode, AUDIO_CALLBACK),
        ).addRow(
            button("Спрашивать", DownloadMode.ASK, mode, ASK_CALLBACK),
        )
    }

    private fun button(
        text: String,
        option: DownloadMode,
        current: DownloadMode,
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
