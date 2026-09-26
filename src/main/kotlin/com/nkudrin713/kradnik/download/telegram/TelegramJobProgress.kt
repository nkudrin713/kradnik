package com.nkudrin713.kradnik.download.telegram

import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.processing.DownloadPhase
import com.nkudrin713.kradnik.download.processing.JobProgress
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import com.nkudrin713.kradnik.telegram.TelegramMessageAddress
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessages
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class TelegramJobProgress(private val sender: TelegramSender, private val messages: TelegramMessages) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun forJob(job: DownloadJob): JobProgress = JobProgress { phase ->
        bestEffort(job) {
            val address = address(job) ?: return@bestEffort
            val status = when (phase) {
                DownloadPhase.DOWNLOADING -> TelegramDownloadStatus.DOWNLOADING
                DownloadPhase.PACKING -> TelegramDownloadStatus.PACKING
                DownloadPhase.UPLOADING -> TelegramDownloadStatus.UPLOADING
            }
            sender.editJobStatus(address, status, job.requiredId(), job.language)
        }
    }

    fun completed(job: DownloadJob) {
        if (job.telegramInlineMessageId != null) return
        val id = job.telegramStatusMessageId ?: return
        bestEffort(job) { sender.deleteMessage(job.telegramChatId, id) }
    }

    fun failed(job: DownloadJob, reason: DownloadFailureReason?) {
        val status = when {
            reason == DownloadFailureReason.TOO_LARGE -> TelegramDownloadStatus.REJECTED_TOO_LARGE
            job.workloadType == DownloadWorkloadType.PLAYLIST_AUDIO -> TelegramDownloadStatus.ERROR
            reason == DownloadFailureReason.AUTHENTICATION_REQUIRED -> TelegramDownloadStatus.AUTHENTICATION_REQUIRED
            reason == DownloadFailureReason.SOURCE_UNAVAILABLE -> TelegramDownloadStatus.SOURCE_UNAVAILABLE
            else -> TelegramDownloadStatus.ERROR
        }

        // Preserve the existing playlist terminal-status failure semantics during extraction.
        if (job.workloadType == DownloadWorkloadType.PLAYLIST_AUDIO) {
            sender.editStatus(requireNotNull(address(job)), status, job.language)
            return
        }
        bestEffort(job) {
            if (address(job) == null) return@bestEffort
            if (job.telegramInlineMessageId != null) {
                sender.editStatus(TelegramMessageAddress.Inline(requireNotNull(job.telegramInlineMessageId)), status, job.language)
            } else {
                sender.editStatus(job.telegramChatId, job.telegramStatusMessageId, status, job.language)
            }
        }
    }

    fun playlistSummary(job: DownloadJob, failedCount: Int) {
        if (failedCount == 0) return
        bestEffort(job) { sender.sendMessage(job.telegramChatId, messages.text(job.language, TelegramMessage.PLAYLIST_COMPLETED_WITH_ERRORS, failedCount)) }
    }

    private fun address(job: DownloadJob): TelegramMessageAddress? {
        job.telegramInlineMessageId?.let { return TelegramMessageAddress.Inline(it) }
        return job.telegramStatusMessageId?.let { TelegramMessageAddress.Chat(job.telegramChatId, it) }
    }

    private fun bestEffort(job: DownloadJob, action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            logger.warn("JOB[{}] status update failed", job.id, error)
        }
    }
}
