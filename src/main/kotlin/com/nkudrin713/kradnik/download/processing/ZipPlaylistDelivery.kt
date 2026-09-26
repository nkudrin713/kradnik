package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.download.playlist.PlaylistEntryDownloader
import com.nkudrin713.kradnik.download.playlist.PlaylistLocalFile
import com.nkudrin713.kradnik.download.playlist.PlaylistSizeLimitException
import com.nkudrin713.kradnik.download.playlist.PlaylistZipBuilder
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import com.nkudrin713.kradnik.telegram.TelegramMediaSender
import com.nkudrin713.kradnik.ytdlp.YtDlpException
import com.nkudrin713.kradnik.ytdlp.YtDlpFileSizeLimitException
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
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

@Component
class ZipPlaylistDelivery(
    private val downloadJobService: DownloadJobService,
    private val entryDownloader: PlaylistEntryDownloader,
    private val zipBuilder: PlaylistZipBuilder,
    private val telegramMediaSender: TelegramMediaSender,
    private val uploadLimits: TelegramUploadLimits,
    private val workDirCleaner: WorkDirCleaner,
    @Value("\${download.playlist-item-parallelism:2}") private val itemParallelism: Int = 2,
    @Value("\${download.playlist-zip-timeout:2h}") private val timeout: Duration = Duration.ofHours(2),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        require(itemParallelism > 0) { "download.playlist-item-parallelism must be positive" }
        require(timeout.isPositive) { "download.playlist-zip-timeout must be positive" }
    }

    suspend fun deliver(job: DownloadJob, root: Path, onStatus: (TelegramDownloadStatus) -> Unit): PlaylistDeliveryResult? {
        try {
            return withTimeout(timeout.toMillis()) {
                coroutineScope {
                    checkWorkspace(root)
                    val monitor = launch {
                        while (true) {
                            delay(250)
                            checkWorkspace(root)
                        }
                    }
                    try {
                        downloadAndSend(job, root, onStatus)
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

    private suspend fun downloadAndSend(job: DownloadJob, root: Path, onStatus: (TelegramDownloadStatus) -> Unit): PlaylistDeliveryResult? {
        val semaphore = Semaphore(itemParallelism)
        val totalBytes = AtomicLong()
        // Local results belong to this attempt. Startup cleanup removes all files, so retries rebuild the archive.
        val files = coroutineScope {
            job.playlistEntries.map { entry ->
                async {
                    semaphore.withPermit {
                        val directory = withContext(Dispatchers.IO) { Files.createDirectory(root.resolve(entry.position.toString())) }
                        val file = try {
                            entryDownloader.download(entry, directory)
                        } catch (error: YtDlpFileSizeLimitException) {
                            throw PlaylistSizeLimitException()
                        } catch (error: YtDlpException) {
                            checkWorkspace(root)
                            logger.warn("PLAYLIST_JOB[{}] item {} unavailable: {}", job.id, entry.position, error.message)
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
        if (!downloadJobService.isProcessing(job.requiredId())) return null
        check(files.isNotEmpty()) { "No playlist items could be downloaded" }
        onStatus(TelegramDownloadStatus.PACKING)
        val archive = zipBuilder.build(files, job.playlistTitle, root, uploadLimits.maxUploadBytes)
        if (!downloadJobService.isProcessing(job.requiredId())) return null
        currentCoroutineContext().ensureActive()
        onStatus(TelegramDownloadStatus.UPLOADING)
        val fileId = telegramMediaSender.sendDocument(job.telegramChatId, archive, job.telegramRequestMessageId)
        return PlaylistDeliveryResult(files.size, job.playlistEntries.size - files.size, fileId)
    }

    private suspend fun checkWorkspace(root: Path) = withContext(Dispatchers.IO) {
        // MP3s, the archive and temporary conversion files share one bounded workspace.
        val maxBytes = uploadLimits.maxUploadBytes * 3
        var usedBytes = 0L
        val context = currentCoroutineContext()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    context.ensureActive()
                    if (attrs.isRegularFile) usedBytes += attrs.size()
                    if (usedBytes > maxBytes) throw PlaylistSizeLimitException()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                    // Download processes can rename or remove temporary files during the scan.
                    if (error is NoSuchFileException) return FileVisitResult.CONTINUE
                    throw error
                }
            },
        )
        check(Files.getFileStore(root).usableSpace >= 64L * 1024 * 1024) { "Not enough disk space for playlist archive" }
    }
}
