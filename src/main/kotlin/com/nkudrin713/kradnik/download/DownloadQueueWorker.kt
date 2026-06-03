package com.nkudrin713.kradnik.download

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.service.DownloadedFileResult
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.createDirectories

@Component
class DownloadQueueWorker(
    private val downloadJobService: DownloadJobService,
    private val downloadOrchestrator: DownloadOrchestrator,
    private val telegramSender: TelegramSender,
    private val ytDlpService: YtDlpService,
    @Value("\${download.work-dir:/tmp/kradnik-downloads}")
    private val workDir: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${download.worker-delay-ms:1000}")
    fun processNextJob() {
        val job = downloadJobService.claimNextQueuedJob() ?: return

        runBlocking {
            processJob(job)
        }
    }

    private suspend fun processJob(job: DownloadJob) {
        val jobId = requireNotNull(job.id)
        val outputDir = Path.of(workDir).resolve(jobId.toString()).createDirectories()

        try {
            runCatching {
                markMetadata(job)
            }.onFailure {
                logger.warn("JOB[{}] metadata extraction skipped: {}", jobId, it.message)
            }

            val downloadedFile = downloadOrchestrator.download(job, outputDir)

            downloadJobService.markUploading(jobId)

            val telegramResult = when (job.outputType) {
                OutputType.VIDEO -> telegramSender.sendVideo(job.telegramChatId, downloadedFile.file)
                OutputType.AUDIO -> telegramSender.sendAudio(job.telegramChatId, downloadedFile.file)
            }

            downloadJobService.markCompleted(
                jobId,
                DownloadedFileResult(
                    telegramFileId = telegramResult.fileId,
                    telegramFileSize = telegramResult.fileSize,
                    downloadedFileSize = downloadedFile.sizeBytes,
                )
            )
        } catch (error: Exception) {
            logger.error("JOB[{}] processing failed", jobId, error)
            downloadJobService.markFailedOrRetry(jobId, error.message ?: error.javaClass.simpleName)
        } finally {
            deleteRecursively(outputDir)
        }
    }

    private suspend fun markMetadata(job: DownloadJob) {
        val jobId = requireNotNull(job.id)
        val metadata = ytDlpService.extractMetadata(job.originalUrl)

        downloadJobService.markMetadata(
            jobId,
            MediaMetadata(
                title = metadata.title,
                extractor = metadata.extractor,
                durationSeconds = metadata.duration?.toLong(),
                width = metadata.width,
                height = metadata.height,
                webpageUrl = metadata.webpageUrl,
            )
        )
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) {
            return
        }

        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder())
                .forEach(Files::deleteIfExists)
        }
    }
}
