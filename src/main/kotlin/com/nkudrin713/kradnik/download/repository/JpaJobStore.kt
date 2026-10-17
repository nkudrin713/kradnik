package com.nkudrin713.kradnik.download.repository

import com.nkudrin713.kradnik.download.delivery.ResultCache
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadJobStatus
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.service.JobStore
import com.nkudrin713.kradnik.download.source.SourceRequest
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class JpaJobStore(private val repository: DownloadJobRepository) :
    JobStore,
    ResultCache {
    @Transactional(readOnly = true)
    override fun find(cacheKey: String): String? = repository.findCachedCompletedJob(cacheKey)?.telegramFileId

    @Transactional(readOnly = true)
    override fun findPlaylistAudio(sourceId: String, source: SourceRequest): String? = repository.findCachedPlaylistFile(sourceId, source.presetName, source.formatSelector, StringListJsonConverter().convertToDatabaseColumn(source.extraArgs))

    override fun save(job: DownloadJob): DownloadJob = repository.save(DownloadJobEntity.from(job)).toJob()
    override fun findById(jobId: Long) = repository.findById(jobId).map(DownloadJobEntity::toJob)
    override fun findByTelegramUpdateId(telegramUpdateId: Int) = repository.findByTelegramUpdateId(telegramUpdateId)?.toJob()
    override fun findByRetryOfJobId(jobId: Long) = repository.findByRetryOfJobId(jobId)?.toJob()
    override fun findForUpdate(jobId: Long) = repository.findForUpdate(jobId)?.toJob()
    override fun lockTelegramUpdate(telegramUpdateId: Int) = repository.lockTelegramUpdate(telegramUpdateId)
    override fun lockQueueClaims() = repository.lockQueueClaims()
    override fun claimNextQueuedJob() = repository.claimNextQueuedJob()?.toJob()
    override fun claimNextQueuedPlaylistJob() = repository.claimNextQueuedPlaylistJob()?.toJob()
    override fun queuePosition(jobId: Long) = repository.queuePosition(jobId)
    override fun findAllByStatusOrderByCreatedAtAscIdAsc(status: DownloadJobStatus) = repository.findAllByStatusOrderByCreatedAtAscIdAsc(status).map(DownloadJobEntity::toJob)
    override fun requeueProcessingJobs() = repository.requeueProcessingJobs()
    override fun complete(jobId: Long, receipt: String) = repository.complete(jobId, receipt)
    override fun fail(jobId: Long, error: String) = repository.fail(jobId, error)
    override fun cancelByUser(jobId: Long, userId: Long) = repository.cancelByUser(jobId, userId)

    override fun savePlaylistResult(jobId: Long, result: PlaylistAudioResult): Boolean {
        val job = repository.findForUpdate(jobId) ?: return false
        if (job.status != DownloadJobStatus.PROCESSING) return false
        if (job.playlistResults.any { it.position == result.position }) return true
        job.playlistResults = job.playlistResults + result
        return true
    }

    override fun savePlaylistFailures(jobId: Long, failures: List<PlaylistAudioResult>): Boolean {
        val job = repository.findForUpdate(jobId) ?: return false
        if (job.status != DownloadJobStatus.PROCESSING) return false
        job.playlistResults = failures.filter { it.fileId == null }
        return true
    }
}
