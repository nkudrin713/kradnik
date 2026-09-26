package com.nkudrin713.kradnik.download.playlist

import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.PlaylistAudioRequest
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.download.processing.DownloadPhase
import com.nkudrin713.kradnik.download.processing.JobProgress
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.telegram.DeliveryContext
import com.nkudrin713.kradnik.download.telegram.TelegramPlaylistSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

@Component
class ZipPlaylistWorkflow(
    private val downloadJobService: DownloadJobService,
    private val entryDownloader: PlaylistEntryDownloader,
    private val zipBuilder: PlaylistZipBuilder,
    private val telegramMediaSender: TelegramPlaylistSender,
    private val uploadLimits: TelegramUploadLimits,
    private val workDirCleaner: WorkDirCleaner,
    private val budget: PlaylistWorkspaceBudget,
    @Value("\${download.playlist-item-parallelism:2}") private val itemParallelism: Int = 2,
    @Value("\${download.playlist-zip-timeout:2h}") private val timeout: Duration = Duration.ofHours(2),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        require(itemParallelism > 0) { "download.playlist-item-parallelism must be positive" }
        require(timeout.isPositive) { "download.playlist-zip-timeout must be positive" }
    }

    suspend fun run(jobId: Long, request: PlaylistAudioRequest, context: DeliveryContext, root: Path, progress: JobProgress): PlaylistDeliveryResult? {
        try {
            return withTimeout(timeout.toMillis()) {
                coroutineScope {
                    budget.check(root)
                    val monitor = launch {
                        while (true) {
                            delay(250)
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
        val semaphore = Semaphore(itemParallelism)
        val totalBytes = AtomicLong()
        // Local results belong to this attempt. Startup cleanup removes all files, so retries rebuild the archive.
        val files = coroutineScope {
            request.entries.map { entry ->
                async {
                    semaphore.withPermit {
                        val directory = withContext(Dispatchers.IO) { Files.createDirectory(root.resolve(entry.position.toString())) }
                        val file = try {
                            entryDownloader.download(entry, directory)
                        } catch (error: DownloadFailure) {
                            if (error.reason == DownloadFailureReason.TOO_LARGE) throw PlaylistSizeLimitException(error)
                            if (error.reason !in SOURCE_FAILURES) throw error
                            budget.check(root)
                            logger.warn("PLAYLIST_JOB[{}] item {} unavailable: {}", jobId, entry.position, error.message, error)
                            workDirCleaner.deleteRecursively(directory)
                            return@withPermit null
                        }
                        val size = withContext(Dispatchers.IO) { Files.size(file.file) }
                        if (totalBytes.addAndGet(size) > uploadLimits.maxUploadBytes) throw PlaylistSizeLimitException()
                        PlaylistLocalFile(entry, file.file)
                    }
                }
            }.awaitAll().filterNotNull()
        }
        if (!downloadJobService.isProcessing(jobId)) return null
        check(files.isNotEmpty()) { "No playlist items could be downloaded" }
        progress.update(DownloadPhase.PACKING)
        val archive = zipBuilder.build(files, request.title, root, uploadLimits.maxUploadBytes)
        if (!downloadJobService.isProcessing(jobId)) return null
        currentCoroutineContext().ensureActive()
        progress.update(DownloadPhase.UPLOADING)
        val fileId = telegramMediaSender.sendArchive(context, archive)
        return PlaylistDeliveryResult(files.size, request.entries.size - files.size, PlaylistCompletion.Archive(fileId))
    }

    private companion object {
        val SOURCE_FAILURES = setOf(
            DownloadFailureReason.SOURCE_FAILED,
            DownloadFailureReason.AUTHENTICATION_REQUIRED,
            DownloadFailureReason.SOURCE_UNAVAILABLE,
            DownloadFailureReason.SOURCE_RATE_LIMITED,
            DownloadFailureReason.SOURCE_REQUEST_FAILED,
            DownloadFailureReason.METADATA_UNAVAILABLE,
        )
    }
}
