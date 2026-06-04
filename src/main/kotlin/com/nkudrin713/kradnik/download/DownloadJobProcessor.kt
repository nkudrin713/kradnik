package com.nkudrin713.kradnik.download

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.*
import kotlin.io.path.createDirectories

@Component
class DownloadJobProcessor(
    private val downloadJobService: DownloadJobService,
    private val downloadOrchestrator: DownloadOrchestrator,
    private val telegramVideoPreparer: TelegramVideoPreparer,
    private val telegramFileSender: TelegramFileSender,
    private val ytDlpService: YtDlpService,
    private val statusReporter: DownloadStatusReporter,
    private val workDirCleaner: WorkDirCleaner,
    @Value("\${download.work-dir:/tmp/kradnik-downloads}")
    private val workDir: String,
    @Value("\${download.telegram-file-cache.enabled:true}")
    private val telegramFileCacheEnabled: Boolean,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun process(job: DownloadJob) {
        val jobId = requireNotNull(job.id)
        val outputDir = Path.of(workDir).resolve(jobId.toString()).createDirectories()

        try {
            if (sendCached(job)) {
                return
            }

            statusReporter.setStatus(job, TelegramDownloadStatus.DOWNLOADING)

            runCatching {
                markMetadata(job)
            }.onFailure {
                logger.warn("JOB[{}] metadata extraction skipped: {}", jobId, it.message)
            }

            val downloadedFile = downloadOrchestrator.download(job, outputDir)
            val uploadFile = prepareForUpload(job, downloadedFile, outputDir)

            upload(job, uploadFile)
        } catch (error: Exception) {
            logger.error("JOB[{}] processing failed", jobId, error)
            downloadJobService.markFailedOrRetry(jobId, error.message ?: error.javaClass.simpleName)
            statusReporter.setStatus(job, TelegramDownloadStatus.ERROR)
        } finally {
            workDirCleaner.deleteRecursively(outputDir)
        }
    }

    private suspend fun prepareForUpload(
        job: DownloadJob,
        downloadedFile: DownloadedFile,
        outputDir: Path,
    ): DownloadedFile {
        val jobId = requireNotNull(job.id)

        return when (job.outputType) {
            OutputType.VIDEO -> telegramVideoPreparer.prepare(downloadedFile, outputDir, jobId)
            OutputType.AUDIO -> downloadedFile
        }
    }

    private suspend fun upload(job: DownloadJob, uploadFile: DownloadedFile) {
        val jobId = requireNotNull(job.id)

        downloadJobService.markUploading(jobId)
        statusReporter.setStatus(job, TelegramDownloadStatus.UPLOADING)
        logger.info(
            "JOB[{}] uploading to Telegram: type={}, sizeMb={}",
            jobId,
            job.outputType,
            formatMegabytes(uploadFile.sizeBytes),
        )

        val telegramResult = telegramFileSender.send(job, uploadFile)

        downloadJobService.markCompleted(jobId, telegramResult.toDownloadedFileResult())
        statusReporter.setStatus(job, TelegramDownloadStatus.COMPLETED)
    }

    private fun sendCached(job: DownloadJob): Boolean {
        if (!telegramFileCacheEnabled) {
            return false
        }

        val jobId = requireNotNull(job.id)
        val cachedJob = downloadJobService.findCachedJob(job) ?: return false
        val fileId = cachedJob.telegramFileId ?: return false

        statusReporter.setStatus(job, TelegramDownloadStatus.UPLOADING)

        val telegramResult = telegramFileSender.sendCached(
            job = job,
            fileId = fileId,
            downloadedFileSize = cachedJob.downloadedFileSize,
        )

        downloadJobService.markCompleted(jobId, telegramResult.toDownloadedFileResult())
        statusReporter.setStatus(job, TelegramDownloadStatus.COMPLETED)

        return true
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

    private fun formatMegabytes(bytes: Long): String {
        return String.format(Locale.US, "%.2f", bytes / BYTES_IN_MEGABYTE)
    }

    private companion object {
        private const val BYTES_IN_MEGABYTE = 1024.0 * 1024.0
    }
}
