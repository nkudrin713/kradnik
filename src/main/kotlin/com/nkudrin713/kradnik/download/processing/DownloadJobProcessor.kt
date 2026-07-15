package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.analytics.DownloadAnalytics
import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.requiredId
import com.nkudrin713.kradnik.download.executor.DownloadExecutorResolver
import com.nkudrin713.kradnik.download.executor.DownloadPreparation
import com.nkudrin713.kradnik.download.limit.DownloadPreflightDecision
import com.nkudrin713.kradnik.download.limit.DownloadPreflightService
import com.nkudrin713.kradnik.download.request.DownloadRequest
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.telegram.TelegramFileSender
import com.nkudrin713.kradnik.download.video.TelegramVideoPreparer
import com.nkudrin713.kradnik.telegram.TelegramSendException
import com.nkudrin713.kradnik.ytdlp.client.YtDlpAuthenticationRequiredException
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
    private val downloadExecutorResolver: DownloadExecutorResolver,
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
            val preparation = downloadExecutorResolver.resolve(request).prepare(request)
            val session = when (preparation) {
                is DownloadPreparation.Ready -> preparation.session
                is DownloadPreparation.NotReady -> {
                    downloadJobLifecycle.deferBeforeAttempt(job, preparation.retryAt, preparation.reason)
                    return
                }

                is DownloadPreparation.RetryableFailure -> {
                    downloadJobLifecycle.retryAt(job, preparation.retryAt, preparation.reason)
                    return
                }

                is DownloadPreparation.TerminalFailure -> {
                    downloadJobLifecycle.failTerminal(job, preparation.reason)
                    return
                }
            }
            val metadata = session.metadata
            val preflightDecision = downloadPreflightService.check(request, metadata)
            downloadAnalytics.recordPreflightDecision(request, metadata, preflightDecision)
            if (preflightDecision is DownloadPreflightDecision.Rejected) {
                downloadJobLifecycle.rejectTooLarge(job, preflightDecision.reason)
                return
            }
            val downloadRequest = (preflightDecision as DownloadPreflightDecision.Allowed).request

            downloadJobLifecycle.markDownloading(job)

            val uploadJob = markMetadata(jobId, metadata)

            val downloadedFile = session.download(downloadRequest, outputDir)
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
        } catch (error: TelegramSendException) {
            logger.error("JOB[{}] Telegram send failed", jobId, error)
            val errorMessage = error.message ?: error.javaClass.simpleName
            if (error.isRetryable()) {
                downloadJobLifecycle.failOrRetry(job, errorMessage)
            } else {
                downloadJobLifecycle.failTerminal(job, errorMessage)
            }
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

    private suspend fun sendCached(job: DownloadJob): Boolean {
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
