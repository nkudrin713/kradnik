package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.telegram.TelegramJobProgress
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class JobLifecycle(private val jobs: DownloadJobService, private val progress: TelegramJobProgress) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun complete(job: DownloadJob, receipt: String): Boolean {
        if (!jobs.markCompleted(job, receipt)) return false
        logger.info("JOB[{}] completed", job.id)
        progress.completed(job)
        return true
    }

    fun fail(job: DownloadJob, error: Exception): Boolean {
        if (!jobs.markFailed(job, error.message ?: error.javaClass.simpleName)) return false
        progress.failed(job, (error as? DownloadFailure)?.reason)
        return true
    }

    fun isUserCancelled(job: DownloadJob): Boolean = jobs.isCancelledByUser(job.requiredId())
}
