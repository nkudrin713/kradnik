package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.domain.OutputType
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.request.SendAudio
import com.pengrad.telegrambot.request.SendVideo
import org.springframework.stereotype.Service
import java.nio.file.Path

@Service
class TelegramSender(
    private val bot: TelegramBot,
    private val telegramTextMessenger: TelegramTextMessenger,
    private val modeView: TelegramModeView,
) {
    fun sendMessage(chatId: Long, text: String) {
        telegramTextMessenger.send(chatId, text)
    }

    fun sendStatus(chatId: Long, status: TelegramDownloadStatus): Int {
        return telegramTextMessenger.send(chatId, status.text)
    }

    fun editStatus(chatId: Long, messageId: Int?, status: TelegramDownloadStatus) {
        if (messageId == null) {
            return
        }

        telegramTextMessenger.edit(chatId, messageId, status.text)
    }

    fun sendModeMenu(chatId: Long, outputType: OutputType) {
        telegramTextMessenger.send(chatId, modeView.text(), modeView.keyboard(outputType))
    }

    fun editModeMenu(chatId: Long, messageId: Int, outputType: OutputType) {
        telegramTextMessenger.edit(chatId, messageId, modeView.text(), modeView.keyboard(outputType))
    }

    fun answerCallback(callbackQueryId: String) {
        telegramTextMessenger.answerCallback(callbackQueryId)
    }

    fun sendVideo(chatId: Long, file: Path): TelegramSendResult {
        val response = bot.execute(
            SendVideo(chatId, file.toFile())
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

    fun sendAudio(chatId: Long, file: Path): TelegramSendResult {
        val response = bot.execute(SendAudio(chatId, file.toFile()))

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
}

data class TelegramSendResult(
    val fileId: String,
    val fileSize: Long?,
)

class TelegramSendException(message: String?) :
    RuntimeException("Telegram send failed: $message")

enum class TelegramDownloadStatus(val text: String) {
    QUEUED("Поставил в очередь ⏳"),
    DOWNLOADING("Скачиваю ⬇️"),
    UPLOADING("Загружаю в Telegram ⬆️"),
    COMPLETED("Готово ✅"),
    ERROR("Ошибка ⛔"),
}
