package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import com.nkudrin713.kradnik.telegram.TelegramSender
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class DownloadStatusReporter(
    private val telegramSender: TelegramSender,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun setStatus(job: DownloadJob, status: TelegramDownloadStatus) {
        runCatching {
            telegramSender.editStatus(
                job.telegramChatId,
                job.telegramStatusMessageId,
                status,
            )
        }.onFailure {
            logger.warn("JOB[{}] status message update failed: {}", job.id, it.message)
        }
    }
}
