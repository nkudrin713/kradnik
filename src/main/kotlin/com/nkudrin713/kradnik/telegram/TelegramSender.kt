package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.choice.DownloadChoiceMediaInfo
import com.nkudrin713.kradnik.download.choice.DownloadChoiceOptionSnapshot
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessages
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.model.request.InlineQueryResultArticle
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.model.request.ReplyParameters
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.AnswerGuestQuery
import com.pengrad.telegrambot.request.DeleteMessage
import com.pengrad.telegrambot.request.EditMessageText
import com.pengrad.telegrambot.request.SendMessage
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class TelegramSender(
    private val apiClient: TelegramApiClient,
    private val downloadChoiceView: TelegramDownloadChoiceView,
    private val messages: TelegramMessages,
) {
    fun sendMessage(
        chatId: Long,
        text: String,
        keyboard: InlineKeyboardMarkup? = null,
    ) {
        sendText(chatId, text, keyboard)
    }

    fun sendStatus(
        chatId: Long,
        status: TelegramDownloadStatus,
        language: BotLanguage = BotLanguage.EN,
        replyToMessageId: Int? = null,
    ): Int {
        return sendText(
            chatId = chatId,
            text = messages.text(language, status.message),
            replyToMessageId = replyToMessageId,
        )
    }

    fun answerGuestMessage(
        guestQueryId: String,
        text: String,
        language: BotLanguage = BotLanguage.EN,
    ): TelegramMessageAddress.Inline {
        val result = InlineQueryResultArticle(
            "download",
            messages.text(language, TelegramMessage.BOT_NAME),
            text,
        )
        val response = apiClient.execute(AnswerGuestQuery(guestQueryId, result))
        val inlineMessageId = response.result?.inlineMessageId
            ?.takeIf(String::isNotBlank)
            ?: throw TelegramSendException("Telegram response does not contain inline message ID")
        return TelegramMessageAddress.Inline(inlineMessageId)
    }

    fun editStatus(
        address: TelegramMessageAddress,
        status: TelegramDownloadStatus,
        language: BotLanguage = BotLanguage.EN,
    ) {
        editText(address, messages.text(language, status.message))
    }

    fun editStatus(
        chatId: Long,
        messageId: Int?,
        status: TelegramDownloadStatus,
        language: BotLanguage = BotLanguage.EN,
    ) {
        messageId ?: return
        editText(chatId, messageId, messages.text(language, status.message))
    }

    fun editDownloadChoice(
        chatId: Long,
        messageId: Int,
        sessionToken: UUID,
        mediaInfo: DownloadChoiceMediaInfo,
        options: List<DownloadChoiceOptionSnapshot>,
        language: BotLanguage = BotLanguage.EN,
    ) {
        editText(
            chatId = chatId,
            messageId = messageId,
            text = downloadChoiceView.text(mediaInfo, language),
            keyboard = downloadChoiceView.keyboard(sessionToken, options, language),
            parseMode = ParseMode.HTML,
        )
    }

    fun editDownloadChoice(
        address: TelegramMessageAddress,
        sessionToken: UUID,
        mediaInfo: DownloadChoiceMediaInfo,
        options: List<DownloadChoiceOptionSnapshot>,
        language: BotLanguage = BotLanguage.EN,
    ) {
        editText(
            address = address,
            text = downloadChoiceView.text(mediaInfo, language),
            keyboard = downloadChoiceView.keyboard(sessionToken, options, language),
            parseMode = ParseMode.HTML,
        )
    }

    fun editMessage(
        chatId: Long,
        messageId: Int,
        text: String,
        keyboard: InlineKeyboardMarkup? = null,
    ) {
        editText(chatId, messageId, text, keyboard)
    }

    fun editMessage(address: TelegramMessageAddress, text: String) {
        editText(address, text)
    }

    fun answerCallback(
        callbackQueryId: String,
        text: String? = null,
        showAlert: Boolean = false,
    ) {
        val request = AnswerCallbackQuery(callbackQueryId)
        text?.let(request::text)
        if (showAlert) {
            request.showAlert(true)
        }
        apiClient.execute(request)
    }

    fun deleteMessage(chatId: Long, messageId: Int) {
        apiClient.execute(DeleteMessage(chatId, messageId))
    }

    private fun sendText(
        chatId: Long,
        text: String,
        keyboard: InlineKeyboardMarkup? = null,
        replyToMessageId: Int? = null,
    ): Int {
        val request = SendMessage(chatId, text)
        keyboard?.let(request::replyMarkup)
        replyToMessageId?.let { request.replyParameters(ReplyParameters(it)) }
        val response = apiClient.execute(request)
        return response.message()?.messageId()
            ?: throw TelegramSendException("Telegram response does not contain message")
    }

    private fun editText(
        chatId: Long,
        messageId: Int,
        text: String,
        keyboard: InlineKeyboardMarkup? = null,
        parseMode: ParseMode? = null,
    ) {
        val request = EditMessageText(chatId, messageId, text)
        keyboard?.let(request::replyMarkup)
        parseMode?.let(request::parseMode)
        apiClient.execute(request)
    }

    private fun editText(
        address: TelegramMessageAddress,
        text: String,
        keyboard: InlineKeyboardMarkup? = null,
        parseMode: ParseMode? = null,
    ) {
        val request = when (address) {
            is TelegramMessageAddress.Chat -> EditMessageText(address.chatId, address.messageId, text)
            is TelegramMessageAddress.Inline -> EditMessageText(address.inlineMessageId, text)
        }
        keyboard?.let(request::replyMarkup)
        parseMode?.let(request::parseMode)
        apiClient.execute(request)
    }
}

sealed interface TelegramMessageAddress {
    data class Chat(val chatId: Long, val messageId: Int) : TelegramMessageAddress

    data class Inline(val inlineMessageId: String) : TelegramMessageAddress
}

enum class TelegramDownloadStatus(val message: TelegramMessage) {
    ANALYZING(TelegramMessage.STATUS_ANALYZING),
    QUEUED(TelegramMessage.STATUS_QUEUED),
    DOWNLOADING(TelegramMessage.STATUS_DOWNLOADING),
    UPLOADING(TelegramMessage.STATUS_UPLOADING),
    REJECTED_TOO_LARGE(TelegramMessage.STATUS_REJECTED_TOO_LARGE),
    AUTHENTICATION_REQUIRED(TelegramMessage.STATUS_AUTHENTICATION_REQUIRED),
    SOURCE_UNAVAILABLE(TelegramMessage.STATUS_SOURCE_UNAVAILABLE),
    ERROR(TelegramMessage.STATUS_ERROR),
}
