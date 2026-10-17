package com.nkudrin713.kradnik.download.service

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadJobStatus
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import java.util.Optional

/** Persistence operations used by job scenarios; locks participate in the caller's short transaction. */
interface JobStore {
    fun save(job: DownloadJob): DownloadJob
    fun findById(jobId: Long): Optional<DownloadJob>
    fun findByTelegramUpdateId(telegramUpdateId: Int): DownloadJob?
    fun findByRetryOfJobId(jobId: Long): DownloadJob?
    fun findForUpdate(jobId: Long): DownloadJob?
    fun lockTelegramUpdate(telegramUpdateId: Int): Int
    fun lockQueueClaims(): Int
    fun claimNextQueuedJob(): DownloadJob?
    fun claimNextQueuedPlaylistJob(): DownloadJob?
    fun queuePosition(jobId: Long): Long?
    fun findAllByStatusOrderByCreatedAtAscIdAsc(status: DownloadJobStatus): List<DownloadJob>
    fun requeueProcessingJobs(): Int
    fun complete(jobId: Long, receipt: String): Int
    fun fail(jobId: Long, error: String): Int
    fun cancelByUser(jobId: Long, userId: Long): Int
    fun savePlaylistResult(jobId: Long, result: PlaylistAudioResult): Boolean
    fun savePlaylistFailures(jobId: Long, failures: List<PlaylistAudioResult>): Boolean
}
