package com.nkudrin713.kradnik.download.pipeline

import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.service.DownloadTaskService
import com.nkudrin713.kradnik.telegram.upload.TelegramFileUploader
import com.nkudrin713.kradnik.ytdlp.client.YtDlpMetadataDto
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively

private const val DOWNLOAD_ROOT = "/tmp/kradnik"

@Service
class DownloadPipeline(
    private val downloadTaskService: DownloadTaskService,
    private val ytDlpService: YtDlpService,
    private val mediaSourceRouter: MediaSourceRouter,
    private val telegramFileUploader: TelegramFileUploader,
    private val downloadTaskUiNotifier: DownloadTaskUiNotifier,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @OptIn(ExperimentalPathApi::class)
    suspend fun processTask(taskId: Long) {
        val tempDir = Path(DOWNLOAD_ROOT)
            .createDirectories()
            .resolve(taskId.toString())
            .createDirectories()
        val task = downloadTaskService.getTask(taskId)

        try {
            downloadTaskUiNotifier.downloading(taskId)
            val metadata = ytDlpService.extractMetadata(task.normalizedUrl, task.telegramChatId, taskId).toMediaMetadata()
            downloadTaskService.markMetadata(taskId, metadata)
            val mediaSourceService = mediaSourceRouter.find(metadata)
            val downloadedFile = mediaSourceService.download(
                task.normalizedUrl,
                metadata,
                task.outputType,
                tempDir,
                task.telegramChatId,
                taskId,
            )
            downloadTaskService.markUploading(taskId)
            downloadTaskUiNotifier.uploading(taskId)
            val telegramFile = telegramFileUploader.upload(
                task.telegramChatId,
                taskId,
                task.outputType,
                downloadedFile,
                metadata.title,
            )
            downloadTaskService.markCompleted(taskId, telegramFile)
            downloadTaskUiNotifier.completed(taskId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            downloadTaskService.markFailed(taskId, e.message ?: e::class.simpleName.orEmpty())
            downloadTaskUiNotifier.failed(taskId)
            logger.error("CHAT[{}] TASK[{}] failed: {}", task.telegramChatId, taskId, e.message, e)
            throw e
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

private fun YtDlpMetadataDto.toMediaMetadata(): MediaMetadata =
    MediaMetadata(
        title = title,
        extractor = extractor,
        durationSeconds = duration?.toLong(),
        width = width,
        height = height,
        webpageUrl = webpageUrl,
    )
