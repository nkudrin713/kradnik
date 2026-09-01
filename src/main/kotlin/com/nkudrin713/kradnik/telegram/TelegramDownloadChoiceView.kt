package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.choice.DownloadChoiceOptionSnapshot
import com.nkudrin713.kradnik.download.choice.DownloadChoiceMediaInfo
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessages
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.UUID

private const val DOWNLOAD_CALLBACK_PREFIX = "dl"
private const val MAX_CALLBACK_BYTES = 64

@Component
class TelegramDownloadChoiceView(
    private val messages: TelegramMessages,
) {
    fun text(mediaInfo: DownloadChoiceMediaInfo, language: BotLanguage = BotLanguage.EN): String {
        val videoInfo = buildList {
            add(
                mediaInfo.title?.takeIf { it.isNotBlank() }
                    ?: messages.text(language, TelegramMessage.CHOICE_TITLE_UNAVAILABLE)
            )
            mediaInfo.durationSeconds?.let {
                add(messages.text(language, TelegramMessage.CHOICE_DURATION, formatDuration(it)))
            }
        }
        return "<pre>${videoInfo.joinToString("\n").escapeHtml()}</pre>"
    }

    fun keyboard(
        sessionToken: UUID,
        options: List<DownloadChoiceOptionSnapshot>,
        language: BotLanguage = BotLanguage.EN,
    ): InlineKeyboardMarkup {
        val rows = options.map { option ->
            arrayOf(
                InlineKeyboardButton(buttonText(option, language))
                    .callbackData(DownloadChoiceCallback.encode(sessionToken, option.key))
            )
        }.toTypedArray()
        return InlineKeyboardMarkup(*rows)
    }

    private fun buttonText(option: DownloadChoiceOptionSnapshot, language: BotLanguage): String {
        val label = "${option.spec.outputType.icon} ${option.label}"
        val size = option.sizeBytes ?: return label
        val prefix = if (option.approximateSize) "≈ " else ""
        val unavailable = if (option.available) {
            ""
        } else {
            " · ${messages.text(language, TelegramMessage.CHOICE_UNAVAILABLE)}"
        }
        return "$label · $prefix${formatSize(size, language)}$unavailable"
    }

    private fun formatSize(bytes: Long, language: BotLanguage): String {
        val gigabytes = bytes >= BYTES_IN_GIGABYTE
        val value = if (gigabytes) bytes / BYTES_IN_GIGABYTE else bytes / BYTES_IN_MEGABYTE
        val pattern = if (value >= 100) "%.0f" else if (value >= 10) "%.1f" else "%.2f"
        val unit = messages.text(
            language,
            if (gigabytes) TelegramMessage.CHOICE_SIZE_GB else TelegramMessage.CHOICE_SIZE_MB,
        )
        return "$pattern $unit".format(language.locale, value)
    }

    private fun formatDuration(totalSeconds: Long): String {
        val hours = totalSeconds / SECONDS_IN_HOUR
        val minutes = totalSeconds % SECONDS_IN_HOUR / SECONDS_IN_MINUTE
        val seconds = totalSeconds % SECONDS_IN_MINUTE
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    private fun String.escapeHtml(): String {
        return replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private companion object {
        private const val SECONDS_IN_MINUTE = 60L
        private const val SECONDS_IN_HOUR = 60L * SECONDS_IN_MINUTE
        private const val BYTES_IN_MEGABYTE = 1_000_000.0
        private const val BYTES_IN_GIGABYTE = 1_000_000_000.0
        private val OutputType.icon: String
            get() = when (this) {
                OutputType.VIDEO -> "🎬"
                OutputType.AUDIO -> "🎧"
                OutputType.COVER -> "🖼"
            }
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
