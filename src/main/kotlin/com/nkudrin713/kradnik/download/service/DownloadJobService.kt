package com.nkudrin713.kradnik.download.service

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadJobStatus
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.domain.PlaylistDeliveryMode
import com.nkudrin713.kradnik.download.playlist.PlaylistRetrySelection
import com.nkudrin713.kradnik.download.repository.DownloadJobRepository
import com.nkudrin713.kradnik.download.repository.StringListJsonConverter
import com.nkudrin713.kradnik.download.source.SourceRequest
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Short database transactions; external calls always happen after these methods return. */
@Service
class DownloadJobService(private val downloadJobRepository: DownloadJobRepository) {
    /** Locks a Telegram update identity and persists at most one [DownloadJob] for a non-null update ID. */
    @Transactional
    fun createJob(command: CreateDownloadJobCommand): DownloadJob? {
        command.telegramUpdateId?.let { telegramUpdateId ->
            downloadJobRepository.lockTelegramUpdate(telegramUpdateId)
            if (downloadJobRepository.findByTelegramUpdateId(telegramUpdateId) != null) {
                return null
            }
        }

        val spec = command.spec
        return downloadJobRepository.save(
            DownloadJob(
                telegramUserId = command.telegramUserId,
                telegramChatId = command.telegramChatId,
                telegramUpdateId = command.telegramUpdateId,
                retryOfJobId = command.retryOfJobId,
                telegramRequestMessageId = command.telegramRequestMessageId,
                language = command.language,
                originalUrl = spec.originalUrl,
                normalizedUrl = spec.normalizedUrl,
                cacheKey = spec.cacheKey,
                outputType = spec.outputType,
                platform = spec.platform,
                downloadPreset = spec.presetName,
                selectedFormat = spec.formatSelector,
                downloadExtraArgs = spec.extraArgs,
                sourcePostText = spec.postText,
                workloadType = spec.workloadType,
                playlistEntries = spec.playlistEntries,
                playlistDeliveryMode = spec.playlistDeliveryMode,
                playlistTitle = spec.playlistTitle,
                telegramStatusMessageId = command.telegramStatusMessageId,
                telegramInlineMessageId = command.telegramInlineMessageId,
            ),
        )
    }

    @Transactional(readOnly = true)
    fun retrySource(jobId: Long, telegramUserId: Long, telegramChatId: Long): DownloadJob? {
        val job = downloadJobRepository.findById(jobId).orElse(null) ?: return null
        return job.takeIf { it.canRetry(telegramUserId, telegramChatId) }
    }

    /** Locks the source job so repeated callbacks cannot create duplicate retry jobs. */
    @Transactional
    fun createRetryJob(
        sourceJobId: Long,
        telegramUserId: Long,
        telegramChatId: Long,
        telegramUpdateId: Int,
        telegramStatusMessageId: Int,
        deliveryMode: PlaylistDeliveryMode,
    ): DownloadJob? {
        val source = downloadJobRepository.findForUpdate(sourceJobId) ?: return null
        if (!source.canRetry(telegramUserId, telegramChatId)) return null
        if (deliveryMode == PlaylistDeliveryMode.ZIP && source.playlistDeliveryMode != PlaylistDeliveryMode.ZIP) return null
        if (downloadJobRepository.findByRetryOfJobId(sourceJobId) != null) return null
        val entries = PlaylistRetrySelection.entries(source.playlistEntries, source.playlistResults)
        val spec = DownloadSpec.fromJob(source).copy(
            cacheKey = "${source.cacheKey}:retry:$sourceJobId",
            playlistEntries = entries,
            playlistDeliveryMode = deliveryMode,
        )
        return createJob(
            CreateDownloadJobCommand(
                telegramUserId = telegramUserId,
                telegramChatId = telegramChatId,
                telegramUpdateId = telegramUpdateId,
                retryOfJobId = sourceJobId,
                telegramRequestMessageId = source.telegramRequestMessageId,
                language = source.language,
                spec = spec,
                telegramStatusMessageId = telegramStatusMessageId,
            ),
        )
    }

    @Transactional
    fun claimNextQueuedJob(): DownloadJob? {
        downloadJobRepository.lockQueueClaims()
        return downloadJobRepository.claimNextQueuedJob()
    }

