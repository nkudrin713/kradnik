package com.nkudrin713.kradnik.download.worker

import com.nkudrin713.kradnik.download.domain.DownloadTask
import com.nkudrin713.kradnik.download.pipeline.DownloadPipeline
import com.nkudrin713.kradnik.download.service.DownloadTaskService
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.mockito.Mockito.never
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.support.DefaultListableBeanFactory

class DownloadQueueWorkerTest {
    private val downloadTaskService: DownloadTaskService = mock()
    private val downloadPipeline: DownloadPipeline = mock()

    @Test
    fun `claims queued task and starts pipeline`() = runTest {
        whenever(downloadTaskService.claimNextQueuedTask())
            .thenReturn(DownloadTask(id = 1))
            .thenReturn(null)

        val beanFactory = DefaultListableBeanFactory()
        val worker = DownloadQueueWorker(
            downloadTaskService = downloadTaskService,
            downloadPipeline = downloadPipeline,
            listenerProvider = beanFactory.getBeanProvider(DownloadQueueListener::class.java),
            concurrency = 1,
        )

        try {
            worker.run(mock())

            withTimeout(1_000) {
                while (true) {
                    try {
                        verify(downloadPipeline).processTask(1)
                        return@withTimeout
                    } catch (_: AssertionError) {
                        delay(10)
                    }
                }
            }
        } finally {
            worker.destroy()
        }
    }

    @Test
    fun `does nothing when queue is empty`() = runTest {
        whenever(downloadTaskService.claimNextQueuedTask()).thenReturn(null)

        val beanFactory = DefaultListableBeanFactory()
        val worker = DownloadQueueWorker(
            downloadTaskService = downloadTaskService,
            downloadPipeline = downloadPipeline,
            listenerProvider = beanFactory.getBeanProvider(DownloadQueueListener::class.java),
            concurrency = 1,
        )

        try {
            worker.run(mock())
            delay(100)

            verify(downloadPipeline, never()).processTask(any())
        } finally {
            worker.destroy()
        }
    }
}
