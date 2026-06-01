package com.nkudrin713.kradnik.download.pipeline

import com.nkudrin713.kradnik.download.service.DownloadTaskService
import org.springframework.stereotype.Service
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteRecursively

private const val DOWNLOAD_ROOT = "/tmp/kradnik"

@Service
class DownloadPipeline(
    private val downloadTaskService: DownloadTaskService,
    private val metadataExtractor: MetadataExtractor,
    private val downloaderRouter: DownloaderRouter,
) {


    @OptIn(ExperimentalPathApi::class)
    suspend fun processTask(taskId: Long) {
        val tempDir = Path(DOWNLOAD_ROOT)
            .createDirectories()
            .resolve(taskId.toString())
            .createDirectory()
        val task = downloadTaskService.getTask(taskId)

        try {
            val metadata = metadataExtractor.extract(task.normalizedUrl)
            val downloader = downloaderRouter.findDownloader(metadata, task.outputType)
            downloader.download(task.normalizedUrl, metadata, task.outputType, tempDir.toFile())
        } catch (e: RuntimeException) {
            downloadTaskService.markFailed(taskId, e.message ?: e::class.simpleName.orEmpty())
            throw e
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
