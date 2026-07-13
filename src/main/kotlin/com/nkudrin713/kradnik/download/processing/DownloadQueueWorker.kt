package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.service.DownloadJobService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID
import kotlin.math.max

@Component
@ConditionalOnProperty(
    name = ["download.worker.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class DownloadQueueWorker(
    private val downloadJobService: DownloadJobService,
    private val downloadJobProcessor: DownloadJobProcessor,
    @Value("\${download.worker-lease-duration-ms:3600000}")
    private val workerLeaseDurationMs: Long,
) {
    init {
        require(workerLeaseDurationMs > 0) { "download.worker-lease-duration-ms must be positive" }
    }

    @Scheduled(fixedDelayString = "\${download.worker-delay-ms:1000}")
    fun processNextJob() {
        recoverExpiredLeases()

        val leaseToken = UUID.randomUUID()
        val job = downloadJobService.claimNextQueuedJob(
            leaseToken = leaseToken,
            leaseExpiresAt = nextLeaseExpiration(),
        ) ?: return

        runBlocking {
            val heartbeat = launch(Dispatchers.IO) {
                while (isActive) {
                    delay(heartbeatIntervalMs())
                    val renewed = downloadJobService.renewLease(
                        jobId = requireNotNull(job.id),
                        leaseToken = leaseToken,
                        leaseExpiresAt = nextLeaseExpiration(),
                    )
                    if (!renewed) {
                        throw DownloadLeaseLostException(requireNotNull(job.id))
                    }
                }
            }
            try {
                downloadJobProcessor.process(job)
            } finally {
                heartbeat.cancelAndJoin()
            }
        }
    }

    private fun recoverExpiredLeases() {
        downloadJobService.recoverExpiredLeases(Instant.now())
    }

    private fun nextLeaseExpiration(): Instant {
        return Instant.now().plusMillis(workerLeaseDurationMs)
    }

    private fun heartbeatIntervalMs(): Long {
        return max(1, workerLeaseDurationMs / 3)
    }
}

private class DownloadLeaseLostException(jobId: Long) :
    RuntimeException("Download job lease lost: $jobId")
