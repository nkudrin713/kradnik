package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.service.ActiveDownloadRegistry
import com.nkudrin713.kradnik.observability.BotTelemetry
import com.nkudrin713.kradnik.observability.RuntimeError
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Executes claimed jobs without prefetch; queue owners perform recovery before starting the pool. */
internal class DownloadWorkerPool(
    private val category: String,
    private val workers: Int,
    private val pollDelayMs: Long,
    private val activeDownloads: ActiveDownloadRegistry,
    private val runtime: BotTelemetry,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newFixedThreadPool(workers) { task -> Thread(task, "$category-worker") }

    fun start(claim: () -> DownloadJob?, isProcessing: (Long) -> Boolean, process: suspend (DownloadJob) -> Unit) {
        repeat(workers) { index ->
            val id = "$category-${index + 1}"
            runtime.register(id, category)
            executor.execute { work(id, claim, isProcessing, process) }
        }
    }

    private fun work(id: String, claim: () -> DownloadJob?, isProcessing: (Long) -> Boolean, process: suspend (DownloadJob) -> Unit) {
        try {
            while (!executor.isShutdown && !Thread.currentThread().isInterrupted) {
                try {
                    runtime.state(id, "IDLE")
                    val job = claim()
                    if (job == null) {
                        Thread.sleep(pollDelayMs)
                    } else {
                        runtime.busy(id, job.requiredId(), job.platform.name)
                        try {
                            runBlocking {
                                activeDownloads.run(job.requiredId()) {
                                    if (isProcessing(job.requiredId())) process(job)
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
                    logger.error("{} worker iteration failed", category, error)
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

    fun shutdown() {
        executor.shutdownNow()
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warn("{} workers did not stop within 30 seconds", category)
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
