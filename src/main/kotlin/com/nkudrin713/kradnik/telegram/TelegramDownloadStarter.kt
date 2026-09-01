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
 * [DownloadJobService]. Direct chats receive a new status message; guest downloads reuse their inline message.
 * Failed or duplicate direct-chat jobs remove the new status so no orphaned UI remains.
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
        messageAddress: TelegramMessageAddress,
        spec: DownloadSpec,
    ) {
        val statusMessageId = when (messageAddress) {
            is TelegramMessageAddress.Chat -> telegramSender.sendStatus(
                telegramChatId,
                TelegramDownloadStatus.QUEUED,
            )
            is TelegramMessageAddress.Inline -> {
                telegramSender.editStatus(messageAddress, TelegramDownloadStatus.QUEUED)
                null
            }
        }
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
            telegramInlineMessageId = (messageAddress as? TelegramMessageAddress.Inline)?.inlineMessageId,
        )
        val created = try {
            downloadJobService.createJob(command)
        } catch (error: Exception) {
            statusMessageId?.let { deleteStatusBestEffort(telegramChatId, it) }
            throw error
        }

        if (!created) {
            statusMessageId?.let { deleteStatusBestEffort(telegramChatId, it) }
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
