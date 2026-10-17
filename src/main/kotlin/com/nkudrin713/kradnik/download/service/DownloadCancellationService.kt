package com.nkudrin713.kradnik.download.service

import com.nkudrin713.kradnik.download.delivery.MessageAddress
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadJobStatus
import org.springframework.stereotype.Service

@Service
class DownloadCancellationService(
    private val downloadJobService: DownloadJobService,
    private val activeDownloads: ActiveDownloadRegistry,
) {
    fun cancel(jobId: Long, telegramUserId: Long, address: MessageAddress): DownloadCancellation {
        val job = downloadJobService.findJob(jobId) ?: return DownloadCancellation.Invalid
        if (job.telegramUserId != telegramUserId) return DownloadCancellation.NotOwner
        if (!job.hasAddress(address)) return DownloadCancellation.Invalid
        if (!downloadJobService.cancelByUser(jobId, telegramUserId)) {
            val status = downloadJobService.findJob(jobId)?.status
            return if (status == DownloadJobStatus.CANCELLED_BY_USER) {
                DownloadCancellation.AlreadyCancelled(job)
            } else {
                DownloadCancellation.AlreadyFinished
            }
        }
        activeDownloads.cancel(jobId)
        return DownloadCancellation.Cancelled(job)
    }

    fun cancelledJob(jobId: Long, telegramUserId: Long, address: MessageAddress): DownloadJob? {
        val job = downloadJobService.findJob(jobId) ?: return null
        return job.takeIf {
            it.telegramUserId == telegramUserId &&
                it.status == DownloadJobStatus.CANCELLED_BY_USER &&
                it.hasAddress(address)
        }
    }

    private fun DownloadJob.hasAddress(address: MessageAddress): Boolean = when (address) {
        is MessageAddress.Chat ->
            telegramInlineMessageId == null &&
                telegramChatId == address.chatId &&
                telegramStatusMessageId == address.messageId

        is MessageAddress.Inline -> telegramInlineMessageId == address.inlineMessageId
    }
}

sealed interface DownloadCancellation {
    data class Cancelled(val job: DownloadJob) : DownloadCancellation
    data class AlreadyCancelled(val job: DownloadJob) : DownloadCancellation
    data object AlreadyFinished : DownloadCancellation
    data object NotOwner : DownloadCancellation
    data object Invalid : DownloadCancellation
}
