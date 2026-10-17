package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.choice.REPEAT_LAST_OPTION_KEY
import com.nkudrin713.kradnik.localization.BotLanguage
import com.nkudrin713.kradnik.localization.MessageKey
import com.nkudrin713.kradnik.localization.Messages
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import org.springframework.stereotype.Component
import java.util.UUID

private const val JOB_CALLBACK_PREFIX = "job"

@Component
class TelegramDownloadJobView(private val messages: Messages) {
    fun retryKeyboard(jobId: Long, language: BotLanguage, count: Int, includeZip: Boolean, repeatToken: UUID? = null): InlineKeyboardMarkup {
        val rows = mutableListOf<Array<InlineKeyboardButton>>()
        if (count > 0) {
            rows += arrayOf(
                InlineKeyboardButton(messages.text(language, MessageKey.ACTION_RETRY_AUDIO, count))
                    .callbackData(DownloadJobCallback.encode(DownloadJobAction.RETRY_AUDIO, jobId)),
            )
        }
        if (includeZip && count > 0) {
            rows += arrayOf(
                InlineKeyboardButton(messages.text(language, MessageKey.ACTION_RETRY_ZIP, count))
                    .callbackData(DownloadJobCallback.encode(DownloadJobAction.RETRY_ZIP, jobId)),
            )
        }
        repeatToken?.let { rows += repeatKeyboard(it, language).inlineKeyboard().single() }
        return InlineKeyboardMarkup(*rows.toTypedArray())
    }

    fun repeatKeyboard(token: UUID, language: BotLanguage): InlineKeyboardMarkup = InlineKeyboardMarkup(
        arrayOf(
            InlineKeyboardButton(messages.text(language, MessageKey.ACTION_REPEAT_LAST))
                .callbackData(DownloadChoiceCallback.encode(token, REPEAT_LAST_OPTION_KEY)),
        ),
    )

    fun queueKeyboard(jobId: Long, language: BotLanguage): InlineKeyboardMarkup = cancelKeyboard(jobId, language)

    fun cancelKeyboard(jobId: Long, language: BotLanguage): InlineKeyboardMarkup = InlineKeyboardMarkup(
        arrayOf(
            InlineKeyboardButton(messages.text(language, MessageKey.ACTION_CANCEL))
                .callbackData(DownloadJobCallback.encode(DownloadJobAction.CANCEL, jobId))
                .style("danger"),
        ),
    )

    fun backKeyboard(jobId: Long, language: BotLanguage): InlineKeyboardMarkup = InlineKeyboardMarkup(
        arrayOf(
            InlineKeyboardButton(messages.text(language, MessageKey.ACTION_BACK_TO_OPTIONS))
                .callbackData(DownloadJobCallback.encode(DownloadJobAction.BACK, jobId))
                .style("success"),
        ),
    )
}

enum class DownloadJobAction(val value: String) {
    CANCEL("cancel"),
    BACK("back"),
    RETRY_AUDIO("retry_audio"),
    RETRY_ZIP("retry_zip"),
}

data class DownloadJobCallback(val action: DownloadJobAction, val jobId: Long) {
    companion object {
        fun encode(action: DownloadJobAction, jobId: Long): String = "$JOB_CALLBACK_PREFIX:${action.value}:$jobId"

        fun parse(value: String): DownloadJobCallback? {
            val parts = value.split(':')
            if (parts.size != 3 || parts[0] != JOB_CALLBACK_PREFIX) return null
            val action = DownloadJobAction.entries.firstOrNull { it.value == parts[1] } ?: return null
            val jobId = parts[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return DownloadJobCallback(action, jobId)
        }
    }
}
