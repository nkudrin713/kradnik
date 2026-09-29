package com.nkudrin713.kradnik.download.telegram

import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.domain.PlaylistDeliveryMode
import com.nkudrin713.kradnik.download.playlist.PlaylistDeliveryResult
import com.nkudrin713.kradnik.download.playlist.PlaylistEmptyException
import com.nkudrin713.kradnik.download.playlist.PlaylistOperationException
import com.nkudrin713.kradnik.download.playlist.PlaylistRetrySelection
import com.nkudrin713.kradnik.download.playlist.PlaylistSizeLimitException
import com.nkudrin713.kradnik.download.processing.DownloadPhase
import com.nkudrin713.kradnik.download.processing.JobProgress
import com.nkudrin713.kradnik.download.processing.PlaylistProgress
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import com.nkudrin713.kradnik.telegram.TelegramMessageAddress
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessages
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class TelegramJobProgress(private val sender: TelegramSender, private val messages: TelegramMessages, private val jobs: DownloadJobService) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun forJob(job: DownloadJob): JobProgress = object : JobProgress {
        private var lastUpdate = 0L
        private var longWaitWarning: String? = null

        private fun withWarning(text: String): String = longWaitWarning?.let { "$text\n\n$it" } ?: text

        override fun update(phase: DownloadPhase) {
            bestEffort(job) {
                val address = address(job) ?: return@bestEffort
                val status = when (phase) {
                    DownloadPhase.DOWNLOADING -> TelegramDownloadStatus.DOWNLOADING
                    DownloadPhase.PACKING -> TelegramDownloadStatus.PACKING
                    DownloadPhase.UPLOADING -> TelegramDownloadStatus.UPLOADING
                }
                if (job.workloadType == DownloadWorkloadType.PLAYLIST_AUDIO) {
                    sender.editPlaylistProgress(address, withWarning(messages.text(job.language, status.message)), job.requiredId(), job.language) {
                        jobs.isProcessing(job.requiredId())
                    }
                } else {
                    sender.editJobStatus(address, status, job.requiredId(), job.language)
                }
            }
        }

        @Synchronized
        override fun playlist(progress: PlaylistProgress) {
            bestEffort(job) {
                if (!jobs.isProcessing(job.requiredId())) return@bestEffort
                val newWarning = progress.longWait && longWaitWarning == null
                if (newWarning) {
                    val message = if (progress.total == 1) TelegramMessage.PLAYLIST_LONG_ITEM_WARNING else TelegramMessage.PLAYLIST_LONG_WARNING
                    longWaitWarning = messages.text(job.language, message)
                }
                val now = System.nanoTime()
                if (!newWarning && lastUpdate != 0L && now - lastUpdate < 5_000_000_000L) return@bestEffort
                lastUpdate = now
                val text = messages.text(job.language, TelegramMessage.PLAYLIST_PROGRESS, progress.successful + progress.failed, progress.total, progress.successful, progress.failed)
                val active = progress.active.take(2).joinToString("\n") { "${it.position}. ${it.title.take(160)}" }
                val detail = if (active.isBlank()) "" else "\n\n" + messages.text(job.language, TelegramMessage.PLAYLIST_ACTIVE_ITEMS, active)
                val address = address(job) ?: return@bestEffort
                sender.editPlaylistProgress(address, withWarning(text + detail), job.requiredId(), job.language) { jobs.isProcessing(job.requiredId()) }
            }
        }
    }

    fun completed(job: DownloadJob) {
        if (job.workloadType == DownloadWorkloadType.PLAYLIST_AUDIO) return
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

        if (job.workloadType == DownloadWorkloadType.PLAYLIST_AUDIO) {
            bestEffort(job) { sender.editStatus(requireNotNull(address(job)), status, job.language) }
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

    fun playlistSummary(job: DownloadJob, result: PlaylistDeliveryResult) {
        if (job.playlistDeliveryMode == PlaylistDeliveryMode.AUDIO_MESSAGES && result.successfulCount > 1) {
            bestEffort(job) { sender.sendMessage(job.telegramChatId, messages.text(job.language, TelegramMessage.PLAYLIST_PLAYBACK_HINT)) }
        }
        bestEffort(job) {
            val address = address(job)
            if (result.failedCount == 0) {
                address?.let {
                    sender.editFinalMessage(it, messages.text(job.language, TelegramMessage.PLAYLIST_COMPLETED, result.successfulCount, job.playlistEntries.size))
                }
                return@bestEffort
            }
            val header = messages.text(job.language, TelegramMessage.PLAYLIST_PARTIAL_SUCCESS, result.successfulCount, job.playlistEntries.size)
            val report = PlaylistFailureReport(messages).render(header, job.playlistEntries, result.failures, job.language)
            publishReport(job, report, result.failures)
        }
    }

    fun playlistFailure(job: DownloadJob, error: Exception) {
        bestEffort(job) {
            if (error is PlaylistEmptyException) {
                val header = messages.text(job.language, TelegramMessage.PLAYLIST_ALL_FAILED)
                publishReport(job, PlaylistFailureReport(messages).render(header, job.playlistEntries, error.failures, job.language), error.failures)
                return@bestEffort
            }
            val key = when {
                error is PlaylistOperationException -> error.userMessage
                error is PlaylistSizeLimitException && job.playlistDeliveryMode == PlaylistDeliveryMode.ZIP -> TelegramMessage.ERROR_PLAYLIST_ZIP_TOO_LARGE
                else -> return@bestEffort
            }
            val address = address(job) ?: return@bestEffort
            sender.editMessage(address, messages.text(job.language, key))
        }
    }

    private fun address(job: DownloadJob): TelegramMessageAddress? {
        job.telegramInlineMessageId?.let { return TelegramMessageAddress.Inline(it) }
        return job.telegramStatusMessageId?.let { TelegramMessageAddress.Chat(job.telegramChatId, it) }
    }

    private fun publishReport(job: DownloadJob, report: List<String>, failures: List<PlaylistAudioResult>) {
        val retryCount = PlaylistRetrySelection.entries(job.playlistEntries, failures).size
        val includeZip = job.playlistDeliveryMode == PlaylistDeliveryMode.ZIP
        val address = address(job)
        if (address == null) {
            sendReport(job, report.first(), retryCount, includeZip)
            report.drop(1).forEach { sender.sendHtmlMessage(job.telegramChatId, it) }
            return
        }
        try {
            if (retryCount > 0) {
                sender.editPlaylistReport(address, report.first(), job.requiredId(), job.language, retryCount, includeZip)
            } else {
                sender.editFinalMessage(address, report.first(), html = true)
            }
        } catch (error: Exception) {
            logger.warn("JOB[{}] final status edit failed; sending report separately", job.id, error)
            sendReport(job, report.first(), retryCount, includeZip)
        }
        report.drop(1).forEach { sender.sendHtmlMessage(job.telegramChatId, it) }
    }

    private fun sendReport(job: DownloadJob, html: String, retryCount: Int, includeZip: Boolean) {
        if (retryCount > 0) {
            sender.sendPlaylistReport(job.telegramChatId, html, job.requiredId(), job.language, retryCount, includeZip)
        } else {
            sender.sendHtmlMessage(job.telegramChatId, html)
        }
    }

    private fun bestEffort(job: DownloadJob, action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            logger.warn("JOB[{}] status update failed", job.id, error)
        }
    }
}
