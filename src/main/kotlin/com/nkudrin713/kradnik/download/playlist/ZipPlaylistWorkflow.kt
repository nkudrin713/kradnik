package com.nkudrin713.kradnik.download.playlist

import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.PlaylistAudioRequest
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.download.processing.DownloadPhase
import com.nkudrin713.kradnik.download.processing.JobProgress
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.telegram.DeliveryContext
import com.nkudrin713.kradnik.download.telegram.TelegramPlaylistSender
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

@Component
class ZipPlaylistWorkflow(
    private val downloadJobService: DownloadJobService,
    private val entryDownloader: PlaylistEntryDownloader,
    private val zipBuilder: PlaylistZipBuilder,
    private val telegramMediaSender: TelegramPlaylistSender,
    private val uploadLimits: TelegramUploadLimits,
    private val workDirCleaner: WorkDirCleaner,
    private val budget: PlaylistWorkspaceBudget,
    @Value($$"${download.playlist-zip-timeout:2h}") private val timeout: Duration = Duration.ofHours(2),
    private val items: PlaylistItems = PlaylistItems(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        require(timeout.isPositive) { "download.playlist-zip-timeout must be positive" }
    }

    /**
     * Downloads entries, builds a ZIP, and sends it under one timeout with periodic workspace-budget checks.
     * Retries rebuild all local files. Recognized source failures skip individual entries; size-limit and
     * other failures abort the attempt. Both downloaded files and the final archive must fit the upload limit.
     *
     * Returns null when a job-state check before packing or sending finds the job is no longer processing.
     * An empty result fails the attempt. The workflow timeout becomes an [IllegalStateException], while
     * parent cancellation propagates. The caller owns cleanup of [root], including partial files and the ZIP.
     */
    suspend fun run(jobId: Long, request: PlaylistAudioRequest, context: DeliveryContext, root: Path, progress: JobProgress): PlaylistDeliveryResult? {
        try {
            return withTimeout(timeout.toMillis().milliseconds) {
                coroutineScope {
                    budget.check(root)
                    val monitor = launch {
                        while (true) {
                            delay(250.milliseconds)
                            budget.check(root)
                        }
                    }
                    try {
                        downloadAndSend(jobId, request, context, root, progress)
                    } finally {
                        monitor.cancelAndJoin()
                    }
                }
            }
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException("Playlist archive exceeded its time limit", error)
        }
    }

    private suspend fun downloadAndSend(jobId: Long, request: PlaylistAudioRequest, context: DeliveryContext, root: Path, progress: JobProgress): PlaylistDeliveryResult? {
        val totalBytes = AtomicLong()
        val failures = Collections.synchronizedList(mutableListOf<PlaylistAudioResult>())
        val tracker = PlaylistProgressTracker(request.entries, progress)
        // Local results belong to this attempt. Startup cleanup removes all files, so retries rebuild the archive.
        val files = items.map(jobId, request.entries) { entry ->
            tracker.started(entry)
            tracker.downloading()
            val directory = withContext(Dispatchers.IO) { Files.createDirectory(root.resolve(entry.position.toString())) }
            val file = try {
                entryDownloader.download(entry, directory, request.source)
            } catch (error: DownloadFailure) {
                if (error.reason == DownloadFailureReason.TOO_LARGE) throw PlaylistSizeLimitException(error)
                if (!error.isPlaylistSourceFailure()) throw error
                budget.check(root)
                logger.warn("PLAYLIST_JOB[{}] item {} unavailable: {}", jobId, entry.position, error.message, error)
                failures += PlaylistAudioResult(entry.position, error = error.message?.take(1000), failure = PlaylistItemFailure.from(error.message, error.reason))
                tracker.finished(entry, false)
                workDirCleaner.deleteRecursively(directory)
                return@map null
            }
            val size = withContext(Dispatchers.IO) { Files.size(file.file) }
            if (totalBytes.addAndGet(size) > uploadLimits.maxUploadBytes) throw PlaylistSizeLimitException()
            tracker.finished(entry, true)
            PlaylistLocalFile(entry, file.file)
        }
        if (!downloadJobService.isProcessing(jobId)) return null
        if (files.isEmpty()) throw PlaylistEmptyException(failures.toList())
        progress.update(DownloadPhase.PACKING)
        val archive = try {
            zipBuilder.build(files, request.title, root, uploadLimits.maxUploadBytes)
        } catch (error: CancellationException) {
            throw error
        } catch (error: PlaylistSizeLimitException) {
            throw error
        } catch (error: Exception) {
            throw PlaylistOperationException(TelegramMessage.PLAYLIST_ARCHIVE_FAILED, error)
        }
        if (!downloadJobService.isProcessing(jobId)) return null
        currentCoroutineContext().ensureActive()
        progress.update(DownloadPhase.UPLOADING)
        val fileId = try {
            telegramMediaSender.sendArchive(context, archive)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw PlaylistOperationException(TelegramMessage.PLAYLIST_UPLOAD_FAILED, error)
        }
        return PlaylistDeliveryResult(files.size, request.entries.size - files.size, PlaylistCompletion.Archive(fileId), failures.toList())
    }
}
