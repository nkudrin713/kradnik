package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.service.ClaimedDownloadJob
import com.nkudrin713.kradnik.download.service.DownloadJobRecoveryResult
import com.nkudrin713.kradnik.download.service.DownloadJobService
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlin.test.Test

class DownloadQueueWorkerTest {
    private val downloadJobService: DownloadJobService = mockk()
    private val downloadJobProcessor: DownloadJobProcessor = mockk()

    @Test
    fun processesClaimedJob() {
        val job = DownloadJob(id = 1)
        val attempt = attempt(job)
        every { downloadJobService.recoverExpiredLeases() } returns DownloadJobRecoveryResult(0, 0)
        every { downloadJobService.claimNextQueuedJob(any(), any()) } returns attempt
        coEveryProcess(attempt)

        worker().processNextJob()

        verify { downloadJobService.claimNextQueuedJob(any(), any()) }
        coVerify { downloadJobProcessor.process(attempt) }
    }

    @Test
    fun returnsWhenQueueIsEmpty() {
        every { downloadJobService.recoverExpiredLeases() } returns DownloadJobRecoveryResult(0, 0)
        every { downloadJobService.claimNextQueuedJob(any(), any()) } returns null

        worker().processNextJob()

        verify { downloadJobService.claimNextQueuedJob(any(), any()) }
    }

    private fun worker(): DownloadQueueWorker {
        return DownloadQueueWorker(
            downloadJobService = downloadJobService,
            downloadJobProcessor = downloadJobProcessor,
            workerLeaseDurationMs = 1000,
        )
    }

    private fun coEveryProcess(attempt: ClaimedDownloadJob) {
        io.mockk.coEvery { downloadJobProcessor.process(attempt) } returns Unit
    }

    private fun attempt(job: DownloadJob): ClaimedDownloadJob =
        ClaimedDownloadJob(job, UUID.randomUUID())
}
