package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.service.DownloadJobLeaseLostException
import com.nkudrin713.kradnik.download.service.DownloadJobService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID
import kotlin.math.max

/** Claims one job per tick and renews its lease while processing remains active. */
@Component
@ConditionalOnProperty(
    name = ["download.worker.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class DownloadQueueWorker(
    private val downloadJobService: DownloadJobService,
    private val downloadJobProcessor: DownloadJobProcessor,
    @Value("\${download.worker-lease-duration-ms:300000}")
    private val workerLeaseDurationMs: Long,
) {
    init {
        require(workerLeaseDurationMs > 0) { "download.worker-lease-duration-ms must be positive" }
    }

    @Scheduled(fixedDelayString = "\${download.worker-delay-ms:1000}")
    fun processNextJob() {
        recoverExpiredLeases()

        val leaseToken = UUID.randomUUID()
        val attempt = downloadJobService.claimNextQueuedJob(
            leaseToken = leaseToken,
            leaseDurationMs = workerLeaseDurationMs,
        ) ?: return

        runBlocking {
            val heartbeat = launch(Dispatchers.IO) {
                while (isActive) {
                    delay(heartbeatIntervalMs())
                    val renewed = downloadJobService.renewLease(
                        jobId = attempt.requiredId(),
                        leaseToken = leaseToken,
                        leaseDurationMs = workerLeaseDurationMs,
                    )
                    if (!renewed) {
                        throw DownloadJobLeaseLostException(attempt.requiredId())
                    }
                }
            }
            try {
                downloadJobProcessor.process(attempt)
            } finally {
                heartbeat.cancelAndJoin()
            }
        }
    }

    private fun recoverExpiredLeases() {
        downloadJobService.recoverExpiredLeases()
    }

    private fun heartbeatIntervalMs(): Long {
        return max(1, workerLeaseDurationMs / 3)
    }
}
