package com.nkudrin713.kradnik.download

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.service.DownloadedFileResult
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Comparator
import java.util.Locale
import kotlin.io.path.createDirectories

@Component
@ConditionalOnProperty(
    name = ["download.worker.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class DownloadQueueWorker(
    private val downloadJobService: DownloadJobService,
    private val downloadOrchestrator: DownloadOrchestrator,
    private val telegramVideoPreparer: TelegramVideoPreparer,
    private val telegramSender: TelegramSender,
    private val ytDlpService: YtDlpService,
    @Value("\${download.work-dir:/tmp/kradnik-downloads}")
    private val workDir: String,
    @Value("\${download.telegram-file-cache.enabled:true}")
    private val telegramFileCacheEnabled: Boolean,
    @Value("\${download.worker-stale-timeout-ms:3600000}")
    private val workerStaleTimeoutMs: Long,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${download.worker-delay-ms:1000}")
    fun processNextJob() {
        recoverStaleJobs()

        val job = downloadJobService.claimNextQueuedJob() ?: return

        runBlocking {
            processJob(job)
        }
    }

    private fun recoverStaleJobs() {
        val staleBefore = Instant.now().minusMillis(workerStaleTimeoutMs)
        downloadJobService.recoverStaleInProgressJobs(staleBefore)
    }

    private suspend fun processJob(job: DownloadJob) {
        val jobId = requireNotNull(job.id)
        val outputDir = Path.of(workDir).resolve(jobId.toString()).createDirectories()

        try {
            if (sendCached(job)) {
                return
            }

            editStatus(job, TelegramDownloadStatus.DOWNLOADING)

            runCatching {
                markMetadata(job)
            }.onFailure {
                logger.warn("JOB[{}] metadata extraction skipped: {}", jobId, it.message)
            }

            val downloadedFile = downloadOrchestrator.download(job, outputDir)
            val uploadFile = when (job.outputType) {
                OutputType.VIDEO -> telegramVideoPreparer.prepare(downloadedFile, outputDir, jobId)
                OutputType.AUDIO -> downloadedFile
            }

            downloadJobService.markUploading(jobId)
            editStatus(job, TelegramDownloadStatus.UPLOADING)
            logger.info(
                "JOB[{}] uploading to Telegram: type={}, sizeMb={}",
                jobId,
                job.outputType,
                formatMegabytes(uploadFile.sizeBytes),
            )

            val telegramResult = when (job.outputType) {
                OutputType.VIDEO -> telegramSender.sendVideo(job.telegramChatId, uploadFile.file)
                OutputType.AUDIO -> telegramSender.sendAudio(job.telegramChatId, uploadFile.file)
            }

            downloadJobService.markCompleted(
                jobId,
                DownloadedFileResult(
                    telegramFileId = telegramResult.fileId,
                    telegramFileSize = telegramResult.fileSize,
                    downloadedFileSize = uploadFile.sizeBytes,
                )
            )
            editStatus(job, TelegramDownloadStatus.COMPLETED)
        } catch (error: Exception) {
            logger.error("JOB[{}] processing failed", jobId, error)
            downloadJobService.markFailedOrRetry(jobId, error.message ?: error.javaClass.simpleName)
            editStatus(job, TelegramDownloadStatus.ERROR)
        } finally {
            deleteRecursively(outputDir)
        }
    }

    private fun sendCached(job: DownloadJob): Boolean {
        if (!telegramFileCacheEnabled) {
            return false
        }

        val jobId = requireNotNull(job.id)
        val cachedJob = downloadJobService.findCachedJob(job) ?: return false
        val fileId = cachedJob.telegramFileId ?: return false

        editStatus(job, TelegramDownloadStatus.UPLOADING)

        val telegramResult = when (job.outputType) {
            OutputType.VIDEO -> telegramSender.sendCachedVideo(job.telegramChatId, fileId)
            OutputType.AUDIO -> telegramSender.sendCachedAudio(job.telegramChatId, fileId)
        }

        downloadJobService.markCompleted(
            jobId,
            DownloadedFileResult(
                telegramFileId = telegramResult.fileId,
                telegramFileSize = telegramResult.fileSize,
                downloadedFileSize = cachedJob.downloadedFileSize,
            )
        )
        editStatus(job, TelegramDownloadStatus.COMPLETED)

        return true
    }

    private fun editStatus(job: DownloadJob, status: TelegramDownloadStatus) {
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

    private suspend fun markMetadata(job: DownloadJob) {
        val jobId = requireNotNull(job.id)
        val metadata = ytDlpService.extractMetadata(job.originalUrl)

        downloadJobService.markMetadata(
            jobId,
            MediaMetadata(
                title = metadata.title,
                extractor = metadata.extractor,
                durationSeconds = metadata.duration?.toLong(),
                width = metadata.width,
                height = metadata.height,
                webpageUrl = metadata.webpageUrl,
            )
        )
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) {
            return
        }

        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder())
                .forEach(Files::deleteIfExists)
        }
    }

    private fun formatMegabytes(bytes: Long): String {
        return String.format(Locale.US, "%.2f", bytes / BYTES_IN_MEGABYTE)
    }

    private companion object {
        private const val BYTES_IN_MEGABYTE = 1024.0 * 1024.0
    }
}
