package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.delivery.DeliveryContext
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadRequestMapper
import com.nkudrin713.kradnik.download.domain.PlaylistDeliveryMode
import com.nkudrin713.kradnik.download.playlist.AudioMessagesPlaylistWorkflow
import com.nkudrin713.kradnik.download.playlist.ZipPlaylistWorkflow
import com.nkudrin713.kradnik.download.progress.DownloadPhase
import com.nkudrin713.kradnik.download.progress.JobNotifications
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class PlaylistJobProcessor(
    private val telegramDelivery: AudioMessagesPlaylistWorkflow,
    private val zipDelivery: ZipPlaylistWorkflow,
    private val lifecycle: JobLifecycle,
    private val progress: JobNotifications,
    private val workDirCleaner: WorkDirCleaner,
    private val jobProgressFactory: JobProgressFactory = JobProgressFactory(progress),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun process(job: DownloadJob) {
        val request = DownloadRequestMapper.playlist(job)
        var outputDir: Path? = null
        try {
            outputDir = workDirCleaner.create(job.requiredId())
            val jobProgress = jobProgressFactory.forJob(job)
            val context = DeliveryContext.fromJob(job)
            jobProgress.update(DownloadPhase.DOWNLOADING)
            val result = when (request.deliveryMode) {
                PlaylistDeliveryMode.AUDIO_MESSAGES -> telegramDelivery.run(job.requiredId(), request, context, outputDir, jobProgress)
                PlaylistDeliveryMode.ZIP -> zipDelivery.run(job.requiredId(), request, context, outputDir, jobProgress)
            } ?: return
            if (!lifecycle.complete(job, result.receipt)) return
            progress.playlistSummary(job, result)
        } catch (error: CancellationException) {
            if (lifecycle.isUserCancelled(job)) return
            throw error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: Exception) {
            logger.error("PLAYLIST_JOB[{}] failed", job.id, error)
            if (lifecycle.fail(job, error)) progress.playlistFailure(job, error)
        } finally {
            outputDir?.let(workDirCleaner::deleteRecursively)
        }
    }
}
