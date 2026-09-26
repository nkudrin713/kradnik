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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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
    @Value("\${download.playlist-item-parallelism:2}") private val itemParallelism: Int = 2,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        require(itemParallelism > 0) { "download.playlist-item-parallelism must be positive" }
    }

    suspend fun run(jobId: Long, request: PlaylistAudioRequest, context: DeliveryContext, root: Path, progress: JobProgress): PlaylistDeliveryResult? {
        val completedPositions = request.completedEntries.map(PlaylistAudioResult::position).toSet()
        val pending = request.entries.filterNot { it.position in completedPositions }
        val semaphore = Semaphore(itemParallelism)
        coroutineScope {
            pending.map { entry ->
                async {
                    semaphore.withPermit {
                        processEntry(jobId, entry, root)
                    }
                }
            }.awaitAll()
        }
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

    private suspend fun processEntry(jobId: Long, entry: PlaylistAudioEntry, root: Path) {
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
        } finally {
            outputDir?.let(workDirCleaner::deleteRecursively)
        }
    }
}
