package com.nkudrin713.kradnik.download

import com.nkudrin713.kradnik.download.service.DownloadJobService
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
@ConditionalOnProperty(
    name = ["download.worker.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class DownloadQueueWorker(
    private val downloadJobService: DownloadJobService,
    private val downloadJobProcessor: DownloadJobProcessor,
    @Value("\${download.worker-stale-timeout-ms:3600000}")
    private val workerStaleTimeoutMs: Long,
) {

    @Scheduled(fixedDelayString = "\${download.worker-delay-ms:1000}")
    fun processNextJob() {
        recoverStaleJobs()

        val job = downloadJobService.claimNextQueuedJob() ?: return

        runBlocking {
            downloadJobProcessor.process(job)
        }
    }

    private fun recoverStaleJobs() {
        val staleBefore = Instant.now().minusMillis(workerStaleTimeoutMs)
        downloadJobService.recoverStaleInProgressJobs(staleBefore)
    }
}
