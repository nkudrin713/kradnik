package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.choice.DownloadChoiceMediaInfo
import com.nkudrin713.kradnik.download.choice.DownloadChoiceOptionSnapshot
import com.nkudrin713.kradnik.download.choice.failureMessage
import com.nkudrin713.kradnik.download.delivery.MessageAddress
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.localization.BotLanguage
import com.nkudrin713.kradnik.localization.MessageKey
import com.nkudrin713.kradnik.localization.Messages
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
    private val downloadJobView: TelegramDownloadJobView,
    private val messages: Messages,
) {
    private val jobStatusLocks = Array(64) { Any() }

    fun editPlaylistProgress(address: MessageAddress, text: String, jobId: Long, language: BotLanguage, isProcessing: () -> Boolean) {
        synchronized(jobStatusLocks[Math.floorMod(jobId, jobStatusLocks.size.toLong()).toInt()]) {
            if (isProcessing()) editText(address, text, downloadJobView.cancelKeyboard(jobId, language))
        }
    }

    fun sendHtmlMessage(chatId: Long, html: String) {
        apiClient.execute(SendMessage(chatId, html).parseMode(ParseMode.HTML))
    }

    fun sendPlaylistReport(chatId: Long, html: String, jobId: Long, language: BotLanguage, retryCount: Int, includeZip: Boolean) {
        apiClient.execute(
            SendMessage(chatId, html)
                .parseMode(ParseMode.HTML)
                .replyMarkup(downloadJobView.retryKeyboard(jobId, language, retryCount, includeZip)),
        )
    }

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
    ): MessageAddress.Inline {
        val result = InlineQueryResultArticle(
            "download",
            messages.text(language, MessageKey.BOT_NAME),
            text,
        )
        val response = apiClient.execute(AnswerGuestQuery(guestQueryId, result))
        val inlineMessageId = response.result?.inlineMessageId
            ?.takeIf(String::isNotBlank)
            ?: throw TelegramSendException("Telegram response does not contain inline message ID")
        return MessageAddress.Inline(inlineMessageId)
    }

    fun editStatus(
        address: MessageAddress,
        status: TelegramDownloadStatus,
        language: BotLanguage = BotLanguage.EN,
    ) {
        editText(address, messages.text(language, status.message), EMPTY_KEYBOARD)
    }

    fun editStatus(
        chatId: Long,
        messageId: Int?,
        status: TelegramDownloadStatus,
        language: BotLanguage = BotLanguage.EN,
    ) {
        messageId ?: return
        editText(chatId, messageId, messages.text(language, status.message), EMPTY_KEYBOARD)
    }

    fun editJobStatus(
        address: MessageAddress,
        status: TelegramDownloadStatus,
        jobId: Long,
        language: BotLanguage = BotLanguage.EN,
    ) {
        synchronized(jobStatusLocks[Math.floorMod(jobId, jobStatusLocks.size.toLong()).toInt()]) {
            editText(address, messages.text(language, status.message), downloadJobView.cancelKeyboard(jobId, language))
        }
    }

    fun editFailure(address: MessageAddress, text: String, retryToken: UUID?, language: BotLanguage, jobId: Long? = null) {
        val publish = { editText(address, text, retryToken?.let { downloadJobView.repeatKeyboard(it, language) } ?: EMPTY_KEYBOARD) }
        if (jobId == null) {
            publish()
        } else {
            synchronized(jobStatusLocks[Math.floorMod(jobId, jobStatusLocks.size.toLong()).toInt()]) { publish() }
        }
    }

    fun editQueuedJob(
        address: MessageAddress,
        jobId: Long,
        language: BotLanguage,
        workloadType: DownloadWorkloadType,
        position: Long,
        isQueued: () -> Boolean,
    ) {
        synchronized(jobStatusLocks[Math.floorMod(jobId, jobStatusLocks.size.toLong()).toInt()]) {
            if (!isQueued()) return
            val positionMessage = when (workloadType) {
                DownloadWorkloadType.SINGLE -> MessageKey.STATUS_QUEUE_POSITION_SINGLE
                DownloadWorkloadType.PLAYLIST_AUDIO -> MessageKey.STATUS_QUEUE_POSITION_PLAYLIST
            }
            val text = messages.text(language, positionMessage, position)
            try {
                editText(address, text, downloadJobView.queueKeyboard(jobId, language))
            } catch (error: TelegramSendException) {
                if (error.kind != TelegramSendFailureKind.MESSAGE_NOT_MODIFIED) throw error
            }
        }
    }

    fun editCancelledJob(
        address: MessageAddress,
        jobId: Long,
        language: BotLanguage = BotLanguage.EN,
    ) {
        synchronized(jobStatusLocks[Math.floorMod(jobId, jobStatusLocks.size.toLong()).toInt()]) {
            editText(
                address,
                messages.text(language, MessageKey.STATUS_CANCELLED_BY_USER),
                downloadJobView.backKeyboard(jobId, language),
            )
        }
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
        address: MessageAddress,
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

    fun editMessage(
        address: MessageAddress,
        text: String,
        keyboard: InlineKeyboardMarkup? = null,
    ) {
        editText(address, text, keyboard)
    }

    fun editFinalMessage(address: MessageAddress, text: String, html: Boolean = false) {
        editText(address, text, EMPTY_KEYBOARD, if (html) ParseMode.HTML else null)
    }

    fun editPlaylistReport(address: MessageAddress, html: String, jobId: Long, language: BotLanguage, retryCount: Int, includeZip: Boolean, repeatToken: UUID? = null) {
        editText(address, html, downloadJobView.retryKeyboard(jobId, language, retryCount, includeZip, repeatToken), ParseMode.HTML)
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
        address: MessageAddress,
        text: String,
        keyboard: InlineKeyboardMarkup? = null,
        parseMode: ParseMode? = null,
    ) {
        val request = when (address) {
            is MessageAddress.Chat -> EditMessageText(address.chatId, address.messageId, text)
            is MessageAddress.Inline -> EditMessageText(address.inlineMessageId, text)
        }
        keyboard?.let(request::replyMarkup)
        parseMode?.let(request::parseMode)
        apiClient.execute(request)
    }

    private companion object {
        val EMPTY_KEYBOARD = InlineKeyboardMarkup()
    }
}

