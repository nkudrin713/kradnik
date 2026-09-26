package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.domain.PlaylistDeliveryMode
import com.nkudrin713.kradnik.download.playlist.PlaylistSizeLimitException
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import com.nkudrin713.kradnik.telegram.TelegramMessageAddress
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessages
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class PlaylistJobProcessor(
    private val downloadJobService: DownloadJobService,
    private val telegramDelivery: TelegramPlaylistDelivery,
    private val zipDelivery: ZipPlaylistDelivery,
    private val telegramSender: TelegramSender,
    private val messages: TelegramMessages,
    private val workDirCleaner: WorkDirCleaner,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun process(job: DownloadJob) {
        require(job.workloadType == DownloadWorkloadType.PLAYLIST_AUDIO)
        var outputDir: Path? = null
        try {
            outputDir = workDirCleaner.create(job.requiredId())
            setStatus(job, TelegramDownloadStatus.DOWNLOADING)
            val result = when (job.playlistDeliveryMode) {
                PlaylistDeliveryMode.AUDIO_MESSAGES -> telegramDelivery.deliver(job, outputDir) { setStatus(job, it) }
                PlaylistDeliveryMode.ZIP -> zipDelivery.deliver(job, outputDir) { setStatus(job, it) }
            } ?: return
            if (!complete(job, result.telegramFileId)) return
            if (result.failedCount > 0) {
                runCatching {
                    telegramSender.sendMessage(
                        job.telegramChatId,
                        messages.text(job.language, TelegramMessage.PLAYLIST_COMPLETED_WITH_ERRORS, result.failedCount),
                    )
                }.onFailure { logger.warn("PLAYLIST_JOB[{}] summary delivery failed", job.id, it) }
            }
        } catch (error: CancellationException) {
            if (downloadJobService.isCancelledByUser(job.requiredId())) return
            throw error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: Exception) {
            logger.error("PLAYLIST_JOB[{}] failed", job.id, error)
            fail(
                job,
                error.message ?: error.javaClass.simpleName,
                if (error is PlaylistSizeLimitException) TelegramDownloadStatus.REJECTED_TOO_LARGE else TelegramDownloadStatus.ERROR,
            )
        } finally {
            outputDir?.let(workDirCleaner::deleteRecursively)
        }
    }

    private fun complete(job: DownloadJob, fileId: String): Boolean {
        if (!downloadJobService.markCompleted(job, fileId)) return false
        job.telegramStatusMessageId?.let { messageId ->
            runCatching { telegramSender.deleteMessage(job.telegramChatId, messageId) }
                .onFailure { logger.warn("PLAYLIST_JOB[{}] status deletion failed", job.id, it) }
        }
        return true
    }

    private fun fail(job: DownloadJob, reason: String, status: TelegramDownloadStatus) {
        if (!downloadJobService.markFailed(job, reason)) return
        telegramSender.editStatus(address(job), status, job.language)
    }

    private fun setStatus(job: DownloadJob, status: TelegramDownloadStatus) {
        runCatching {
            telegramSender.editJobStatus(address(job), status, job.requiredId(), job.language)
        }.onFailure { logger.warn("PLAYLIST_JOB[{}] status update failed", job.id, it) }
    }

    private fun address(job: DownloadJob): TelegramMessageAddress = TelegramMessageAddress.Chat(job.telegramChatId, requireNotNull(job.telegramStatusMessageId))
}

data class PlaylistDeliveryResult(val successfulCount: Int, val failedCount: Int, val telegramFileId: String)
