package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.domain.OutputType
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.DeleteMessage
import com.pengrad.telegrambot.request.EditMessageText
import com.pengrad.telegrambot.request.SendMessage
import org.springframework.stereotype.Service

@Service
class TelegramSender(
    private val apiClient: TelegramApiClient,
    private val modeView: TelegramModeView,
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

    fun sendModeMenu(chatId: Long, outputType: OutputType) {
        sendText(chatId, modeView.text(), modeView.keyboard(outputType))
    }

    fun editModeMenu(chatId: Long, messageId: Int, outputType: OutputType) {
        editText(chatId, messageId, modeView.text(), modeView.keyboard(outputType))
    }

    fun answerCallback(callbackQueryId: String) {
        apiClient.execute(AnswerCallbackQuery(callbackQueryId))
    }

    fun deleteMessage(chatId: Long, messageId: Int) {
        apiClient.execute(DeleteMessage(chatId, messageId))
    }

    private fun sendText(
        chatId: Long,
        text: String,
        keyboard: InlineKeyboardMarkup? = null,
    ): Int {
        val request = SendMessage(chatId, text)
        keyboard?.let(request::replyMarkup)
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
    ERROR("Ошибка ⛔"),
}