enum class TelegramDownloadStatus(val message: MessageKey) {
    ANALYZING(MessageKey.STATUS_ANALYZING),
    QUEUED(MessageKey.STATUS_QUEUED),
    DOWNLOADING(MessageKey.STATUS_DOWNLOADING),
    PACKING(MessageKey.STATUS_PACKING),
    UPLOADING(MessageKey.STATUS_UPLOADING),
    REJECTED_TOO_LARGE(MessageKey.STATUS_REJECTED_TOO_LARGE),
    AUTHENTICATION_REQUIRED(MessageKey.STATUS_AUTHENTICATION_REQUIRED),
    SOURCE_UNAVAILABLE(MessageKey.STATUS_SOURCE_UNAVAILABLE),
    FORMAT_UNAVAILABLE(MessageKey.STATUS_FORMAT_UNAVAILABLE),
    SOURCE_RATE_LIMITED(MessageKey.STATUS_SOURCE_RATE_LIMITED),
    SOURCE_REQUEST_FAILED(MessageKey.STATUS_SOURCE_REQUEST_FAILED),
    SOURCE_TIMEOUT(MessageKey.STATUS_SOURCE_TIMEOUT),
    METADATA_UNAVAILABLE(MessageKey.ERROR_METADATA_UNAVAILABLE),
    ERROR(MessageKey.STATUS_ERROR),
    CANCELLED(MessageKey.STATUS_CANCELLED_BY_USER),
    ;

    companion object {
        fun forFailure(reason: DownloadFailureReason?): TelegramDownloadStatus = entries.first { it.message == failureMessage(reason) }
    }
}
