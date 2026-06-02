package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.domain.DownloadOutputType
import com.nkudrin713.kradnik.download.pipeline.DownloadTaskUiNotifier
import com.nkudrin713.kradnik.download.service.CreateDownloadTaskCommand
import com.nkudrin713.kradnik.download.service.DownloadTaskService
import com.nkudrin713.kradnik.settings.DownloadMode
import com.nkudrin713.kradnik.settings.DownloadSettingsDto
import com.nkudrin713.kradnik.settings.DownloadSettingsService
import com.nkudrin713.kradnik.telegram.ui.BotUiService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.generics.TelegramClient

@Service
class TelegramUpdateHandler(
    private val downloadTaskService: DownloadTaskService,
    private val downloadSettingsService: DownloadSettingsService,
    private val urlExtractor: UrlExtractor,
    private val botUiService: BotUiService,
    private val telegramClient: TelegramClient,
    private val downloadTaskUiNotifier: DownloadTaskUiNotifier,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun handle(update: Update) {
        val message = update.message ?: return
        val text = message.text ?: return
        logger.info("CHAT[{}] message received", message.chatId)

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
        telegramClient.execute(botUiService.toSendMessage(botUiService.downloadModeMessage(message.chatId, mode)))
        logger.info("CHAT[{}] mode set: {}", message.chatId, mode)
    }

    private fun createDownloadTask(message: Message, text: String) {
        val url = urlExtractor.extract(text)
        if (url == null) {
            logger.info("CHAT[{}] no url", message.chatId)
            return
        }
        val mode = downloadSettingsService.getMode(message.chatId)

        val task = downloadTaskService.createTask(
            CreateDownloadTaskCommand(
                telegramUserId = message.from.id,
                telegramChatId = message.chatId,
                originalUrl = url,
                normalizedUrl = normalizeUrl(url),
                outputType = mode.toOutputType(),
            )
        )
        downloadTaskUiNotifier.queued(task.id!!)
        logger.info("CHAT[{}] TASK[{}] queued: type={}", message.chatId, task.id, task.outputType)
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
