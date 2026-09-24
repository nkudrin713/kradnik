package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.DownloadEngine
import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.cover.CoverTooLargeException
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.instagram.InstagramContentUnavailableException
import com.nkudrin713.kradnik.download.instagram.InstagramMediaTooLargeException
import com.nkudrin713.kradnik.download.limit.DownloadPreflightDecision
import com.nkudrin713.kradnik.download.limit.DownloadPreflightService
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.telegram.TelegramFileSender
import com.nkudrin713.kradnik.download.video.TelegramVideoPreparer
import com.nkudrin713.kradnik.download.video.VideoTooLargeException
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import com.nkudrin713.kradnik.telegram.TelegramMessageAddress
import com.nkudrin713.kradnik.telegram.TelegramSendException
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.ytdlp.client.YtDlpAuthenticationRequiredException
import com.nkudrin713.kradnik.ytdlp.client.YtDlpFileSizeLimitException
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Path

/** One job: prepare, validate, download, send, persist outcome, and always clean up. No transaction spans I/O. */
@Component
class DownloadJobProcessor(
    private val downloadJobService: DownloadJobService,
    private val downloadPreflightService: DownloadPreflightService,
    private val telegramVideoPreparer: TelegramVideoPreparer,
    private val telegramFileSender: TelegramFileSender,
    private val downloadEngine: DownloadEngine,
    private val telegramSender: TelegramSender,
    private val workDirCleaner: WorkDirCleaner,
    private val uploadLimits: TelegramUploadLimits,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun process(job: DownloadJob) {
        var outputDir: Path? = null
        try {
            if (sendCached(job)) return
            outputDir = workDirCleaner.create(job.requiredId())
            val spec = DownloadSpec.fromJob(job)
            val prepared = downloadEngine.prepare(spec)
            val preflight = downloadPreflightService.check(spec, prepared.metadata)
            if (preflight is DownloadPreflightDecision.Rejected) {
                fail(job, preflight.reason, TelegramDownloadStatus.REJECTED_TOO_LARGE)
                return
            }
            val downloadSpec = (preflight as DownloadPreflightDecision.Allowed).spec
            setStatus(job, TelegramDownloadStatus.DOWNLOADING)
            val metadata = prepared.metadata
            job.sourceDurationSeconds = metadata.duration?.toInt()
            job.sourceAudioTitle = metadata.track ?: metadata.title ?: "Audio"
            job.sourceAudioPerformer = metadata.artist ?: metadata.uploader ?: metadata.channel ?: "Unknown"

            val downloaded = downloadEngine.download(downloadSpec, prepared, outputDir)
            val file = if (job.outputType == OutputType.VIDEO) {
                telegramVideoPreparer.prepare(downloaded, outputDir, job.requiredId())
            } else {
                downloaded
            }
            if (job.outputType != OutputType.IMAGES && file.sizeBytes > uploadLimits.maxUploadBytes) {
                fail(job, "File exceeds Telegram upload limit", TelegramDownloadStatus.REJECTED_TOO_LARGE)
                return
            }
            setStatus(job, TelegramDownloadStatus.UPLOADING)
            val fileId = telegramFileSender.send(job, file)
            complete(job, fileId)
        } catch (error: CancellationException) {
            // Shutdown leaves PROCESSING for startup recovery.
            throw error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: Exception) {
            logger.error("JOB[{}] failed", job.id, error)
            val status = when (error) {
                is YtDlpAuthenticationRequiredException -> TelegramDownloadStatus.AUTHENTICATION_REQUIRED
                is InstagramContentUnavailableException -> TelegramDownloadStatus.SOURCE_UNAVAILABLE
                is VideoTooLargeException, is YtDlpFileSizeLimitException,
                is InstagramMediaTooLargeException, is CoverTooLargeException -> TelegramDownloadStatus.REJECTED_TOO_LARGE
                else -> TelegramDownloadStatus.ERROR
            }
            fail(job, error.message ?: error.javaClass.simpleName, status)
        } finally {
            if (outputDir != null) workDirCleaner.deleteRecursively(outputDir)
        }
    }

    private suspend fun sendCached(job: DownloadJob): Boolean {
        val cached = downloadJobService.findCachedJob(job) ?: return false
        val fileId = cached.telegramFileId ?: return false
        val sentId = try {
            telegramFileSender.sendCached(job = job, fileId = fileId)
        } catch (error: TelegramSendException) {
            if (!error.isInvalidCachedFile()) throw error
            return false
        }
        complete(job, sentId)
        return true
    }

    private fun complete(job: DownloadJob, fileId: String) {
        downloadJobService.markCompleted(job, fileId)
        logger.info("JOB[{}] completed", job.id)
        val messageId = job.telegramStatusMessageId
        if (job.telegramInlineMessageId == null && messageId != null) {
            try {
                telegramSender.deleteMessage(job.telegramChatId, messageId)
            } catch (error: Exception) {
                logger.warn("JOB[{}] status deletion failed", job.id, error)
            }
        }
    }

    private fun fail(job: DownloadJob, reason: String, status: TelegramDownloadStatus) {
        downloadJobService.markFailed(job, reason)
        setStatus(job, status)
    }

    private fun setStatus(job: DownloadJob, status: TelegramDownloadStatus) {
        try {
            val inlineId = job.telegramInlineMessageId
            if (inlineId != null) {
                telegramSender.editStatus(TelegramMessageAddress.Inline(inlineId), status, job.language)
            } else {
                telegramSender.editStatus(job.telegramChatId, job.telegramStatusMessageId, status, job.language)
            }
        } catch (error: Exception) {
            logger.warn("JOB[{}] status update failed", job.id, error)
        }
    }
}
