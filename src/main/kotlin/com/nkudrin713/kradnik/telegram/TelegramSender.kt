package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.VideoMetadataProbe
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.request.SendAudio
import com.pengrad.telegrambot.request.SendVideo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

@Service
class TelegramSender(
    private val bot: TelegramBot,
    private val telegramTextMessenger: TelegramTextMessenger,
    private val modeView: TelegramModeView,
    private val videoMetadataProbe: VideoMetadataProbe,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

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

    suspend fun sendVideo(chatId: Long, file: Path): TelegramSendResult {
        val fileSize = Files.size(file)
        val metadata = videoMetadataProbe.probe(file)
        logger.info(
            "Telegram video upload metadata: width={}, height={}, sar={}, dar={}",
            metadata.width,
            metadata.height,
            metadata.sampleAspectRatio,
            metadata.displayAspectRatio,
        )

        val response = bot.execute(
            SendVideo(chatId, file.toFile())
                .width(metadata.width)
                .height(metadata.height)
                .supportsStreaming(true)
        )

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

    fun sendAudio(chatId: Long, file: Path): TelegramSendResult {
        val fileSize = Files.size(file)
        val response = bot.execute(SendAudio(chatId, file.toFile()))

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

    private fun formatMegabytes(bytes: Long): String {
        return String.format(Locale.US, "%.2f", bytes / BYTES_IN_MEGABYTE)
    }

    private companion object {
        private const val BYTES_IN_MEGABYTE = 1024.0 * 1024.0
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
