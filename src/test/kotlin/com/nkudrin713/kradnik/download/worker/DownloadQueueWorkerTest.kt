package com.nkudrin713.kradnik.download.worker

import com.nkudrin713.kradnik.download.domain.DownloadTask
import com.nkudrin713.kradnik.download.pipeline.DownloadPipeline
import com.nkudrin713.kradnik.download.service.DownloadTaskService
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import kotlin.time.Duration.Companion.milliseconds

class DownloadQueueWorkerTest {
    private val downloadTaskService: DownloadTaskService = mockk()
    private val downloadPipeline: DownloadPipeline = mockk()

    @Test
    fun `claims queued task and starts pipeline`() = runTest {
        every { downloadTaskService.claimNextQueuedTask() } returnsMany listOf(DownloadTask(id = 1), null)
        coEvery { downloadPipeline.processTask(1) } returns Unit

        val beanFactory = DefaultListableBeanFactory()
        val worker = DownloadQueueWorker(
            downloadTaskService = downloadTaskService,
            downloadPipeline = downloadPipeline,
            listenerProvider = beanFactory.getBeanProvider(DownloadQueueListener::class.java),
            concurrency = 1,
            pollIntervalMs = 5_000,
        )

        try {
            worker.run(mockk())

            withTimeout(1_000.milliseconds) {
                while (true) {
                    try {
                        coVerify { downloadPipeline.processTask(1) }
                        return@withTimeout
                    } catch (_: AssertionError) {
                        delay(10.milliseconds)
                    }
                }
            }
        } finally {
            worker.destroy()
        }
    }

    @Test
    fun `does nothing when queue is empty`() = runTest {
        every { downloadTaskService.claimNextQueuedTask() } returns null

        val beanFactory = DefaultListableBeanFactory()
        val worker = DownloadQueueWorker(
            downloadTaskService = downloadTaskService,
            downloadPipeline = downloadPipeline,
            listenerProvider = beanFactory.getBeanProvider(DownloadQueueListener::class.java),
            concurrency = 1,
            pollIntervalMs = 5_000,
        )

        try {
            worker.run(mockk())
            delay(100.milliseconds)

            coVerify(exactly = 0) { downloadPipeline.processTask(any()) }
        } finally {
            worker.destroy()
        }
    }
}
