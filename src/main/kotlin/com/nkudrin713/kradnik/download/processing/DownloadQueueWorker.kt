package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.observability.BotTelemetry
import com.nkudrin713.kradnik.observability.RuntimeError
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
    private val runtime: BotTelemetry = BotTelemetry.NONE,
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
        repeat(workers) { index ->
            val id = "download-${index + 1}"
            runtime.register(id, "download")
            executor.execute { work(id) }
        }
    }

    private fun work(id: String) {
        try {
            while (!executor.isShutdown && !Thread.currentThread().isInterrupted) {
                try {
                    runtime.state(id, "IDLE")
                    val job = downloadJobService.claimNextQueuedJob()
                    if (job == null) {
                        Thread.sleep(pollDelayMs)
                    } else {
                        runtime.busy(id, job.requiredId(), job.platform.name)
                        try {
                            runBlocking {
                                activeDownloads.run(job.requiredId()) {
                                    if (downloadJobService.isProcessing(job.requiredId())) {
                                        downloadJobProcessor.process(job)
                                    }
                                }
                            }
                        } finally {
                            runtime.state(id, "IDLE")
                        }
                    }
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                } catch (error: Exception) {
                    runtime.error(RuntimeError.WORKER)
                    runtime.state(id, "BACKOFF")
                    logger.error("Download worker iteration failed", error)
                    try {
                        Thread.sleep(pollDelayMs)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
            }
        } finally {
            runtime.state(id, "STOPPED")
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