    @Transactional
    fun claimNextQueuedPlaylistJob(): DownloadJob? {
        downloadJobRepository.lockQueueClaims()
        return downloadJobRepository.claimNextQueuedPlaylistJob()
    }

    @Transactional(readOnly = true)
    fun queuePosition(jobId: Long): Long? = downloadJobRepository.queuePosition(jobId)

    /** Called once before workers start. The previous application instance must already be stopped. */
    @Transactional
    fun recoverInterruptedJobs(): Int = downloadJobRepository.requeueProcessingJobs()

    @Transactional(readOnly = true)
    fun findCachedFileId(cacheKey: String): String? = downloadJobRepository.findCachedCompletedJob(cacheKey)?.telegramFileId

    @Transactional(readOnly = true)
    fun findCachedPlaylistFile(videoId: String, source: SourceRequest): String? = downloadJobRepository.findCachedPlaylistFile(
        videoId,
        source.presetName,
        source.formatSelector,
        StringListJsonConverter().convertToDatabaseColumn(source.extraArgs),
    )

    @Transactional
    fun markCompleted(job: DownloadJob, telegramFileId: String): Boolean = downloadJobRepository.complete(job.requiredId(), telegramFileId) == 1

    @Transactional
    fun markFailed(job: DownloadJob, errorMessage: String): Boolean = downloadJobRepository.fail(job.requiredId(), errorMessage.take(1000)) == 1

    @Transactional
    fun cancelByUser(jobId: Long, telegramUserId: Long): Boolean = downloadJobRepository.cancelByUser(jobId, telegramUserId) == 1

    @Transactional(readOnly = true)
    fun findJob(jobId: Long): DownloadJob? = downloadJobRepository.findById(jobId).orElse(null)

    @Transactional(readOnly = true)
    fun isProcessing(jobId: Long): Boolean = downloadJobRepository.findById(jobId).orElse(null)?.status == DownloadJobStatus.PROCESSING

    @Transactional(readOnly = true)
    fun isQueued(jobId: Long): Boolean = downloadJobRepository.findById(jobId).orElse(null)?.status == DownloadJobStatus.QUEUED

    @Transactional(readOnly = true)
    fun isCancelledByUser(jobId: Long): Boolean = downloadJobRepository.findById(jobId).orElse(null)?.status == DownloadJobStatus.CANCELLED_BY_USER

    @Transactional
    fun savePlaylistResult(jobId: Long, result: PlaylistAudioResult): Boolean {
        val job = downloadJobRepository.findForUpdate(jobId) ?: return false
        if (job.status != DownloadJobStatus.PROCESSING) return false
        if (job.playlistResults.any { it.position == result.position }) return true
        job.playlistResults = job.playlistResults + result
        return true
    }

    @Transactional
    fun savePlaylistFailures(jobId: Long, failures: List<PlaylistAudioResult>): Boolean {
        val job = downloadJobRepository.findForUpdate(jobId) ?: return false
        if (job.status != DownloadJobStatus.PROCESSING) return false
        job.playlistResults = failures.filter { it.fileId == null }
        return true
    }

    private fun DownloadJob.canRetry(telegramUserId: Long, telegramChatId: Long): Boolean {
        return this.telegramUserId == telegramUserId &&
            this.telegramChatId == telegramChatId &&
            telegramInlineMessageId == null &&
            workloadType == DownloadWorkloadType.PLAYLIST_AUDIO &&
            status in setOf(DownloadJobStatus.COMPLETED, DownloadJobStatus.FAILED) &&
            PlaylistRetrySelection.entries(playlistEntries, playlistResults).isNotEmpty()
    }
}

data class CreateDownloadJobCommand(
    val telegramUserId: Long,
    val telegramChatId: Long,
    val telegramUpdateId: Int? = null,
    val retryOfJobId: Long? = null,
    val telegramRequestMessageId: Int? = null,
    val language: BotLanguage = BotLanguage.EN,
    val spec: DownloadSpec,
    val telegramStatusMessageId: Int? = null,
    val telegramInlineMessageId: String? = null,
)
