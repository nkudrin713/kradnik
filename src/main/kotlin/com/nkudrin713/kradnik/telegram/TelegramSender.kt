package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.video.VideoMetadataProbe
import com.nkudrin713.kradnik.download.domain.OutputType
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.EditMessageText
import com.pengrad.telegrambot.request.PinChatMessage
import com.pengrad.telegrambot.request.SendAudio
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.request.SendVideo
import com.pengrad.telegrambot.response.BaseResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

@Service
class TelegramSender(
    private val bot: TelegramBot,
    private val modeView: TelegramModeView,
    private val videoMetadataProbe: VideoMetadataProbe,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun sendMessage(chatId: Long, text: String) {
        sendText(chatId, text)
    }

    fun sendStatus(chatId: Long, status: TelegramDownloadStatus): Int {
        return sendText(chatId, status.text)
    }

    fun editStatus(chatId: Long, messageId: Int?, status: TelegramDownloadStatus) {
        if (messageId == null) {
            return
        }

        editText(chatId, messageId, status.text)
    }

    fun sendModeMenu(chatId: Long, outputType: OutputType) {
        sendText(chatId, modeView.text(), modeView.keyboard(outputType))
    }

    fun sendDonationMessage(chatId: Long, donationUrl: String) {
        sendText(chatId, DONATION_MESSAGE, donationKeyboard(donationUrl))
    }

    fun sendDonationPin(channelId: String, donationUrl: String): Int {
        val response = executeTelegram(
            SendMessage(channelId, DONATION_PIN_TEXT)
                .replyMarkup(donationKeyboard(donationUrl))
        )
        val messageId = requireNotNull(response.message()).messageId()
        pinMessage(channelId, messageId)
        return messageId
    }

    fun updateDonationPin(
        channelId: String,
        messageId: Int,
        donationUrl: String,
    ) {
        executeTelegram(
            EditMessageText(channelId, messageId, DONATION_PIN_TEXT)
                .replyMarkup(donationKeyboard(donationUrl))
        )
        pinMessage(channelId, messageId)
    }

    fun editModeMenu(chatId: Long, messageId: Int, outputType: OutputType) {
        editText(chatId, messageId, modeView.text(), modeView.keyboard(outputType))
    }

    fun answerCallback(callbackQueryId: String) {
        executeTelegram(AnswerCallbackQuery(callbackQueryId))
    }

    suspend fun sendVideo(chatId: Long, file: Path): TelegramSendResult {
        val fileSize = withContext(Dispatchers.IO) {
            Files.size(file)
        }
        val metadata = videoMetadataProbe.probe(file)
        logger.info(
            "Telegram video upload metadata: width={}, height={}, sar={}, dar={}",
            metadata.width,
            metadata.height,
            metadata.sampleAspectRatio,
            metadata.displayAspectRatio,
        )

        val response = withContext(Dispatchers.IO) {
            bot.execute(
                SendVideo(chatId, file.toFile())
                    .width(metadata.width)
                    .height(metadata.height)
                    .supportsStreaming(true)
            )
        }

        if (!response.isOk) {
            throw TelegramSendException("${response.description()} (sizeMb=${formatMegabytes(fileSize)})")
        }

        val video = response.message()?.video()
            ?: throw TelegramSendException("Telegram response does not contain video")

        return TelegramSendResult(
            fileId = video.fileId,
            fileSize = video.fileSize,
        )
    }

    fun sendCachedVideo(chatId: Long, fileId: String): TelegramSendResult {
        val response = bot.execute(
            SendVideo(chatId, fileId)
                .supportsStreaming(true)
        )

        if (!response.isOk) {
            throw TelegramSendException(response.description())
        }

        val video = response.message()?.video()
            ?: throw TelegramSendException("Telegram response does not contain video")

        return TelegramSendResult(
            fileId = video.fileId,
            fileSize = video.fileSize,
        )
    }

    suspend fun sendAudio(
        chatId: Long,
        file: Path,
        title: String?,
        performer: String?,
        durationSeconds: Int?,
    ): TelegramSendResult {
        val fileSize = withContext(Dispatchers.IO) {
            Files.size(file)
        }
        val response = withContext(Dispatchers.IO) {
            val request = SendAudio(chatId, file.toFile())
            if (title != null) {
                request.title(title)
            }
            if (performer != null) {
                request.performer(performer)
            }
            if (durationSeconds != null) {
                request.duration(durationSeconds)
            }

            bot.execute(request)
        }

        if (!response.isOk) {
            throw TelegramSendException("${response.description()} (sizeMb=${formatMegabytes(fileSize)})")
        }

        val audio = response.message()?.audio()
            ?: throw TelegramSendException("Telegram response does not contain audio")

        return TelegramSendResult(
            fileId = audio.fileId ?: throw TelegramSendException("Telegram audio file_id is empty"),
            fileSize = audio.fileSize,
        )
    }

    fun sendCachedAudio(chatId: Long, fileId: String): TelegramSendResult {
        val response = bot.execute(SendAudio(chatId, fileId))

        if (!response.isOk) {
            throw TelegramSendException(response.description())
        }

        val audio = response.message()?.audio()
            ?: throw TelegramSendException("Telegram response does not contain audio")

        return TelegramSendResult(
            fileId = audio.fileId ?: throw TelegramSendException("Telegram audio file_id is empty"),
            fileSize = audio.fileSize,
        )
    }

    private fun sendText(
        chatId: Long,
        text: String,
        keyboard: InlineKeyboardMarkup? = null,
    ): Int {
        val request = SendMessage(chatId, text)
        if (keyboard != null) {
            request.replyMarkup(keyboard)
        }

        val response = executeTelegram(request)
        return requireNotNull(response.message()).messageId()
    }

    private fun editText(
        chatId: Long,
        messageId: Int,
        text: String,
        keyboard: InlineKeyboardMarkup? = null,
    ) {
        val request = EditMessageText(chatId, messageId, text)
        if (keyboard != null) {
            request.replyMarkup(keyboard)
        }

        executeTelegram(request)
    }

    private fun donationKeyboard(donationUrl: String): InlineKeyboardMarkup {
        return InlineKeyboardMarkup(
            InlineKeyboardButton(DONATION_BUTTON_TEXT)
                .url(donationUrl)
        )
    }

    private fun pinMessage(channelId: String, messageId: Int) {
        executeTelegram(
            PinChatMessage(channelId, messageId)
                .disableNotification(true)
        )
    }

    private fun <T, R> executeTelegram(request: BaseRequest<T, R>): R
            where T : BaseRequest<T, R>, R : BaseResponse {
        val response = bot.execute(request)
        if (!response.isOk) {
            throw TelegramSendException(response.description())
        }

        return response
    }

    private fun formatMegabytes(bytes: Long): String {
        return String.format(Locale.US, "%.2f", bytes / BYTES_IN_MEGABYTE)
    }

    private companion object {
        private const val BYTES_IN_MEGABYTE = 1024.0 * 1024.0
        private val DONATION_MESSAGE = """
            Крадник остаётся бесплатным.
           
            А это — банка для тех, кто хочет подкинуть топлива проекту.
            Донаты уходят на хостинг, новые фичи и моральную устойчивость разработчика.
            Обещаю не покупать пиво и сигареты.
            
            Спасибо, что помогаете боту жить.
        """.trimIndent()
        private const val DONATION_PIN_TEXT = "Плеснуть топлива в медиадвигатель 🛢"
        private const val DONATION_BUTTON_TEXT = "Поддержать проект"
    }
}

data class TelegramSendResult(
    val fileId: String,
    val fileSize: Long?,
)

class TelegramSendException(message: String?) :
    RuntimeException("Telegram send failed: $message")

enum class TelegramDownloadStatus(val text: String) {
    QUEUED("В очереди ⏳"),
    DOWNLOADING("Скачиваю ⬇️"),
    UPLOADING("Загружаю в Telegram ⬆️"),
    COMPLETED("Готово ✅"),
    REJECTED_TOO_LARGE("Слишком тяжелый файл 🪨 Не справлюсь"),
    ERROR("Ошибка ⛔"),
}
