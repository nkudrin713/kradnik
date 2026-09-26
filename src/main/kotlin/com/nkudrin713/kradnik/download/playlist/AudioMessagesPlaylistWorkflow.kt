package com.nkudrin713.kradnik.download.playlist

import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistAudioRequest
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.processing.DownloadPhase
import com.nkudrin713.kradnik.download.processing.JobProgress
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.telegram.DeliveryContext
import com.nkudrin713.kradnik.download.telegram.TelegramPlaylistSender
import com.nkudrin713.kradnik.download.telegram.TelegramResultCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

@Component
class AudioMessagesPlaylistWorkflow(
    private val downloadJobService: DownloadJobService,
    private val entryDownloader: PlaylistEntryDownloader,
    private val telegramFileSender: TelegramPlaylistSender,
    private val cache: TelegramResultCache,
    private val workDirCleaner: WorkDirCleaner,
    private val items: PlaylistItems = PlaylistItems(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Processes entries without a recorded result using bounded parallelism, then delivers successful audio files.
     * Each entry reuses a cached Telegram file or stages a download and persists its success or failure.
     * Recorded positions, including failures, are skipped on resume; cancellation still propagates.
     *
     * Returns null if the job is no longer processing or no longer exists before delivery.
     * Fails if no successful files remain. Item directories are cleaned after staging; the caller owns [root].
     */
    suspend fun run(jobId: Long, request: PlaylistAudioRequest, context: DeliveryContext, root: Path, progress: JobProgress): PlaylistDeliveryResult? {
        val completedPositions = request.completedEntries.map(PlaylistAudioResult::position).toSet()
        val pending = request.entries.filterNot { it.position in completedPositions }
        items.map(jobId, pending) { entry -> processEntry(jobId, entry, root) }
        if (!downloadJobService.isProcessing(jobId)) return null

        val current = downloadJobService.findJob(jobId) ?: return null
        val successful = current.playlistResults.filter { it.fileId != null }
        if (successful.isEmpty()) {
            throw IllegalStateException("No playlist items could be downloaded")
        }
        progress.update(DownloadPhase.UPLOADING)
        telegramFileSender.sendPlaylistAudios(context, request.entries, successful)
        return PlaylistDeliveryResult(successful.size, current.playlistResults.count { it.fileId == null }, PlaylistCompletion.AudioMessages(successful.size))
    }

    private suspend fun processEntry(jobId: Long, entry: PlaylistAudioEntry, root: Path): Boolean? {
        var outputDir: Path? = null
        try {
            val cacheKey = entryDownloader.cacheKey(entry)
            val cached = cache.findAudio(cacheKey)
            val fileId = if (cached != null) {
                cached
            } else {
                val itemDir = withContext(Dispatchers.IO) {
                    Files.createDirectory(root.resolve(entry.position.toString()))
                }
                outputDir = itemDir
                val file = entryDownloader.download(entry, itemDir)
                telegramFileSender.stagePlaylistAudio(file, entry)
            }
            downloadJobService.savePlaylistResult(
                jobId,
                PlaylistAudioResult(position = entry.position, fileId = fileId),
            )
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn(
                "PLAYLIST_JOB[{}] item {} failed: {}",
                jobId,
                entry.position,
                error.message,
                error,
            )
            downloadJobService.savePlaylistResult(
                jobId,
                PlaylistAudioResult(
                    position = entry.position,
                    error = (error.message ?: error.javaClass.simpleName).take(1000),
                ),
            )
            return null
        } finally {
            outputDir?.let(workDirCleaner::deleteRecursively)
        }
    }
}
