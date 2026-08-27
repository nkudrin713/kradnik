package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.DownloadEngine
import com.nkudrin713.kradnik.download.DownloadPreparation
import com.nkudrin713.kradnik.download.cleanup.WorkDirCapacityGuard
import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.cover.CoverTooLargeException
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.instagram.InstagramMediaTooLargeException
import com.nkudrin713.kradnik.download.limit.DownloadPreflightDecision
import com.nkudrin713.kradnik.download.limit.DownloadPreflightService
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.download.service.ClaimedDownloadJob
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.telegram.TelegramFileSender
import com.nkudrin713.kradnik.download.video.TelegramVideoPreparer
import com.nkudrin713.kradnik.download.video.VideoTooLargeException
import com.nkudrin713.kradnik.telegram.TelegramSendException
import com.nkudrin713.kradnik.ytdlp.client.YtDlpAuthenticationRequiredException
import com.nkudrin713.kradnik.ytdlp.client.YtDlpFileSizeLimitException
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.createDirectories

/**
 * Executes one lease-owned [ClaimedDownloadJob] from Telegram cache lookup through source preparation and delivery.
 * [DownloadEngine] binds metadata to the download path, [DownloadPreflightService] rejects known oversize media,
 * [TelegramVideoPreparer] normalizes video, and [DownloadJobLifecycle] records every terminal or retry outcome.
 */
