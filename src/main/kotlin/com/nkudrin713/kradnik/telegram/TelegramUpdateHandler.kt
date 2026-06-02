package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.domain.DownloadOutputType
import com.nkudrin713.kradnik.download.service.CreateDownloadTaskCommand
import com.nkudrin713.kradnik.download.service.DownloadTaskService
import com.nkudrin713.kradnik.settings.DownloadMode
import com.nkudrin713.kradnik.settings.DownloadSettingsDto
import com.nkudrin713.kradnik.settings.DownloadSettingsService
import org.springframework.stereotype.Service
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.message.Message

@Service
class TelegramUpdateHandler(
    private val downloadTaskService: DownloadTaskService,
    private val downloadSettingsService: DownloadSettingsService,
    private val urlExtractor: UrlExtractor,
) {
    fun handle(update: Update) {
        val message = update.message ?: return
        val text = message.text ?: return

        when (text.trim()) {
            "/audio" -> setMode(message, DownloadMode.AUDIO)
            "/video" -> setMode(message, DownloadMode.VIDEO)
            else -> createDownloadTask(message, text)
        }
    }

    private fun setMode(message: Message, mode: DownloadMode) {
        downloadSettingsService.setMode(
            DownloadSettingsDto(
                chatId = message.chatId,
                mode = mode.name,
            )
        )
    }

    private fun createDownloadTask(message: Message, text: String) {
        val url = urlExtractor.extract(text) ?: return
        val mode = downloadSettingsService.getMode(message.chatId)

        downloadTaskService.createTask(
            CreateDownloadTaskCommand(
                telegramUserId = message.from.id,
                telegramChatId = message.chatId,
                originalUrl = url,
                normalizedUrl = normalizeUrl(url),
                outputType = mode.toOutputType(),
            )
        )
    }

    private fun normalizeUrl(url: String): String =
        // TODO: replace with real canonical URL normalization per source.
        url.trim()

    private fun DownloadMode.toOutputType(): DownloadOutputType =
        when (this) {
            DownloadMode.VIDEO -> DownloadOutputType.VIDEO
            DownloadMode.AUDIO -> DownloadOutputType.AUDIO
        }
}
