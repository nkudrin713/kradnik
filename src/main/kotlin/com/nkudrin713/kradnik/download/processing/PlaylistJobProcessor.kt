package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.PlaylistDeliveryMode
import com.nkudrin713.kradnik.download.playlist.AudioMessagesPlaylistWorkflow
import com.nkudrin713.kradnik.download.playlist.ZipPlaylistWorkflow
import com.nkudrin713.kradnik.download.repository.DownloadRequestMapper
import com.nkudrin713.kradnik.download.telegram.DeliveryContext
import com.nkudrin713.kradnik.download.telegram.TelegramJobProgress
import com.nkudrin713.kradnik.download.telegram.TelegramReceiptCodec
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class PlaylistJobProcessor(
    private val telegramDelivery: AudioMessagesPlaylistWorkflow,
    private val zipDelivery: ZipPlaylistWorkflow,
    private val lifecycle: JobLifecycle,
    private val progress: TelegramJobProgress,
    private val workDirCleaner: WorkDirCleaner,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun process(job: DownloadJob) {
        val request = DownloadRequestMapper.playlist(job)
        var outputDir: Path? = null
        try {
            outputDir = workDirCleaner.create(job.requiredId())
            val jobProgress = progress.forJob(job)
            val context = DeliveryContext.fromJob(job)
            jobProgress.update(DownloadPhase.DOWNLOADING)
            val result = when (request.deliveryMode) {
                PlaylistDeliveryMode.AUDIO_MESSAGES -> telegramDelivery.run(job.requiredId(), request, context, outputDir, jobProgress)
                PlaylistDeliveryMode.ZIP -> zipDelivery.run(job.requiredId(), request, context, outputDir, jobProgress)
            } ?: return
            val receipt = TelegramReceiptCodec.playlist(result.completion)
            if (!lifecycle.complete(job, receipt)) return
            progress.playlistSummary(job, result.failedCount)
        } catch (error: CancellationException) {
            if (lifecycle.isUserCancelled(job)) return
            throw error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: Exception) {
            logger.error("PLAYLIST_JOB[{}] failed", job.id, error)
            lifecycle.fail(job, error)
        } finally {
            outputDir?.let(workDirCleaner::deleteRecursively)
        }
    }
}
