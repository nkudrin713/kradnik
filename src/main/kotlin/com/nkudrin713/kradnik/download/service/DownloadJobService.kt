package com.nkudrin713.kradnik.download.service

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.repository.DownloadJobRepository
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Short database transactions; external calls always happen after these methods return. */
@Service
class DownloadJobService(private val downloadJobRepository: DownloadJobRepository) {
	/** Locks a Telegram update identity and persists at most one [DownloadJob] for a non-null update ID. */
	@Transactional
	fun createJob(command: CreateDownloadJobCommand): Boolean {
		command.telegramUpdateId?.let { telegramUpdateId ->
			downloadJobRepository.lockTelegramUpdate(telegramUpdateId)
			if (downloadJobRepository.findByTelegramUpdateId(telegramUpdateId) != null) {
				return false
			}
		}

		val spec = command.spec
		downloadJobRepository.save(
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
				telegramStatusMessageId = command.telegramStatusMessageId,
				telegramInlineMessageId = command.telegramInlineMessageId,
			)
		)
		return true
	}


    @Transactional
    fun claimNextQueuedJob(): DownloadJob? = downloadJobRepository.claimNextQueuedJob()

    /** Called once before workers start. The previous application instance must already be stopped. */
    @Transactional
    fun recoverInterruptedJobs(): Int = downloadJobRepository.requeueProcessingJobs()

    @Transactional(readOnly = true)
    fun findCachedJob(job: DownloadJob): DownloadJob? =
        downloadJobRepository.findCachedCompletedJob(job.cacheKey)

    @Transactional
    fun markCompleted(job: DownloadJob, telegramFileId: String) {
        check(downloadJobRepository.complete(job.requiredId(), telegramFileId) == 1) {
            "Job is no longer processing: ${job.id}"
        }
    }

    @Transactional
    fun markFailed(job: DownloadJob, errorMessage: String) {
        check(downloadJobRepository.fail(job.requiredId(), errorMessage.take(1000)) == 1) {
            "Job is no longer processing: ${job.id}"
        }
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