@Component
class DownloadJobProcessor(
    private val downloadJobService: DownloadJobService,
    private val downloadPreflightService: DownloadPreflightService,
    private val telegramVideoPreparer: TelegramVideoPreparer,
    private val telegramFileSender: TelegramFileSender,
    private val downloadEngine: DownloadEngine,
    private val downloadJobLifecycle: DownloadJobLifecycle,
    private val workDirCleaner: WorkDirCleaner,
    private val workDirCapacityGuard: WorkDirCapacityGuard,
    private val uploadLimits: TelegramUploadLimits,
    @Value("\${download.work-dir:/tmp/kradnik-downloads}")
    private val workDir: String,
    @Value("\${download.telegram-file-cache.enabled:true}")
    private val telegramFileCacheEnabled: Boolean,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Processes [attempt] only while its lease remains valid and always removes the job workspace in `finally`.
     * Cancellation and [DownloadJobLeaseLostException][com.nkudrin713.kradnik.download.service.DownloadJobLeaseLostException]
     * propagate without being converted into a retry, while known source and size failures receive explicit outcomes.
     */
    suspend fun process(attempt: ClaimedDownloadJob) {
        val job = attempt.job
        val jobId = requireNotNull(job.id)
        val jobDir = Path.of(workDir).resolve(jobId.toString())
        val outputDir = jobDir
            .resolve(attempt.leaseToken.toString())

        try {
            workDirCleaner.deleteRecursively(jobDir)
            outputDir.createDirectories()
            if (sendCached(attempt)) {
                return
            }

            val spec = DownloadSpec.fromJob(job)
            val preparation = downloadEngine.prepare(spec)
            val session = when (preparation) {
                is DownloadPreparation.Ready -> preparation.session
                is DownloadPreparation.NotReady -> {
                    downloadJobLifecycle.deferBeforeAttempt(attempt, preparation.retryAt, preparation.reason)
                    return
                }

                is DownloadPreparation.RetryableFailure -> {
                    downloadJobLifecycle.retryAt(attempt, preparation.retryAt, preparation.reason)
                    return
                }

                is DownloadPreparation.SourceUnavailable -> {
                    downloadJobLifecycle.failSourceUnavailable(attempt, preparation.reason)
                    return
                }

                is DownloadPreparation.TerminalFailure -> {
                    downloadJobLifecycle.failTerminal(attempt, preparation.reason)
                    return
                }
            }
            val metadata = session.metadata
            val preflightDecision = downloadPreflightService.check(spec, metadata)
            if (preflightDecision is DownloadPreflightDecision.Rejected) {
                downloadJobLifecycle.rejectTooLarge(attempt, preflightDecision.reason)
                return
            }
            val downloadSpec = (preflightDecision as DownloadPreflightDecision.Allowed).spec

            workDirCapacityGuard.ensureDownloadCapacity(outputDir)
            downloadJobLifecycle.markDownloading(attempt)

            val uploadJob = markMetadata(attempt, metadata)

            val downloadedFile = session.download(downloadSpec, outputDir)
            val uploadFile = prepareForUpload(uploadJob, downloadedFile, outputDir, jobId)
            if (uploadFile.sizeBytes > uploadLimits.maxUploadBytes) {
                downloadJobLifecycle.rejectTooLarge(
                    attempt,
                    uploadLimitReason(uploadJob.outputType, uploadFile.sizeBytes),
                )
                return
            }

            upload(attempt, uploadJob, uploadFile)
        } catch (error: YtDlpAuthenticationRequiredException) {
            logger.warn("JOB[{}] requires authentication, failing without retry", jobId, error)
            downloadJobLifecycle.failAuthenticationRequired(
                attempt,
                error.message ?: error.javaClass.simpleName,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: VideoTooLargeException) {
            logger.warn("JOB[{}] video exceeds Telegram upload limit", jobId)
            downloadJobLifecycle.rejectTooLarge(
                attempt,
                uploadLimitReason(OutputType.VIDEO, error.sizeBytes),
            )
        } catch (error: YtDlpFileSizeLimitException) {
            logger.warn("JOB[{}] download exceeded safe file size limit", jobId)
            downloadJobLifecycle.rejectTooLarge(
                attempt,
                uploadLimitReason(job.outputType, error.limitBytes + 1),
            )
        } catch (error: InstagramMediaTooLargeException) {
            logger.warn("JOB[{}] Instagram media exceeds Telegram upload limit", jobId)
            downloadJobLifecycle.rejectTooLarge(
                attempt,
                uploadLimitReason(job.outputType, error.sizeBytes),
            )
        } catch (error: CoverTooLargeException) {
            logger.warn("JOB[{}] cover exceeds upload limit", jobId)
            downloadJobLifecycle.rejectTooLarge(
                attempt,
                uploadLimitReason(OutputType.COVER, error.sizeBytes),
            )
        } catch (error: TelegramSendException) {
            logger.error("JOB[{}] Telegram send failed", jobId, error)
            val errorMessage = error.message ?: error.javaClass.simpleName
            if (error.isRetryable()) {
                downloadJobLifecycle.failOrRetry(attempt, errorMessage, error.retryAfter)
            } else {
                downloadJobLifecycle.failTerminal(attempt, errorMessage)
            }
        } catch (error: Exception) {
            logger.error("JOB[{}] processing failed", jobId, error)
            downloadJobLifecycle.failOrRetry(attempt, error.message ?: error.javaClass.simpleName)
        } finally {
            workDirCleaner.deleteRecursively(jobDir)
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
            OutputType.AUDIO, OutputType.COVER -> downloadedFile
        }
    }

    private suspend fun upload(
        attempt: ClaimedDownloadJob,
        job: DownloadJob,
        uploadFile: DownloadedFile,
    ) {
        downloadJobLifecycle.markUploading(attempt)
        logger.info(
            "JOB[{}] uploading to Telegram: type={}, sizeMb={}",
            job.requiredId(),
            job.outputType,
            formatMegabytes(uploadFile.sizeBytes),
        )

        val telegramFileId = telegramFileSender.send(job, uploadFile)

        downloadJobLifecycle.complete(
            attempt = attempt,
            telegramFileId = telegramFileId,
        )
    }

    private suspend fun sendCached(attempt: ClaimedDownloadJob): Boolean {
        val job = attempt.job
        if (!telegramFileCacheEnabled) {
            return false
        }

        val cachedJob = downloadJobService.findCachedJob(job)
        cachedJob ?: return false
        val fileId = cachedJob.telegramFileId ?: return false

        val telegramFileId = try {
            telegramFileSender.sendCached(
                job = job,
                fileId = fileId,
            )
        } catch (error: TelegramSendException) {
            if (!error.isInvalidCachedFile()) {
                throw error
            }
            logger.warn("JOB[{}] cached Telegram file is invalid, downloading source", job.requiredId())
            return false
        }

        downloadJobLifecycle.markUploading(attempt)
        downloadJobLifecycle.complete(
            attempt = attempt,
            telegramFileId = telegramFileId,
        )

        return true
    }

    private fun markMetadata(
        attempt: ClaimedDownloadJob,
        metadata: YtDlpMetadataDto,
    ): DownloadJob {
        return downloadJobService.markAudioMetadata(
            attempt = attempt,
            durationSeconds = metadata.duration?.toInt(),
            title = metadata.track ?: metadata.title ?: DEFAULT_AUDIO_TITLE,
            performer = metadata.artist
                ?: metadata.uploader
                ?: metadata.channel
                ?: metadata.extractor
                ?: DEFAULT_AUDIO_PERFORMER,
        )
    }

    private fun formatMegabytes(bytes: Long): String {
        return String.format(Locale.US, "%.2f", bytes / BYTES_IN_MEGABYTE)
    }

    private fun uploadLimitReason(outputType: OutputType, sizeBytes: Long): String {
        return "Selected ${outputType.dbValue} is too large for Telegram: " +
                "sizeMb=${formatMegabytes(sizeBytes)}, limitMb=${formatMegabytes(uploadLimits.maxUploadBytes)}"
    }

    private companion object {
        private const val BYTES_IN_MEGABYTE = 1024.0 * 1024.0
        private const val DEFAULT_AUDIO_TITLE = "Audio"
        private const val DEFAULT_AUDIO_PERFORMER = "Unknown"
    }
}
