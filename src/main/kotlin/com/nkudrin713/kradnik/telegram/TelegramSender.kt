package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.settings.DownloadMode
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.model.request.ReplyParameters
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.DeleteMessage
import com.pengrad.telegrambot.request.EditMessageText
import com.pengrad.telegrambot.request.SendMessage
import org.springframework.stereotype.Service

@Service
class TelegramSender(
    private val apiClient: TelegramApiClient,
    private val modeView: TelegramModeView,
    private val downloadChoiceView: TelegramDownloadChoiceView,
) {
    fun sendMessage(chatId: Long, text: String) {
        sendText(chatId, text)
    }

    fun sendStatus(chatId: Long, status: TelegramDownloadStatus): Int {
        return sendText(chatId, status.text)
    }

    fun editStatus(chatId: Long, messageId: Int?, status: TelegramDownloadStatus) {
        messageId ?: return
        editText(chatId, messageId, status.text)
    }

    fun sendModeMenu(chatId: Long, mode: DownloadMode): Int {
        return sendText(chatId, modeView.text(), modeView.keyboard(mode))
    }

    fun sendDownloadChoice(
        chatId: Long,
        replyToMessageId: Int,
        telegramUpdateId: Int,
    ): Int {
        return sendText(
            chatId = chatId,
            text = downloadChoiceView.text(),
            keyboard = downloadChoiceView.keyboard(telegramUpdateId),
            replyToMessageId = replyToMessageId,
        )
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
    ) {
        val request = EditMessageText(chatId, messageId, text)
        keyboard?.let(request::replyMarkup)
        apiClient.execute(request)
    }
}

enum class TelegramDownloadStatus(val text: String) {
    QUEUED("В очереди ⏳"),
    DOWNLOADING("Скачиваю ⬇️"),
    UPLOADING("Загружаю в Telegram ⬆️"),
    REJECTED_TOO_LARGE("Слишком тяжелый файл 🪨 Не справлюсь"),
    AUTHENTICATION_REQUIRED("Не смогу скачать, сервис требует cookies ⛔"),
    SOURCE_UNAVAILABLE("Публикация недоступна для скачивания ⛔"),
    ERROR("Ошибка ⛔"),
}
