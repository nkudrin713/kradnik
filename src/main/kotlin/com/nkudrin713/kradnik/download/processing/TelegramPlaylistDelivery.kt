package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.playlist.PlaylistEntryDownloader
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.telegram.TelegramFileSender
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
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
class TelegramPlaylistDelivery(
    private val downloadJobService: DownloadJobService,
    private val entryDownloader: PlaylistEntryDownloader,
    private val telegramFileSender: TelegramFileSender,
    private val workDirCleaner: WorkDirCleaner,
    @Value("\${download.playlist-item-parallelism:2}") private val itemParallelism: Int = 2,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        require(itemParallelism > 0) { "download.playlist-item-parallelism must be positive" }
    }

    suspend fun deliver(job: DownloadJob, root: Path, onStatus: (TelegramDownloadStatus) -> Unit): PlaylistDeliveryResult? {
        val completedPositions = job.playlistResults.map(PlaylistAudioResult::position).toSet()
        val pending = job.playlistEntries.filterNot { it.position in completedPositions }
        val semaphore = Semaphore(itemParallelism)
        coroutineScope {
            pending.map { entry ->
                async {
                    semaphore.withPermit {
                        processEntry(job, entry, root)
                    }
                }
            }.awaitAll()
        }
        if (!downloadJobService.isProcessing(job.requiredId())) return null

        val current = downloadJobService.findJob(job.requiredId()) ?: return null
        val successful = current.playlistResults.filter { it.fileId != null }
        if (successful.isEmpty()) {
            throw IllegalStateException("No playlist items could be downloaded")
        }
        onStatus(TelegramDownloadStatus.UPLOADING)
        telegramFileSender.sendPlaylistAudios(current, successful)
        return PlaylistDeliveryResult(successful.size, current.playlistResults.count { it.fileId == null }, "playlist:${successful.size}")
    }

    private suspend fun processEntry(job: DownloadJob, entry: PlaylistAudioEntry, root: Path) {
        var outputDir: Path? = null
        try {
            val cacheKey = entryDownloader.cacheKey(entry)
            val cached = downloadJobService.findCachedFileId(cacheKey)
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
                job.requiredId(),
                PlaylistAudioResult(position = entry.position, fileId = fileId),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn(
                "PLAYLIST_JOB[{}] item {} failed: {}",
                job.id,
                entry.position,
                error.message,
            )
            downloadJobService.savePlaylistResult(
                job.requiredId(),
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
