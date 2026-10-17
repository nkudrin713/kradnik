package com.nkudrin713.kradnik.download.telegram

import com.nkudrin713.kradnik.download.DownloadEngine
import com.nkudrin713.kradnik.download.choice.CreateDownloadRetryCommand
import com.nkudrin713.kradnik.download.choice.DownloadChoiceSessionService
import com.nkudrin713.kradnik.download.delivery.MessageAddress
import com.nkudrin713.kradnik.download.delivery.PlaylistDeliveryResult
import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.domain.PlaylistDeliveryMode
import com.nkudrin713.kradnik.download.playlist.PlaylistEmptyException
import com.nkudrin713.kradnik.download.playlist.PlaylistOperationException
import com.nkudrin713.kradnik.download.playlist.PlaylistRetrySelection
import com.nkudrin713.kradnik.download.playlist.PlaylistSizeLimitException
import com.nkudrin713.kradnik.download.progress.DownloadPhase
import com.nkudrin713.kradnik.download.progress.JobNotifications
import com.nkudrin713.kradnik.download.progress.JobProgress
import com.nkudrin713.kradnik.download.progress.PlaylistProgress
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.localization.MessageKey
import com.nkudrin713.kradnik.localization.Messages
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import com.nkudrin713.kradnik.telegram.TelegramSender
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class TelegramJobProgress(private val sender: TelegramSender, private val messages: Messages, private val jobs: DownloadJobService, private val retrySessions: DownloadChoiceSessionService, private val engine: DownloadEngine) : JobNotifications {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun forJob(job: DownloadJob): JobProgress = object : JobProgress {
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
                    val message = if (progress.total == 1) MessageKey.PLAYLIST_LONG_ITEM_WARNING else MessageKey.PLAYLIST_LONG_WARNING
                    longWaitWarning = messages.text(job.language, message)
                }
                val now = System.nanoTime()
                if (!newWarning && lastUpdate != 0L && now - lastUpdate < 5_000_000_000L) return@bestEffort
                lastUpdate = now
                val text = messages.text(job.language, MessageKey.PLAYLIST_PROGRESS, progress.successful + progress.failed, progress.total, progress.successful, progress.failed)
                val active = progress.active.take(2).joinToString("\n") { "${it.position}. ${it.title.take(160)}" }
                val detail = if (active.isBlank()) "" else "\n\n" + messages.text(job.language, MessageKey.PLAYLIST_ACTIVE_ITEMS, active)
                val address = address(job) ?: return@bestEffort
                sender.editPlaylistProgress(address, withWarning(text + detail), job.requiredId(), job.language) { jobs.isProcessing(job.requiredId()) }
            }
        }
    }

    override fun completed(job: DownloadJob) {
        if (job.workloadType == DownloadWorkloadType.PLAYLIST_AUDIO) return
        if (job.telegramInlineMessageId != null) return
        val id = job.telegramStatusMessageId ?: return
        bestEffort(job) { sender.deleteMessage(job.telegramChatId, id) }
    }

    override fun failed(job: DownloadJob, reason: DownloadFailureReason?) {
        val status = when {
            reason == DownloadFailureReason.TOO_LARGE -> TelegramDownloadStatus.REJECTED_TOO_LARGE
            job.workloadType == DownloadWorkloadType.PLAYLIST_AUDIO -> TelegramDownloadStatus.ERROR
            else -> TelegramDownloadStatus.forFailure(reason)
        }

        if (job.workloadType == DownloadWorkloadType.PLAYLIST_AUDIO) {
            bestEffort(job) { sender.editStatus(requireNotNull(address(job)), status, job.language) }
            return
        }
        bestEffort(job) {
            if (address(job) == null) return@bestEffort
            val address = requireNotNull(address(job))
            sender.editFailure(address, messages.text(job.language, status.message), retryToken(job, address), job.language, job.requiredId())
        }
    }

    override fun playlistSummary(job: DownloadJob, result: PlaylistDeliveryResult) {
        if (job.playlistDeliveryMode == PlaylistDeliveryMode.AUDIO_MESSAGES && result.successfulCount > 1) {
            bestEffort(job) { sender.sendMessage(job.telegramChatId, messages.text(job.language, MessageKey.PLAYLIST_PLAYBACK_HINT)) }
        }
        bestEffort(job) {
            val address = address(job)
            if (result.failedCount == 0) {
                address?.let {
                    sender.editFinalMessage(it, messages.text(job.language, MessageKey.PLAYLIST_COMPLETED, result.successfulCount, job.playlistEntries.size))
                }
                return@bestEffort
            }
            val header = messages.text(job.language, MessageKey.PLAYLIST_PARTIAL_SUCCESS, result.successfulCount, job.playlistEntries.size)
            val report = PlaylistFailureReport(messages, engine).render(header, job.playlistEntries, result.failures, job.language)
            publishReport(job, report, result.failures)
        }
    }

    override fun playlistFailure(job: DownloadJob, error: Exception) {
        bestEffort(job) {
            if (error is PlaylistEmptyException) {
                val header = messages.text(job.language, MessageKey.PLAYLIST_ALL_FAILED)
                val token = address(job)?.let { retryToken(job, it) }
                publishReport(job, PlaylistFailureReport(messages, engine).render(header, job.playlistEntries, error.failures, job.language), error.failures, token)
                return@bestEffort
            }
            val key = when {
                error is PlaylistOperationException -> error.userMessage
                error is PlaylistSizeLimitException && job.playlistDeliveryMode == PlaylistDeliveryMode.ZIP -> MessageKey.ERROR_PLAYLIST_ZIP_TOO_LARGE
                else -> TelegramDownloadStatus.forFailure((error as? DownloadFailure)?.reason).message
            }
            val address = address(job) ?: return@bestEffort
            sender.editFailure(address, messages.text(job.language, key), retryToken(job, address), job.language, job.requiredId())
        }
    }

    private fun retryToken(job: DownloadJob, address: MessageAddress): UUID? = try {
        retrySessions.createRetry(
            CreateDownloadRetryCommand(
                job.telegramUserId,
                job.telegramChatId,
                job.telegramUpdateId ?: 0,
                job.telegramRequestMessageId ?: 0,
                (address as? MessageAddress.Chat)?.messageId,
                (address as? MessageAddress.Inline)?.inlineMessageId,
                job.language,
                job.originalUrl,
            ),
        ).token
    } catch (error: Exception) {
        logger.warn("JOB[{}] retry button persistence failed", job.id, error)
        null
    }

    private fun address(job: DownloadJob): MessageAddress? {
        job.telegramInlineMessageId?.let { return MessageAddress.Inline(it) }
        return job.telegramStatusMessageId?.let { MessageAddress.Chat(job.telegramChatId, it) }
    }

    private fun publishReport(job: DownloadJob, report: List<String>, failures: List<PlaylistAudioResult>, repeatToken: UUID? = null) {
        val retryCount = PlaylistRetrySelection.entries(job.playlistEntries, failures).size
        val includeZip = job.playlistDeliveryMode == PlaylistDeliveryMode.ZIP
        val address = address(job)
        if (address == null) {
            sendReport(job, report.first(), retryCount, includeZip)
            report.drop(1).forEach { sender.sendHtmlMessage(job.telegramChatId, it) }
            return
        }
        try {
            if (retryCount > 0 || repeatToken != null) {
                sender.editPlaylistReport(address, report.first(), job.requiredId(), job.language, retryCount, includeZip, repeatToken)
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
