package com.nkudrin713.kradnik.download.pipeline

import com.nkudrin713.kradnik.download.domain.DownloadOutputType
import com.nkudrin713.kradnik.download.domain.DownloadTask
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.service.DownloadTaskService
import com.nkudrin713.kradnik.download.service.TelegramFileResult
import com.nkudrin713.kradnik.telegram.upload.TelegramFileUploader
import com.nkudrin713.kradnik.ytdlp.client.YtDlpMetadataDto
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import kotlin.test.assertFailsWith

class DownloadPipelineTest {
    private val downloadTaskService: DownloadTaskService = mock()
    private val ytDlpService: YtDlpService = mock()
    private val mediaSourceRouter: MediaSourceRouter = mock()
    private val mediaSourceService: MediaSourceService = mock()
    private val telegramFileUploader: TelegramFileUploader = mock()

    private val pipeline = DownloadPipeline(
        downloadTaskService = downloadTaskService,
        ytDlpService = ytDlpService,
        mediaSourceRouter = mediaSourceRouter,
        telegramFileUploader = telegramFileUploader,
    )

    @Test
    fun `downloads uploads and marks task completed`(@TempDir tempDir: File) = runTest {
        val task = task()
        val metadata = metadata()
        val downloadedFile = downloadedFile(tempDir)
        val telegramFile = TelegramFileResult(
            fileId = "telegram-file-id",
            fileSize = downloadedFile.sizeBytes,
        )

        whenever(downloadTaskService.getTask(1)).thenReturn(task)
        whenever(ytDlpService.extractMetadata(task.normalizedUrl)).thenReturn(metadataDto())
        whenever(mediaSourceRouter.find(metadata)).thenReturn(mediaSourceService)
        whenever(mediaSourceService.download(any(), any(), any(), any())).thenReturn(downloadedFile)
        whenever(telegramFileUploader.upload(task.telegramChatId, task.outputType, downloadedFile)).thenReturn(telegramFile)

        pipeline.processTask(1)

        verify(downloadTaskService).markCompleted(1, telegramFile)
    }

    @Test
    fun `marks task as failed when download fails`() = runTest {
        val task = task()
        val metadata = metadata()

        whenever(downloadTaskService.getTask(1)).thenReturn(task)
        whenever(ytDlpService.extractMetadata(task.normalizedUrl)).thenReturn(metadataDto())
        whenever(mediaSourceRouter.find(metadata)).thenReturn(mediaSourceService)
        whenever(mediaSourceService.download(any(), any(), any(), any())).thenThrow(RuntimeException("download failed"))

        assertFailsWith<RuntimeException> {
            pipeline.processTask(1)
        }

        verify(downloadTaskService).markFailed(1, "download failed")
    }

    private fun task(): DownloadTask =
        DownloadTask(
            id = 1,
            telegramChatId = 100,
            normalizedUrl = "https://example.com",
            outputType = DownloadOutputType.AUDIO,
        )

    private fun metadata(): MediaMetadata =
        MediaMetadata(
            title = "title",
            extractor = "youtube",
            durationSeconds = 10,
            webpageUrl = "https://example.com",
        )

    private fun metadataDto(): YtDlpMetadataDto =
        YtDlpMetadataDto(
            id = "id",
            title = "title",
            extractor = "youtube",
            webpageUrl = "https://example.com",
            thumbnail = null,
            duration = 10,
            ext = null,
            width = null,
            height = null,
            fps = null,
            filesize = null,
            vcodec = null,
            acodec = null,
            filesizeApprox = null,
            formatId = null,
            format = null,
        )

    private fun downloadedFile(tempDir: File): DownloadedFile {
        val file = File(tempDir, "audio.mp3")
        file.writeText("audio")
        return DownloadedFile(
            file = file,
            ext = "mp3",
            sizeBytes = file.length(),
            args = emptyList(),
        )
    }
}
