package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.admin.AdminRuntime
import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.service.DownloadJobService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadQueueWorkerTest {
    private val jobs = mockk<DownloadJobService>(relaxed = true)
    private val processor = mockk<DownloadJobProcessor>()
    private val cleaner = mockk<WorkDirCleaner>(relaxed = true)
    private val activeDownloads = ActiveDownloadRegistry()
    private val runtime = AdminRuntime(true)

    @Test
    fun threeJobsRunTogetherAndRemainingJobsStayInDatabaseUntilCapacityIsFree() {
        val queue = ConcurrentLinkedQueue((1L..100L).map { DownloadJob(id = it) })
        val started = CountDownLatch(3)
        val fourth = CountDownLatch(1)
        val finish = Semaphore(0)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val claimed = AtomicInteger()
        val seen = ConcurrentHashMap.newKeySet<Long>()
        every { jobs.claimNextQueuedJob() } answers {
            claimed.incrementAndGet()
            queue.poll()
        }
        every { jobs.isProcessing(any()) } returns true
        coEvery { processor.process(any()) } coAnswers {
            val job = firstArg<DownloadJob>()
            assertTrue(seen.add(job.requiredId()))
            val current = active.incrementAndGet()
            maximum.accumulateAndGet(current, ::maxOf)
            started.countDown()
            if (seen.size >= 4) fourth.countDown()
            try {
                finish.acquire()
            } finally {
                active.decrementAndGet()
            }
        }
        val worker = DownloadQueueWorker(jobs, processor, activeDownloads, cleaner, workers = 3, pollDelayMs = 1, runtime = runtime)
        try {
            worker.start()
            assertTrue(started.await(10, TimeUnit.SECONDS))
            assertEquals(3, active.get())
            assertEquals(3, runtime.snapshot().workers.count { it.state == "BUSY" })
            assertEquals(3, claimed.get())
            assertEquals(97, queue.size)
            finish.release()
            assertTrue(fourth.await(10, TimeUnit.SECONDS))
            assertEquals(96, queue.size)
            assertEquals(3, maximum.get())
        } finally {
            worker.shutdown()
        }
        assertEquals(0, active.get())
        assertTrue(runtime.snapshot().workers.all { it.state == "STOPPED" && it.jobId == null })
        verifyOrder {
            cleaner.cleanInterruptedJobs()
            jobs.recoverInterruptedJobs()
            jobs.claimNextQueuedJob()
        }
    }

    @Test
    fun failedIterationDoesNotKillWorkerAndStartupRecoveryRunsFirst() {
        val calls = AtomicInteger()
        every { jobs.claimNextQueuedJob() } answers {
            when (calls.incrementAndGet()) {
                1 -> throw IllegalStateException("database temporarily unavailable")
                2 -> DownloadJob(id = 1)
                3 -> DownloadJob(id = 2)
                else -> null
            }
        }
        every { jobs.isProcessing(any()) } returns true
        val processed = CountDownLatch(1)
        coEvery { processor.process(match { it.id == 1L }) } throws IllegalStateException("unexpected failure")
        coEvery { processor.process(match { it.id == 2L }) } coAnswers { processed.countDown() }
        val worker = DownloadQueueWorker(jobs, processor, activeDownloads, cleaner, workers = 1, pollDelayMs = 1, runtime = runtime)
        try {
            worker.start()
            assertTrue(processed.await(10, TimeUnit.SECONDS))
        } finally {
            worker.shutdown()
        }
        assertEquals(2L, runtime.snapshot().errors.first().counts["WORKER"])
        assertTrue(runtime.snapshot().workers.all { it.state == "STOPPED" })
    }
}
