package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.telegram.TelegramFileSender
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import com.nkudrin713.kradnik.telegram.TelegramMessageAddress
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessages
import com.nkudrin713.kradnik.ytdlp.YtDlpService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

@Component
class PlaylistJobProcessor(
    private val downloadJobService: DownloadJobService,
    private val ytDlpService: YtDlpService,
    private val telegramFileSender: TelegramFileSender,
    private val telegramSender: TelegramSender,
    private val messages: TelegramMessages,
    private val workDirCleaner: WorkDirCleaner,
    @Value("\${download.playlist-item-parallelism:2}") private val itemParallelism: Int = 2,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        require(itemParallelism > 0) { "download.playlist-item-parallelism must be positive" }
    }

    suspend fun process(job: DownloadJob) {
        require(job.workloadType == DownloadWorkloadType.PLAYLIST_AUDIO)
        var outputDir: Path? = null
        try {
            outputDir = workDirCleaner.create(job.requiredId())
            setStatus(job, TelegramDownloadStatus.DOWNLOADING)
            val completedPositions = job.playlistResults.map(PlaylistAudioResult::position).toSet()
            val pending = job.playlistEntries.filterNot { it.position in completedPositions }
            val semaphore = Semaphore(itemParallelism)
            coroutineScope {
                pending.map { entry ->
                    async {
                        semaphore.withPermit {
                            processEntry(job, entry, outputDir)
                        }
                    }
                }.awaitAll()
            }
            if (!downloadJobService.isProcessing(job.requiredId())) return

            val current = downloadJobService.findJob(job.requiredId()) ?: return
            val successful = current.playlistResults.filter { it.fileId != null }
            if (successful.isEmpty()) {
                fail(job, "No playlist items could be downloaded")
                return
            }
            setStatus(job, TelegramDownloadStatus.UPLOADING)
            telegramFileSender.sendPlaylistAudios(current, successful)
            val failedCount = current.playlistResults.count { it.fileId == null }
            if (failedCount > 0) {
                telegramSender.sendMessage(
                    job.telegramChatId,
                    messages.text(job.language, TelegramMessage.PLAYLIST_COMPLETED_WITH_ERRORS, failedCount),
                )
            }
            complete(job, successful.size)
        } catch (error: CancellationException) {
            if (downloadJobService.isCancelledByUser(job.requiredId())) return
            throw error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: Exception) {
            logger.error("PLAYLIST_JOB[{}] failed", job.id, error)
            fail(job, error.message ?: error.javaClass.simpleName)
        } finally {
            outputDir?.let(workDirCleaner::deleteRecursively)
        }
    }

    private suspend fun processEntry(job: DownloadJob, entry: PlaylistAudioEntry, root: Path) {
        var outputDir: Path? = null
        try {
            val cacheKey = "youtube:video:${entry.videoId}:audio:youtube_audio:audio:96K"
            val cached = downloadJobService.findCachedFileId(cacheKey)
            val fileId = if (cached != null) {
                cached
            } else {
                val itemDir = Files.createDirectory(root.resolve(entry.position.toString()))
                outputDir = itemDir
                val spec = DownloadSpec(
                    originalUrl = entry.url,
                    normalizedUrl = "https://www.youtube.com/watch?v=${entry.videoId}",
                    cacheKey = cacheKey,
                    outputType = OutputType.AUDIO,
                    platform = DownloadPlatform.YOUTUBE,
                    formatSelector = "ba/bestaudio",
                    extraArgs = PLAYLIST_AUDIO_ARGS,
                    presetName = "youtube_audio",
                )
                val file = ytDlpService.download(spec, itemDir)
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

    private fun complete(job: DownloadJob, successfulCount: Int) {
        if (!downloadJobService.markCompleted(job, "playlist:$successfulCount")) return
        job.telegramStatusMessageId?.let { messageId ->
            runCatching { telegramSender.deleteMessage(job.telegramChatId, messageId) }
                .onFailure { logger.warn("PLAYLIST_JOB[{}] status deletion failed", job.id, it) }
        }
    }

    private fun fail(job: DownloadJob, reason: String) {
        if (!downloadJobService.markFailed(job, reason)) return
        telegramSender.editStatus(address(job), TelegramDownloadStatus.ERROR, job.language)
    }

    private fun setStatus(job: DownloadJob, status: TelegramDownloadStatus) {
        runCatching {
            telegramSender.editJobStatus(address(job), status, job.requiredId(), job.language)
        }.onFailure { logger.warn("PLAYLIST_JOB[{}] status update failed", job.id, it) }
    }

    private fun address(job: DownloadJob): TelegramMessageAddress = TelegramMessageAddress.Chat(job.telegramChatId, requireNotNull(job.telegramStatusMessageId))

    private companion object {
        val PLAYLIST_AUDIO_ARGS = listOf(
            "-x",
            "--audio-format", "mp3",
            "--audio-quality", "96K",
            "--embed-metadata",
            "--embed-thumbnail",
            "--convert-thumbnails", "jpg",
        )
    }
}
