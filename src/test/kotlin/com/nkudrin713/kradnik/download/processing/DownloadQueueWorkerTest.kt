package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.service.DownloadJobRecoveryResult
import com.nkudrin713.kradnik.download.service.DownloadJobService
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test

class DownloadQueueWorkerTest {
    private val downloadJobService: DownloadJobService = mockk()
    private val downloadJobProcessor: DownloadJobProcessor = mockk()

    @Test
    fun processesClaimedJob() {
        val job = DownloadJob(id = 1)
        every { downloadJobService.recoverStaleInProgressJobs(any()) } returns DownloadJobRecoveryResult(0, 0)
        every { downloadJobService.claimNextQueuedJob() } returns job
        coEveryProcess(job)

        worker().processNextJob()

        verify { downloadJobService.claimNextQueuedJob() }
        coVerify { downloadJobProcessor.process(job) }
    }

    @Test
    fun returnsWhenQueueIsEmpty() {
        every { downloadJobService.recoverStaleInProgressJobs(any()) } returns DownloadJobRecoveryResult(0, 0)
        every { downloadJobService.claimNextQueuedJob() } returns null

        worker().processNextJob()

        verify { downloadJobService.claimNextQueuedJob() }
    }

    private fun worker(): DownloadQueueWorker {
        return DownloadQueueWorker(
            downloadJobService = downloadJobService,
            downloadJobProcessor = downloadJobProcessor,
            workerStaleTimeoutMs = 1000,
        )
    }

    private fun coEveryProcess(job: DownloadJob) {
        io.mockk.coEvery { downloadJobProcessor.process(job) } returns Unit
    }
}
