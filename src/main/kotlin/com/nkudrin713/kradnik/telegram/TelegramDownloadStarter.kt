package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.service.CreateDownloadJobCommand
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.video.TelegramVideoPolicy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Publishes a queued status, versions video cache identity with [TelegramVideoPolicy], and delegates persistence to
 * [DownloadJobService]. If creation fails or the Telegram update already owns a job, the new status message is removed
 * so [DownloadChoiceHandler][com.nkudrin713.kradnik.telegram.handler.DownloadChoiceHandler] leaves no orphaned UI.
 */
@Component
class TelegramDownloadStarter(
    private val downloadJobService: DownloadJobService,
    private val telegramSender: TelegramSender,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun start(
        telegramUserId: Long,
        telegramChatId: Long,
        telegramUpdateId: Int,
        telegramRequestMessageId: Int,
        spec: DownloadSpec,
    ) {
        val statusMessageId = telegramSender.sendStatus(
            telegramChatId,
            TelegramDownloadStatus.QUEUED,
        )
        val jobSpec = spec.copy(
            cacheKey = when (spec.outputType) {
                OutputType.VIDEO -> TelegramVideoPolicy.versionCacheKey(spec.cacheKey)
                OutputType.AUDIO, OutputType.COVER -> spec.cacheKey
            },
        )
        val command = CreateDownloadJobCommand(
            telegramUserId = telegramUserId,
            telegramChatId = telegramChatId,
            telegramUpdateId = telegramUpdateId,
            telegramRequestMessageId = telegramRequestMessageId,
            spec = jobSpec,
            telegramStatusMessageId = statusMessageId,
        )
        val created = try {
            downloadJobService.createJob(command)
        } catch (error: Exception) {
            deleteStatusBestEffort(telegramChatId, statusMessageId)
            throw error
        }

        if (!created) {
            deleteStatusBestEffort(telegramChatId, statusMessageId)
        }
    }

    private fun deleteStatusBestEffort(chatId: Long, messageId: Int) {
        runCatching {
            telegramSender.deleteMessage(chatId, messageId)
        }.onFailure {
            logger.warn(
                "Duplicate or failed job status message deletion failed: chatId={}, messageId={}",
                chatId,
                messageId,
                it,
            )
        }
    }
}
