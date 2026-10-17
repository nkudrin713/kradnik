package com.nkudrin713.kradnik.download.playlist

import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.delivery.DeliveryContext
import com.nkudrin713.kradnik.download.delivery.PlaylistDelivery
import com.nkudrin713.kradnik.download.delivery.PlaylistDeliveryResult
import com.nkudrin713.kradnik.download.delivery.ResultCache
import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistAudioRequest
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.progress.DownloadPhase
import com.nkudrin713.kradnik.download.progress.JobProgress
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.localization.MessageKey
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
    private val telegramFileSender: PlaylistDelivery,
    private val cache: ResultCache,
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
        val cached = pending.associate { entry ->
            entry.position to (
                cache.find(entryDownloader.cacheKey(entry, request.source))
                    ?: request.source?.let { cache.findPlaylistAudio(entry.sourceId, it) }
                )
        }
        val tracker = PlaylistProgressTracker(request.entries, progress, request.completedEntries, pending.filter { cached[it.position] == null })
        if (cached.values.any { it == null }) {
            try {
                telegramFileSender.checkStorage()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw PlaylistOperationException(MessageKey.PLAYLIST_STORAGE_UNAVAILABLE, error)
            }
        }
        items.map(jobId, pending) { entry ->
            tracker.started(entry)
            if (cached[entry.position] == null) tracker.downloading()
            processEntry(jobId, entry, root, request, cached[entry.position]).also {
                tracker.finished(entry, it != null)
            }
        }
        if (!downloadJobService.isProcessing(jobId)) return null

        val current = downloadJobService.findJob(jobId) ?: return null
        val successful = current.playlistResults.filter { it.fileId != null }
        if (successful.isEmpty()) {
            throw PlaylistEmptyException(current.playlistResults.filter { it.fileId == null })
        }
        progress.update(DownloadPhase.UPLOADING)
        val receipt = try {
            telegramFileSender.sendPlaylistAudios(context, request.entries, successful)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw PlaylistOperationException(MessageKey.PLAYLIST_UPLOAD_FAILED, error)
        }
        return PlaylistDeliveryResult(successful.size, current.playlistResults.count { it.fileId == null }, receipt, current.playlistResults.filter { it.fileId == null })
    }

    private suspend fun processEntry(jobId: Long, entry: PlaylistAudioEntry, root: Path, request: PlaylistAudioRequest, cached: String?): Boolean? {
        var outputDir: Path? = null
        try {
            val fileId = if (cached != null) {
                cached
            } else {
                val itemDir = withContext(Dispatchers.IO) {
                    Files.createDirectory(root.resolve(entry.position.toString()))
                }
                outputDir = itemDir
                val file = entryDownloader.download(entry, itemDir, request.source)
                try {
                    telegramFileSender.stagePlaylistAudio(file, entry)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    throw PlaylistOperationException(MessageKey.PLAYLIST_STORAGE_UNAVAILABLE, error)
                }
            }
            downloadJobService.savePlaylistResult(
                jobId,
                PlaylistAudioResult(position = entry.position, fileId = fileId),
            )
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: DownloadFailure) {
            if (!error.isPlaylistSourceFailure()) throw error
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
                    failure = error.playlistFailure,
                ),
            )
            return null
        } finally {
            outputDir?.let(workDirCleaner::deleteRecursively)
        }
    }
}
