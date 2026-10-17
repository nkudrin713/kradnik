package com.nkudrin713.kradnik.download.progress

import com.nkudrin713.kradnik.download.delivery.PlaylistDeliveryResult
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.DownloadJob

/** Presentation of execution events; implementations must keep notification failures from failing a job. */
interface JobNotifications {
    fun forJob(job: DownloadJob): JobProgress
    fun completed(job: DownloadJob)
    fun failed(job: DownloadJob, reason: DownloadFailureReason?)
    fun playlistSummary(job: DownloadJob, result: PlaylistDeliveryResult)
    fun playlistFailure(job: DownloadJob, error: Exception)
}
