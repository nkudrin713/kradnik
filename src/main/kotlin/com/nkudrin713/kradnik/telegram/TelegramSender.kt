package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.choice.DownloadChoiceMediaInfo
import com.nkudrin713.kradnik.download.choice.DownloadChoiceOptionSnapshot
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
) {
    fun sendMessage(chatId: Long, text: String) {
        sendText(chatId, text)
    }

    fun sendStatus(
        chatId: Long,
        status: TelegramDownloadStatus,
        replyToMessageId: Int? = null,
    ): Int {
        return sendText(chatId, status.text, replyToMessageId = replyToMessageId)
    }

    fun answerGuestMessage(guestQueryId: String, text: String): TelegramMessageAddress.Inline {
        val result = InlineQueryResultArticle("download", "Крадник", text)
        val response = apiClient.execute(AnswerGuestQuery(guestQueryId, result))
        val inlineMessageId = response.result?.inlineMessageId
            ?.takeIf(String::isNotBlank)
            ?: throw TelegramSendException("Telegram response does not contain inline message ID")
        return TelegramMessageAddress.Inline(inlineMessageId)
    }

    fun editStatus(address: TelegramMessageAddress, status: TelegramDownloadStatus) {
        editText(address, status.text)
    }

    fun editStatus(chatId: Long, messageId: Int?, status: TelegramDownloadStatus) {
        messageId ?: return
        editText(chatId, messageId, status.text)
    }

    fun editDownloadChoice(
        chatId: Long,
        messageId: Int,
        sessionToken: UUID,
        mediaInfo: DownloadChoiceMediaInfo,
        options: List<DownloadChoiceOptionSnapshot>,
    ) {
        editText(
            chatId = chatId,
            messageId = messageId,
            text = downloadChoiceView.text(mediaInfo),
            keyboard = downloadChoiceView.keyboard(sessionToken, options),
            parseMode = ParseMode.HTML,
        )
    }

    fun editDownloadChoice(
        address: TelegramMessageAddress,
        sessionToken: UUID,
        mediaInfo: DownloadChoiceMediaInfo,
        options: List<DownloadChoiceOptionSnapshot>,
    ) {
        editText(
            address = address,
            text = downloadChoiceView.text(mediaInfo),
            keyboard = downloadChoiceView.keyboard(sessionToken, options),
            parseMode = ParseMode.HTML,
        )
    }

    fun editMessage(chatId: Long, messageId: Int, text: String) {
        editText(chatId, messageId, text)
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

enum class TelegramDownloadStatus(val text: String) {
    ANALYZING("Анализирую варианты… ⏳"),
    QUEUED("В очереди ⏳"),
    DOWNLOADING("Скачиваю ⬇️"),
    UPLOADING("Загружаю в Telegram ⬆️"),
    REJECTED_TOO_LARGE("Слишком тяжелый файл 🪨 Не справлюсь"),
    AUTHENTICATION_REQUIRED("Не смогу скачать, сервис требует cookies ⛔"),
    SOURCE_UNAVAILABLE("Публикация недоступна для скачивания ⛔"),
    ERROR("Ошибка ⛔"),
}
