package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.observability.BotTelemetry
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** N long-lived loops: each claims only when free, so waiting jobs remain in PostgreSQL. */
@Component
@ConditionalOnProperty(name = ["download.worker.enabled"], havingValue = "true", matchIfMissing = true)
class DownloadQueueWorker(
    private val downloadJobService: DownloadJobService,
    private val downloadJobProcessor: DownloadJobProcessor,
    private val activeDownloads: ActiveDownloadRegistry,
    private val workDirCleaner: WorkDirCleaner,
    @Value($$"${download.workers:3}") private val workers: Int,
    @Value($$"${download.worker-delay-ms:1000}") private val pollDelayMs: Long = 1000,
    private val runtime: BotTelemetry = BotTelemetry.NONE,
) {
    init {
        require(workers > 0) { "download.workers must be positive" }
        require(pollDelayMs > 0) { "download.worker-delay-ms must be positive" }
    }

    private val logger = LoggerFactory.getLogger(javaClass)
    private val pool = DownloadWorkerPool("download", workers, pollDelayMs, activeDownloads, runtime)

    /**
     * Cleans interrupted workspaces and recovers persisted jobs before starting the fixed worker pool.
     * Each worker claims its next job only after the previous job has finished.
     */
    @PostConstruct
    fun start() {
        workDirCleaner.cleanInterruptedJobs()
        val recovered = downloadJobService.recoverInterruptedJobs()
        logger.info("Starting {} download workers; recovered {} interrupted jobs", workers, recovered)
        pool.start(downloadJobService::claimNextQueuedJob, downloadJobService::isProcessing, downloadJobProcessor::process)
    }

    /**
     * Interrupts polling and active coroutine bridges, then waits up to 30 seconds for workers to exit.
     * Logs a timeout and preserves interruption of the shutdown thread.
     */
    @PreDestroy
    fun shutdown() = pool.shutdown()
}
