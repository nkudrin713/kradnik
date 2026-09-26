package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.service.DownloadJobService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
) {
    init {
        require(workers > 0) { "download.workers must be positive" }
        require(pollDelayMs > 0) { "download.worker-delay-ms must be positive" }
    }

    private val logger = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newFixedThreadPool(workers) { task ->
        Thread(task, "download-worker")
    }

    /**
     * Cleans interrupted workspaces and recovers persisted jobs before starting the fixed worker pool.
     * Each worker claims its next job only after the previous job has finished.
     */
    @PostConstruct
    fun start() {
        workDirCleaner.cleanInterruptedJobs()
        val recovered = downloadJobService.recoverInterruptedJobs()
        logger.info("Starting {} download workers; recovered {} interrupted jobs", workers, recovered)
        repeat(workers) { executor.execute(::work) }
    }

    private fun work() {
        while (!executor.isShutdown && !Thread.currentThread().isInterrupted) {
            try {
                val job = downloadJobService.claimNextQueuedJob()
                if (job == null) {
                    Thread.sleep(pollDelayMs)
                } else {
                    // Blocking bridge to the existing suspend-based external I/O adapters.
                    runBlocking {
                        activeDownloads.run(job.requiredId()) {
                            if (downloadJobService.isProcessing(job.requiredId())) {
                                downloadJobProcessor.process(job)
                            }
                        }
                    }
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (error: Exception) {
                logger.error("Download worker iteration failed", error)
                try {
                    Thread.sleep(pollDelayMs)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    /**
     * Interrupts polling and active coroutine bridges, then waits up to 30 seconds for workers to exit.
     * Logs a timeout and preserves interruption of the shutdown thread.
     */
    @PreDestroy
    fun shutdown() {
        // Interrupts polling and runBlocking; process adapters terminate child processes in finally.
        executor.shutdownNow()
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warn("Download workers did not stop within 30 seconds")
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
