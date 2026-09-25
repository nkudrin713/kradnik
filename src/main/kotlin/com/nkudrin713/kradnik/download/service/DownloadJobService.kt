package com.nkudrin713.kradnik.download.service

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadJobStatus
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.repository.DownloadJobRepository
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
				telegramStatusMessageId = command.telegramStatusMessageId,
				telegramInlineMessageId = command.telegramInlineMessageId,
			)
		)
	}

    @Transactional
    fun claimNextQueuedJob(): DownloadJob? = downloadJobRepository.claimNextQueuedJob()

    @Transactional
    fun claimNextQueuedPlaylistJob(): DownloadJob? = downloadJobRepository.claimNextQueuedPlaylistJob()

    /** Called once before workers start. The previous application instance must already be stopped. */
    @Transactional
    fun recoverInterruptedJobs(): Int = downloadJobRepository.requeueProcessingJobs()

    @Transactional(readOnly = true)
    fun findCachedJob(job: DownloadJob): DownloadJob? =
        downloadJobRepository.findCachedCompletedJob(job.cacheKey)

    @Transactional(readOnly = true)
    fun findCachedFileId(cacheKey: String): String? =
        downloadJobRepository.findCachedCompletedJob(cacheKey)?.telegramFileId

    @Transactional
    fun markCompleted(job: DownloadJob, telegramFileId: String): Boolean =
        downloadJobRepository.complete(job.requiredId(), telegramFileId) == 1

    @Transactional
    fun markFailed(job: DownloadJob, errorMessage: String): Boolean =
        downloadJobRepository.fail(job.requiredId(), errorMessage.take(1000)) == 1

    @Transactional
    fun cancelByUser(jobId: Long, telegramUserId: Long): Boolean =
        downloadJobRepository.cancelByUser(jobId, telegramUserId) == 1

    @Transactional(readOnly = true)
    fun findJob(jobId: Long): DownloadJob? = downloadJobRepository.findById(jobId).orElse(null)

    @Transactional(readOnly = true)
    fun isProcessing(jobId: Long): Boolean =
        downloadJobRepository.findById(jobId).orElse(null)?.status == DownloadJobStatus.PROCESSING

    @Transactional(readOnly = true)
    fun isCancelledByUser(jobId: Long): Boolean =
        downloadJobRepository.findById(jobId).orElse(null)?.status == DownloadJobStatus.CANCELLED_BY_USER

    @Transactional
    fun savePlaylistResult(jobId: Long, result: PlaylistAudioResult): Boolean {
        val job = downloadJobRepository.findForUpdate(jobId) ?: return false
        if (job.status != DownloadJobStatus.PROCESSING) return false
        if (job.playlistResults.any { it.position == result.position }) return true
        job.playlistResults = job.playlistResults + result
        return true
    }
}

data class CreateDownloadJobCommand(
	val telegramUserId: Long,
	val telegramChatId: Long,
	val telegramUpdateId: Int? = null,
	val telegramRequestMessageId: Int? = null,
	val language: BotLanguage = BotLanguage.EN,
	val spec: DownloadSpec,
	val telegramStatusMessageId: Int? = null,
	val telegramInlineMessageId: String? = null,
)
