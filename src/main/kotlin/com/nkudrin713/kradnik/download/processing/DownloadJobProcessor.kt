package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.requiredId
import com.nkudrin713.kradnik.download.limit.DownloadPreflightDecision
import com.nkudrin713.kradnik.download.limit.DownloadPreflightService
import com.nkudrin713.kradnik.download.request.DownloadRequestFactory
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.telegram.TelegramFileSender
import com.nkudrin713.kradnik.download.video.TelegramVideoPreparer
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.createDirectories

@Component
class DownloadJobProcessor(
    private val downloadJobService: DownloadJobService,
    private val downloadRequestFactory: DownloadRequestFactory,
    private val downloadPreflightService: DownloadPreflightService,
    private val telegramVideoPreparer: TelegramVideoPreparer,
    private val telegramFileSender: TelegramFileSender,
    private val ytDlpService: YtDlpService,
    private val mediaMetadataMapper: MediaMetadataMapper,
    private val downloadJobLifecycle: DownloadJobLifecycle,
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

            val request = downloadRequestFactory.create(job)
            val metadata = ytDlpService.extractMetadata(request)
            val preflightDecision = downloadPreflightService.check(request, metadata)
            if (preflightDecision is DownloadPreflightDecision.Rejected) {
                downloadJobLifecycle.rejectTooLarge(job, preflightDecision.reason)
                return
            }

            downloadJobLifecycle.markDownloading(job)

            val uploadJob = markMetadata(jobId, metadata)

            val downloadedFile = ytDlpService.download(request, outputDir)
            val uploadFile = prepareForUpload(uploadJob, downloadedFile, outputDir, jobId)

            upload(uploadJob, uploadFile)
        } catch (error: Exception) {
            logger.error("JOB[{}] processing failed", jobId, error)
            downloadJobLifecycle.failOrRetry(job, error.message ?: error.javaClass.simpleName)
        } finally {
            workDirCleaner.deleteRecursively(outputDir)
        }
    }

    private suspend fun prepareForUpload(
        job: DownloadJob,
        downloadedFile: DownloadedFile,
        outputDir: Path,
        jobId: Long,
    ): DownloadedFile {
        return when (job.outputType) {
            OutputType.VIDEO -> telegramVideoPreparer.prepare(downloadedFile, outputDir, jobId)
            OutputType.AUDIO -> downloadedFile
        }
    }

    private suspend fun upload(job: DownloadJob, uploadFile: DownloadedFile) {
        downloadJobLifecycle.markUploading(job)
        logger.info(
            "JOB[{}] uploading to Telegram: type={}, sizeMb={}",
            job.requiredId(),
            job.outputType,
            formatMegabytes(uploadFile.sizeBytes),
        )

        val telegramResult = telegramFileSender.send(job, uploadFile)

        downloadJobLifecycle.complete(job, telegramResult.toDownloadedFileResult())
    }

    private fun sendCached(job: DownloadJob): Boolean {
        if (!telegramFileCacheEnabled) {
            return false
        }

        val cachedJob = downloadJobService.findCachedJob(job) ?: return false
        val fileId = cachedJob.telegramFileId ?: return false

        downloadJobLifecycle.markUploading(job)

        val telegramResult = telegramFileSender.sendCached(
            job = job,
            fileId = fileId,
            downloadedFileSize = cachedJob.downloadedFileSize,
        )

        downloadJobLifecycle.complete(job, telegramResult.toDownloadedFileResult())

        return true
    }

    private fun markMetadata(
        jobId: Long,
        metadata: YtDlpMetadataDto,
    ): DownloadJob {
        return downloadJobService.markMetadata(
            jobId,
            mediaMetadataMapper.toMediaMetadata(metadata),
        )
    }

    private fun formatMegabytes(bytes: Long): String {
        return String.format(Locale.US, "%.2f", bytes / BYTES_IN_MEGABYTE)
    }

    private companion object {
        private const val BYTES_IN_MEGABYTE = 1024.0 * 1024.0
    }
}
