package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.observability.BotTelemetry
import com.nkudrin713.kradnik.observability.RuntimeError
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Component
@DependsOn("downloadQueueWorker")
@ConditionalOnProperty(name = ["download.worker.enabled"], havingValue = "true", matchIfMissing = true)
class PlaylistQueueWorker(
    private val downloadJobService: DownloadJobService,
    private val playlistJobProcessor: PlaylistJobProcessor,
    private val activeDownloads: ActiveDownloadRegistry,
    @Value($$"${download.playlist-workers:1}") private val workers: Int,
    @Value($$"${download.worker-delay-ms:1000}") private val pollDelayMs: Long = 1000,
    private val runtime: BotTelemetry = BotTelemetry.NONE,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newFixedThreadPool(workers) { task -> Thread(task, "playlist-worker") }

    init {
        require(workers > 0) { "download.playlist-workers must be positive" }
        require(pollDelayMs > 0) { "download.worker-delay-ms must be positive" }
    }

    /**
     * Starts the dedicated playlist worker pool after [DownloadQueueWorker] has performed startup recovery.
     * Each worker processes one claimed playlist at a time; per-item parallelism belongs to its workflow.
     */
    @PostConstruct
    fun start() {
        logger.info("Starting {} playlist workers", workers)
        repeat(workers) { index ->
            val id = "playlist-${index + 1}"
            runtime.register(id, "playlist")
            executor.execute { work(id) }
        }
    }

    private fun work(id: String) {
        try {
            while (!executor.isShutdown && !Thread.currentThread().isInterrupted) {
                try {
                    runtime.state(id, "IDLE")
                    val job = downloadJobService.claimNextQueuedPlaylistJob()
                    if (job == null) {
                        Thread.sleep(pollDelayMs)
                    } else {
                        runtime.busy(id, job.requiredId(), job.platform.name)
                        try {
                            runBlocking {
                                activeDownloads.run(job.requiredId()) {
                                    if (downloadJobService.isProcessing(job.requiredId())) {
                                        playlistJobProcessor.process(job)
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
                    logger.error("Playlist worker iteration failed", error)
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
     * Interrupts polling and active playlist processing, then waits up to 30 seconds for workers to exit.
     * Logs a timeout and preserves interruption of the shutdown thread.
     */
    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warn("Playlist workers did not stop within 30 seconds")
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
