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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.writeText
import kotlin.test.assertFailsWith

class DownloadPipelineTest {
    private val downloadTaskService: DownloadTaskService = mockk()
    private val ytDlpService: YtDlpService = mockk()
    private val mediaSourceRouter: MediaSourceRouter = mockk()
    private val mediaSourceService: MediaSourceService = mockk()
    private val telegramFileUploader: TelegramFileUploader = mockk()

    private val pipeline = DownloadPipeline(
        downloadTaskService = downloadTaskService,
        ytDlpService = ytDlpService,
        mediaSourceRouter = mediaSourceRouter,
        telegramFileUploader = telegramFileUploader,
    )

    @Test
    fun `downloads uploads and marks task completed`(@TempDir tempDir: Path) = runTest {
        val task = task()
        val metadata = metadata()
        val downloadedFile = downloadedFile(tempDir)
        val telegramFile = TelegramFileResult(
            fileId = "telegram-file-id",
            fileSize = downloadedFile.sizeBytes,
        )

        every { downloadTaskService.getTask(1) } returns task
        coEvery { ytDlpService.extractMetadata(task.normalizedUrl, task.telegramChatId, 1) } returns metadataDto()
        every { downloadTaskService.markMetadata(1, metadata) } returns task
        every { mediaSourceRouter.find(metadata) } returns mediaSourceService
        coEvery { mediaSourceService.download(any(), any(), any(), any(), any(), any()) } returns downloadedFile
        every { telegramFileUploader.upload(task.telegramChatId, 1, task.outputType, downloadedFile) } returns telegramFile
        every { downloadTaskService.markCompleted(1, telegramFile) } returns task

        pipeline.processTask(1)

        verify { downloadTaskService.markMetadata(1, metadata) }
        verify { downloadTaskService.markCompleted(1, telegramFile) }
    }

    @Test
    fun `marks task as failed when download fails`() = runTest {
        val task = task()
        val metadata = metadata()

        every { downloadTaskService.getTask(1) } returns task
        coEvery { ytDlpService.extractMetadata(task.normalizedUrl, task.telegramChatId, 1) } returns metadataDto()
        every { downloadTaskService.markMetadata(1, metadata) } returns task
        every { mediaSourceRouter.find(metadata) } returns mediaSourceService
        coEvery { mediaSourceService.download(any(), any(), any(), any(), any(), any()) } throws RuntimeException("download failed")
        every { downloadTaskService.markFailed(1, "download failed") } returns task

        assertFailsWith<RuntimeException> {
            pipeline.processTask(1)
        }

        verify { downloadTaskService.markFailed(1, "download failed") }
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

    private fun downloadedFile(tempDir: Path): DownloadedFile {
        val file = tempDir.resolve("audio.mp3")
        file.writeText("audio")
        return DownloadedFile(
            file = file,
            ext = "mp3",
            sizeBytes = file.fileSize(),
            args = emptyList(),
        )
    }
}
