package com.nkudrin713.kradnik.download.pipeline

import com.nkudrin713.kradnik.download.domain.DownloadOutputType
import com.nkudrin713.kradnik.download.domain.DownloadTask
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.service.DownloadTaskService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertFailsWith

class DownloadPipelineTest {
    private val downloadTaskService: DownloadTaskService = mock()
    private val metadataExtractor: MetadataExtractor = mock()
    private val downloaderRouter: DownloaderRouter = mock()
    private val mediaDownloader: MediaDownloader = mock()

    private val pipeline = DownloadPipeline(
        downloadTaskService = downloadTaskService,
        metadataExtractor = metadataExtractor,
        downloaderRouter = downloaderRouter,
    )

    @Test
    fun `marks task as failed when download fails`() = runTest {
        val task = DownloadTask(
            id = 1,
            normalizedUrl = "https://example.com",
            outputType = DownloadOutputType.AUDIO,
        )
        val metadata = MediaMetadata(
            title = "title",
            extractor = "youtube",
            durationSeconds = 10,
            webpageUrl = "https://example.com",
        )

        whenever(downloadTaskService.getTask(1)).thenReturn(task)
        whenever(metadataExtractor.extract(task.normalizedUrl)).thenReturn(metadata)
        whenever(downloaderRouter.findDownloader(metadata, task.outputType)).thenReturn(mediaDownloader)
        whenever(mediaDownloader.download(any(), any(), any(), any())).thenThrow(RuntimeException("download failed"))

        assertFailsWith<RuntimeException> {
            pipeline.processTask(1)
        }

        verify(downloadTaskService).markFailed(1, "download failed")
    }
}
