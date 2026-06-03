package com.nkudrin713.kradnik.telegram

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.EditMessageText
import com.pengrad.telegrambot.request.SendAudio
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.request.SendVideo
import com.nkudrin713.kradnik.download.domain.OutputType
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

    fun sendModeMenu(chatId: Long, outputType: OutputType) {
        val response = bot.execute(
            SendMessage(chatId, MODE_TEXT)
                .replyMarkup(modeKeyboard(outputType))
        )

        if (!response.isOk) {
            throw TelegramSendException(response.description())
        }
    }

    fun editModeMenu(chatId: Long, messageId: Int, outputType: OutputType) {
        val response = bot.execute(
            EditMessageText(chatId, messageId, MODE_TEXT)
                .replyMarkup(modeKeyboard(outputType))
        )

        if (!response.isOk) {
            throw TelegramSendException(response.description())
        }
    }

    fun answerCallback(callbackQueryId: String) {
        val response = bot.execute(AnswerCallbackQuery(callbackQueryId))

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

private const val MODE_TEXT = "Режим загрузки"
private const val VIDEO_CALLBACK = "mode:video"
private const val AUDIO_CALLBACK = "mode:audio"

private fun modeKeyboard(outputType: OutputType): InlineKeyboardMarkup {
    return InlineKeyboardMarkup(
        InlineKeyboardButton(label("Видео", OutputType.VIDEO, outputType)).callbackData(VIDEO_CALLBACK),
        InlineKeyboardButton(label("Аудио", OutputType.AUDIO, outputType)).callbackData(AUDIO_CALLBACK),
    )
}

private fun label(text: String, option: OutputType, current: OutputType): String {
    return if (option == current) {
        "✅ $text"
    } else {
        text
    }
}
