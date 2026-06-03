package com.nkudrin713.kradnik.telegram

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.request.SendAudio
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.request.SendVideo
import org.springframework.stereotype.Service
import java.nio.file.Path

@Service
class TelegramSender(
    private val bot: TelegramBot,
) {
    fun sendMessage(chatId: Long, text: String) {
        val response = bot.execute(SendMessage(chatId, text))

        if (!response.isOk) {
            throw TelegramSendException(response.description())
        }
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
}

data class TelegramSendResult(
    val fileId: String,
    val fileSize: Long?,
)

class TelegramSendException(message: String?) :
    RuntimeException("Telegram send failed: $message")
