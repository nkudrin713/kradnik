package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.analytics.DownloadAnalytics
import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.requiredId
import com.nkudrin713.kradnik.download.instagram.InstagramEmbedDownloader
import com.nkudrin713.kradnik.download.instagram.InstagramEmbedException
import com.nkudrin713.kradnik.download.instagram.InstagramPreparedDownload
import com.nkudrin713.kradnik.download.limit.DownloadPreflightDecision
import com.nkudrin713.kradnik.download.limit.DownloadPreflightService
import com.nkudrin713.kradnik.download.request.DownloadRequest
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.telegram.TelegramFileSender
import com.nkudrin713.kradnik.download.video.TelegramVideoPreparer
import com.nkudrin713.kradnik.telegram.TelegramSendException
import com.nkudrin713.kradnik.ytdlp.client.YtDlpAuthenticationRequiredException
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.createDirectories

@Component
class DownloadJobProcessor(
    private val downloadJobService: DownloadJobService,
    private val downloadPreflightService: DownloadPreflightService,
    private val telegramVideoPreparer: TelegramVideoPreparer,
    private val telegramFileSender: TelegramFileSender,
    private val instagramEmbedDownloader: InstagramEmbedDownloader,
    private val ytDlpService: YtDlpService,
    private val mediaMetadataMapper: MediaMetadataMapper,
    private val downloadJobLifecycle: DownloadJobLifecycle,
    private val downloadAnalytics: DownloadAnalytics,
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

            val request = DownloadRequest.fromJob(job)
            val instagramDownload = prepareInstagramDownload(request, jobId)
            val metadata = instagramDownload?.metadata ?: ytDlpService.extractMetadata(request)
            val preflightDecision = downloadPreflightService.check(request, metadata)
            downloadAnalytics.recordPreflightDecision(request, metadata, preflightDecision)
            if (preflightDecision is DownloadPreflightDecision.Rejected) {
                downloadJobLifecycle.rejectTooLarge(job, preflightDecision.reason)
                return
            }
            val downloadRequest = (preflightDecision as DownloadPreflightDecision.Allowed).request

            downloadJobLifecycle.markDownloading(job)

            val uploadJob = markMetadata(jobId, metadata)

            val downloadedFile = if (instagramDownload != null) {
                instagramEmbedDownloader.download(instagramDownload, outputDir)
            } else {
                ytDlpService.download(downloadRequest, outputDir)
            }
            val uploadFile = prepareForUpload(uploadJob, downloadedFile, outputDir, jobId)

            upload(uploadJob, uploadFile)
        } catch (error: YtDlpAuthenticationRequiredException) {
            logger.warn("JOB[{}] requires authentication, failing without retry", jobId, error)
            downloadJobLifecycle.failAuthenticationRequired(
                job,
                error.message ?: error.javaClass.simpleName,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.error("JOB[{}] processing failed", jobId, error)
            downloadJobLifecycle.failOrRetry(job, error.message ?: error.javaClass.simpleName)
        } finally {
            workDirCleaner.deleteRecursively(outputDir)
        }
    }

    private suspend fun prepareInstagramDownload(
        request: DownloadRequest,
        jobId: Long,
    ): InstagramPreparedDownload? {
        if (!instagramEmbedDownloader.supports(request)) {
            return null
        }

        return try {
            instagramEmbedDownloader.prepare(request)
        } catch (error: InstagramEmbedException) {
            logger.warn("JOB[{}] Instagram embed extraction failed, falling back to yt-dlp", jobId, error)
            null
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

        val cachedJob = downloadJobService.findCachedJob(job)
        downloadAnalytics.recordTelegramCacheLookup(job, cachedJob)
        cachedJob ?: return false
        val fileId = cachedJob.telegramFileId ?: return false

        val telegramResult = try {
            telegramFileSender.sendCached(
                job = job,
                fileId = fileId,
                downloadedFileSize = cachedJob.downloadedFileSize,
            )
        } catch (error: TelegramSendException) {
            if (!error.isInvalidCachedFile()) {
                throw error
            }
            logger.warn("JOB[{}] cached Telegram file is invalid, downloading source", job.requiredId())
            return false
        }

        downloadJobLifecycle.markUploading(job)
        downloadJobLifecycle.complete(job, telegramResult.toDownloadedFileResult())

        return true
    }

    private fun markMetadata(
        jobId: Long,
        metadata: YtDlpMetadataDto,
    ): DownloadJob {
        val mappedMetadata = mediaMetadataMapper.toMediaMetadata(metadata)
        val job = downloadJobService.markMetadata(
            jobId,
            mappedMetadata,
        )
        downloadAnalytics.recordMetadataExtracted(jobId, mappedMetadata, job)
        return job
    }

    private fun formatMegabytes(bytes: Long): String {
        return String.format(Locale.US, "%.2f", bytes / BYTES_IN_MEGABYTE)
    }

    private companion object {
        private const val BYTES_IN_MEGABYTE = 1024.0 * 1024.0
    }
}
