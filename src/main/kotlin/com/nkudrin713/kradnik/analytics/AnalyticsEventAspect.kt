package com.nkudrin713.kradnik.analytics

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.requiredId
import com.nkudrin713.kradnik.download.limit.DownloadPreflightDecision
import com.nkudrin713.kradnik.download.request.DownloadRequest
import com.nkudrin713.kradnik.download.service.CreateDownloadJobCommand
import com.nkudrin713.kradnik.download.service.DownloadedFileResult
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import org.aspectj.lang.annotation.AfterReturning
import org.aspectj.lang.annotation.Aspect
import org.springframework.stereotype.Component

@Aspect
@Component
class AnalyticsEventAspect(
    private val analyticsEventService: AnalyticsEventService,
) {
    @AfterReturning(
        pointcut = "execution(* com.nkudrin713.kradnik.download.service.DownloadJobService.createJob(..)) && args(command)",
        returning = "job",
    )
    fun recordDownloadRequested(
        command: CreateDownloadJobCommand,
        job: DownloadJob,
    ) {
        analyticsEventService.record(
            RecordAnalyticsEventCommand(
                eventType = AnalyticsEventType.DOWNLOAD_REQUESTED,
                jobId = job.id,
                telegramUserId = command.telegramUserId,
                telegramChatId = command.telegramChatId,
                outputType = command.outputType,
                cacheKey = command.cacheKey,
                properties = mapOf(
                    "downloadPreset" to command.downloadPreset,
                    "selectedFormat" to command.selectedFormat,
                ),
            )
        )
    }

    @AfterReturning(
        pointcut = "execution(* com.nkudrin713.kradnik.download.service.DownloadJobService.findCachedJob(..)) && args(job)",
        returning = "cachedJob",
    )
    fun recordTelegramCacheLookup(
        job: DownloadJob,
        cachedJob: DownloadJob?,
    ) {
        analyticsEventService.record(
            job.toRecordCommand(
                eventType = if (cachedJob == null) {
                    AnalyticsEventType.TELEGRAM_CACHE_MISS
                } else {
                    AnalyticsEventType.TELEGRAM_CACHE_HIT
                },
                success = cachedJob != null,
                properties = mapOf(
                    "cachedJobId" to cachedJob?.id,
                ),
            )
        )
    }

    @AfterReturning(
        pointcut = "execution(* com.nkudrin713.kradnik.download.service.DownloadJobService.markMetadata(..)) && args(jobId, metadata)",
        returning = "job",
    )
    fun recordMetadataExtracted(
        jobId: Long,
        metadata: MediaMetadata,
        job: DownloadJob,
    ) {
        analyticsEventService.record(
            job.toRecordCommand(
                eventType = AnalyticsEventType.METADATA_EXTRACTED,
                platform = metadata.extractor,
                sourceDurationSeconds = metadata.durationSeconds?.toInt(),
                success = true,
                properties = mapOf(
                    "jobId" to jobId,
                    "width" to metadata.width,
                    "height" to metadata.height,
                    "hasAudioTitle" to (metadata.audioTitle != null),
                    "hasAudioPerformer" to (metadata.audioPerformer != null),
                ),
            )
        )
    }

    @AfterReturning(
        pointcut = "execution(* com.nkudrin713.kradnik.download.limit.DownloadPreflightService.check(..)) && args(request, metadata)",
        returning = "decision",
    )
    fun recordPreflightDecision(
        request: DownloadRequest,
        metadata: YtDlpMetadataDto,
        decision: DownloadPreflightDecision,
    ) {
        analyticsEventService.record(
            RecordAnalyticsEventCommand(
                eventType = when (decision) {
                    is DownloadPreflightDecision.Allowed -> AnalyticsEventType.PREFLIGHT_ALLOWED
                    is DownloadPreflightDecision.Rejected -> AnalyticsEventType.PREFLIGHT_REJECTED
                },
                platform = metadata.extractor,
                outputType = request.outputType,
                sourceDurationSeconds = metadata.duration?.toInt(),
                success = decision is DownloadPreflightDecision.Allowed,
                errorCode = if (decision is DownloadPreflightDecision.Rejected) {
                    "too_large"
                } else {
                    null
                },
                properties = mapOf(
                    "presetName" to request.presetName,
                    "normalizedUrl" to request.normalizedUrl,
                    "filesize" to metadata.filesize,
                    "filesizeApprox" to metadata.filesizeApprox,
                    "reason" to (decision as? DownloadPreflightDecision.Rejected)?.reason,
                ),
            )
        )
    }

    @AfterReturning("execution(* com.nkudrin713.kradnik.download.processing.DownloadJobLifecycle.markDownloading(..)) && args(job)")
    fun recordDownloadStarted(job: DownloadJob) {
        analyticsEventService.record(
            job.toRecordCommand(
                eventType = AnalyticsEventType.DOWNLOAD_STARTED,
                success = true,
            )
        )
    }

    @AfterReturning("execution(* com.nkudrin713.kradnik.download.processing.DownloadJobLifecycle.markUploading(..)) && args(job)")
    fun recordUploadStarted(job: DownloadJob) {
        analyticsEventService.record(
            job.toRecordCommand(
                eventType = AnalyticsEventType.UPLOAD_STARTED,
                success = true,
            )
        )
    }

    @AfterReturning("execution(* com.nkudrin713.kradnik.download.processing.DownloadJobLifecycle.complete(..)) && args(job, result)")
    fun recordDownloadCompleted(
        job: DownloadJob,
        result: DownloadedFileResult,
    ) {
        analyticsEventService.record(
            job.toRecordCommand(
                eventType = AnalyticsEventType.DOWNLOAD_COMPLETED,
                downloadedFileSize = result.downloadedFileSize,
                telegramFileSize = result.telegramFileSize,
                success = true,
            )
        )
    }

    @AfterReturning("execution(* com.nkudrin713.kradnik.download.processing.DownloadJobLifecycle.rejectTooLarge(..)) && args(job, reason)")
    fun recordDownloadRejected(
        job: DownloadJob,
        reason: String,
    ) {
        analyticsEventService.record(
            job.toRecordCommand(
                eventType = AnalyticsEventType.DOWNLOAD_REJECTED,
                success = false,
                errorCode = "too_large",
                properties = mapOf("reason" to reason),
            )
        )
    }

    @AfterReturning("execution(* com.nkudrin713.kradnik.download.processing.DownloadJobLifecycle.failAuthenticationRequired(..)) && args(job, errorMessage)")
    fun recordAuthenticationRequiredFailure(
        job: DownloadJob,
        errorMessage: String,
    ) {
        recordDownloadFailed(
            job = job,
            errorCode = "authentication_required",
            errorMessage = errorMessage,
        )
    }

    @AfterReturning("execution(* com.nkudrin713.kradnik.download.processing.DownloadJobLifecycle.failOrRetry(..)) && args(job, errorMessage)")
    fun recordRetryableFailure(
        job: DownloadJob,
        errorMessage: String,
    ) {
        recordDownloadFailed(
            job = job,
            errorCode = "processing_failed",
            errorMessage = errorMessage,
        )
    }

    private fun recordDownloadFailed(
        job: DownloadJob,
        errorCode: String,
        errorMessage: String,
    ) {
        analyticsEventService.record(
            job.toRecordCommand(
                eventType = AnalyticsEventType.DOWNLOAD_FAILED,
                success = false,
                errorCode = errorCode,
                properties = mapOf("errorMessage" to errorMessage.take(MAX_PROPERTY_VALUE_LENGTH)),
            )
        )
    }

    private fun DownloadJob.toRecordCommand(
        eventType: AnalyticsEventType,
        platform: String? = sourceExtractor,
        sourceDurationSeconds: Int? = this.sourceDurationSeconds,
        downloadedFileSize: Long? = this.downloadedFileSize,
        telegramFileSize: Long? = this.telegramFileSize,
        success: Boolean? = null,
        errorCode: String? = null,
        properties: Map<String, Any?> = emptyMap(),
    ): RecordAnalyticsEventCommand {
        return RecordAnalyticsEventCommand(
            eventType = eventType,
            jobId = requiredId(),
            telegramUserId = telegramUserId,
            telegramChatId = telegramChatId,
            platform = platform,
            outputType = outputType,
            cacheKey = cacheKey,
            sourceDurationSeconds = sourceDurationSeconds,
            downloadedFileSize = downloadedFileSize,
            telegramFileSize = telegramFileSize,
            success = success,
            errorCode = errorCode,
            properties = properties,
        )
    }

    private companion object {
        private const val MAX_PROPERTY_VALUE_LENGTH = 500
    }
}
