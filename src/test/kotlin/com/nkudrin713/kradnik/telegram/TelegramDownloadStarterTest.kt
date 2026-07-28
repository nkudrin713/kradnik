package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.analytics.DownloadAnalytics
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.identity.DownloadIdentity
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import com.nkudrin713.kradnik.download.platform.ResolvedDownload
import com.nkudrin713.kradnik.download.platform.UnsupportedPlatformException
import com.nkudrin713.kradnik.download.request.DownloadRequest
import com.nkudrin713.kradnik.download.service.CreateDownloadJobCommand
import com.nkudrin713.kradnik.download.service.CreateDownloadJobResult
import com.nkudrin713.kradnik.download.service.DownloadJobService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TelegramDownloadStarterTest {
    private val downloadJobService: DownloadJobService = mockk()
    private val platformResolver: PlatformResolver = mockk()
    private val telegramSender: TelegramSender = mockk()
    private val downloadAnalytics: DownloadAnalytics = mockk(relaxed = true)
    private val starter = TelegramDownloadStarter(
        downloadJobService = downloadJobService,
        platformResolver = platformResolver,
        telegramSender = telegramSender,
        downloadAnalytics = downloadAnalytics,
    )

    @Test
    fun validatesUrlWithoutCreatingJob() {
        every {
            platformResolver.resolve(URL, OutputType.VIDEO)
        } returns resolvedDownload(OutputType.VIDEO)

        val valid = starter.validate(100, URL)

        assertEquals(true, valid)
        verify(exactly = 0) { downloadJobService.createJob(any()) }
    }

    @Test
    fun reportsUnsupportedUrlDuringValidation() {
        every {
            platformResolver.resolve(URL, OutputType.VIDEO)
        } throws UnsupportedPlatformException("Платформа не поддерживается")
        every {
            telegramSender.sendMessage(100, "Платформа не поддерживается")
        } just runs

        val valid = starter.validate(100, URL)

        assertEquals(false, valid)
    }

    @Test
    fun createsJobWithRequestMessageAndRecordsAnalytics() {
        val command = slot<CreateDownloadJobCommand>()
        val job = DownloadJob(id = 1)
        every {
            platformResolver.resolve(URL, OutputType.VIDEO)
        } returns resolvedDownload(OutputType.VIDEO)
        every {
            telegramSender.sendStatus(100, TelegramDownloadStatus.QUEUED)
        } returns 500
        every {
            downloadJobService.createJob(capture(command))
        } returns CreateDownloadJobResult.Created(job)

        val started = start(OutputType.VIDEO)

        assertEquals(true, started)
        assertEquals(200, command.captured.telegramRequestMessageId)
        assertEquals("video:telegram-video-h264-v1", command.captured.cacheKey)
        verify { downloadAnalytics.recordDownloadRequested(command.captured, job) }
    }

    @Test
    fun removesDuplicateStatusWithoutRecordingAnalytics() {
        every {
            platformResolver.resolve(URL, OutputType.AUDIO)
        } returns resolvedDownload(OutputType.AUDIO)
        every {
            telegramSender.sendStatus(100, TelegramDownloadStatus.QUEUED)
        } returns 500
        every {
            downloadJobService.createJob(any())
        } returns CreateDownloadJobResult.Existing(DownloadJob(id = 1))
        every { telegramSender.deleteMessage(100, 500) } just runs

        val started = start(OutputType.AUDIO)

        assertEquals(true, started)
        verify { telegramSender.deleteMessage(100, 500) }
        verify(exactly = 0) { downloadAnalytics.recordDownloadRequested(any(), any()) }
    }

    @Test
    fun removesStatusWhenJobCreationFails() {
        every {
            platformResolver.resolve(URL, OutputType.VIDEO)
        } returns resolvedDownload(OutputType.VIDEO)
        every {
            telegramSender.sendStatus(100, TelegramDownloadStatus.QUEUED)
        } returns 500
        every {
            downloadJobService.createJob(any())
        } throws IllegalStateException("database error")
        every { telegramSender.deleteMessage(100, 500) } just runs

        assertFailsWith<IllegalStateException> {
            start(OutputType.VIDEO)
        }

        verify { telegramSender.deleteMessage(100, 500) }
    }

    private fun start(outputType: OutputType): Boolean {
        return starter.start(
            telegramUserId = 300,
            telegramChatId = 100,
            telegramUpdateId = 400,
            telegramRequestMessageId = 200,
            url = URL,
            outputType = outputType,
        )
    }

    private fun resolvedDownload(outputType: OutputType): ResolvedDownload {
        return ResolvedDownload(
            identity = DownloadIdentity(
                originalUrl = URL,
                normalizedUrl = URL,
                cacheKey = "video",
            ),
            request = DownloadRequest(
                originalUrl = URL,
                normalizedUrl = URL,
                outputType = outputType,
                formatSelector = "format",
                presetName = "preset",
            ),
        )
    }

    private companion object {
        private const val URL = "https://example.com/video"
    }
}
