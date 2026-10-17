package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.observability.BotTelemetry
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Component

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

    init {
        require(workers > 0) { "download.playlist-workers must be positive" }
        require(pollDelayMs > 0) { "download.worker-delay-ms must be positive" }
    }

    private val pool = DownloadWorkerPool("playlist", workers, pollDelayMs, activeDownloads, runtime)

    /**
     * Starts the dedicated playlist worker pool after [DownloadQueueWorker] has performed startup recovery.
     * Each worker processes one claimed playlist at a time; per-item parallelism belongs to its workflow.
     */
    @PostConstruct
    fun start() {
        logger.info("Starting {} playlist workers", workers)
        pool.start(downloadJobService::claimNextQueuedPlaylistJob, downloadJobService::isProcessing, playlistJobProcessor::process)
    }

    /**
     * Interrupts polling and active playlist processing, then waits up to 30 seconds for workers to exit.
     * Logs a timeout and preserves interruption of the shutdown thread.
     */
    @PreDestroy
    fun shutdown() = pool.shutdown()
}
