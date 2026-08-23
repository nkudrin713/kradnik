package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.telegram.config.TelegramBotProperties
import com.pengrad.telegrambot.request.SendAudio
import com.pengrad.telegrambot.request.SendDocument
import com.pengrad.telegrambot.request.SendVideo
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class TelegramMediaRequestFactory(
    private val properties: TelegramBotProperties,
) {
    fun video(chatId: Long, file: Path): SendVideo {
        return if (properties.localMode) {
            SendVideo(chatId, localFileUri(file))
        } else {
            SendVideo(chatId, file.toFile())
        }
    }

    fun audio(chatId: Long, file: Path): SendAudio {
        return if (properties.localMode) {
            SendAudio(chatId, localFileUri(file))
        } else {
            SendAudio(chatId, file.toFile())
        }
    }

    fun document(chatId: Long, file: Path): SendDocument {
        return if (properties.localMode) {
            SendDocument(chatId, localFileUri(file))
        } else {
            SendDocument(chatId, file.toFile())
        }
    }

    private fun localFileUri(file: Path): String {
        return file.toAbsolutePath().normalize().toUri().toString()
    }
}
