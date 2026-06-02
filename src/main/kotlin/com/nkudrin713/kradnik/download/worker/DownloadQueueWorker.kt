package com.nkudrin713.kradnik.download.worker

import com.nkudrin713.kradnik.download.pipeline.DownloadPipeline
import com.nkudrin713.kradnik.download.service.DownloadTaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.beans.factory.DisposableBean

@Service
@ConditionalOnProperty(
    name = ["download.worker.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class DownloadQueueWorker(
    private val downloadTaskService: DownloadTaskService,
    private val downloadPipeline: DownloadPipeline,
    private val listenerProvider: ObjectProvider<DownloadQueueListener>,
    @Value("\${download.worker.concurrency:2}")
    concurrency: Int,
    @Value("\${download.worker.poll-interval-ms:5000}")
    private val pollIntervalMs: Long,
) : ApplicationRunner, DisposableBean {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val drainMutex = Mutex()
    private val semaphore = Semaphore(concurrency.coerceAtLeast(1))

    override fun run(args: ApplicationArguments) {
        scope.launch {
            drainQueue()
        }

        scope.launch {
            pollQueue()
        }

        listenerProvider.ifAvailable { listener ->
            scope.launch {
                try {
                    listener.listen {
                        drainQueue()
                    }
                } catch (e: RuntimeException) {
                    logger.error("Download queue listener failed", e)
                }
            }
        }
    }

    private suspend fun pollQueue() {
        while (currentCoroutineContext().isActive) {
            delay(pollIntervalMs.coerceAtLeast(100))
            drainQueue()
        }
    }

    private suspend fun drainQueue() {
        drainMutex.withLock {
            while (currentCoroutineContext().isActive) {
                semaphore.acquire()

                val task = downloadTaskService.claimNextQueuedTask()
                val taskId = task?.id

                if (taskId == null) {
                    semaphore.release()
                    return
                }
                logger.info("CHAT[{}] TASK[{}] claimed", task.telegramChatId, taskId)

                scope.launch {
                    try {
                        downloadPipeline.processTask(taskId)
                    } catch (e: RuntimeException) {
                        logger.error("Download task failed: taskId={}", taskId, e)
                    } finally {
                        semaphore.release()
                    }
                }
            }
        }
    }

    override fun destroy() {
        scope.cancel()
    }
}
