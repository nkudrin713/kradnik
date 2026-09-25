package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.service.DownloadJobService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

class PlaylistQueueWorkerTest {
    @Test
    fun claimsAndProcessesOnlyPlaylistQueue() {
        val jobs = mockk<DownloadJobService>()
        val processor = mockk<PlaylistJobProcessor>()
        val job = DownloadJob(id = 1)
        val processed = CountDownLatch(1)
        every { jobs.claimNextQueuedPlaylistJob() } returns job andThen null
        every { jobs.isProcessing(1) } returns true
        coEvery { processor.process(job) } coAnswers { processed.countDown() }
        val worker = PlaylistQueueWorker(
            jobs,
            processor,
            ActiveDownloadRegistry(),
            workers = 1,
            pollDelayMs = 1,
        )

        try {
            worker.start()
            assertTrue(processed.await(10, TimeUnit.SECONDS))
        } finally {
            worker.shutdown()
        }

        verify(atLeast = 1) { jobs.claimNextQueuedPlaylistJob() }
        verify(exactly = 0) { jobs.claimNextQueuedJob() }
    }
}
