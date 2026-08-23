package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.choice.DownloadChoiceOptionSnapshot
import com.nkudrin713.kradnik.download.choice.DownloadSizeFormatter
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.UUID

private const val DOWNLOAD_CALLBACK_PREFIX = "dl"
private const val MAX_CALLBACK_BYTES = 64

@Component
class TelegramDownloadChoiceView {
    fun text(): String = "Что скачать?"

    fun keyboard(
        sessionToken: UUID,
        options: List<DownloadChoiceOptionSnapshot>,
    ): InlineKeyboardMarkup {
        val rows = options.map { option ->
            arrayOf(
                InlineKeyboardButton(buttonText(option))
                    .callbackData(DownloadChoiceCallback.encode(sessionToken, option.key))
            )
        }.toTypedArray()
        return InlineKeyboardMarkup(*rows)
    }

    private fun buttonText(option: DownloadChoiceOptionSnapshot): String {
        val size = option.sizeBytes ?: return option.label
        val prefix = if (option.approximateSize) "≈ " else ""
        val unavailable = if (option.available) "" else " · недоступно"
        return "${option.label} · $prefix${DownloadSizeFormatter.format(size)}$unavailable"
    }
}

data class DownloadChoiceCallback(
    val sessionToken: UUID,
    val optionKey: String,
) {
    companion object {
        fun encode(sessionToken: UUID, optionKey: String): String {
            val value = "$DOWNLOAD_CALLBACK_PREFIX:$sessionToken:$optionKey"
            require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_CALLBACK_BYTES) {
                "Download callback exceeds Telegram limit"
            }
            return value
        }

        fun parse(value: String): DownloadChoiceCallback? {
            val parts = value.split(':', limit = 3)
            if (parts.size != 3 || parts[0] != DOWNLOAD_CALLBACK_PREFIX || parts[2].isBlank()) {
                return null
            }
            val token = runCatching { UUID.fromString(parts[1]) }.getOrNull() ?: return null
            return DownloadChoiceCallback(
                sessionToken = token,
                optionKey = parts[2],
            )
        }
    }
}
