package com.nkudrin713.kradnik.download.service

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.PlaylistDeliveryMode
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.repository.DownloadJobRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadJobServiceTest {
    private val repository: DownloadJobRepository = mockk()
    private val service = DownloadJobService(repository)

    @Test
    fun createsJobAtomicallyForTelegramUpdate() {
        val savedJob = slot<DownloadJob>()
        every { repository.lockTelegramUpdate(3) } returns 1
        every { repository.findByTelegramUpdateId(3) } returns null
        every { repository.save(capture(savedJob)) } answers { firstArg() }

        val created = service.createJob(command())
        val actual = savedJob.captured

        assertNotNull(created)
        assertEquals(1, actual.telegramUserId)
        assertEquals(2, actual.telegramChatId)
        assertEquals(3, actual.telegramUpdateId)
        assertEquals(4, actual.telegramRequestMessageId)
        assertEquals("https://example.com/raw", actual.originalUrl)
        assertEquals("https://example.com/normalized", actual.normalizedUrl)
        assertEquals("cache-key", actual.cacheKey)
        assertEquals(OutputType.AUDIO, actual.outputType)
        assertEquals(DownloadPlatform.YOUTUBE, actual.platform)
        assertEquals("preset", actual.downloadPreset)
        assertEquals("format", actual.selectedFormat)
        assertEquals(listOf("-x", "--audio-format", "mp3"), actual.downloadExtraArgs)
        assertEquals("Post text", actual.sourcePostText)
        assertEquals(PlaylistDeliveryMode.ZIP, actual.playlistDeliveryMode)
        assertEquals("My playlist", actual.playlistTitle)
        assertEquals("My playlist", DownloadSpec.fromJob(actual).playlistTitle)
        assertEquals(PlaylistDeliveryMode.ZIP, DownloadSpec.fromJob(actual).playlistDeliveryMode)
        assertEquals(10, actual.telegramStatusMessageId)
        verify { repository.lockTelegramUpdate(3) }
    }

    @Test
    fun returnsExistingJobForRepeatedTelegramUpdate() {
        val existing = job().apply { telegramUpdateId = 3 }
        every { repository.lockTelegramUpdate(3) } returns 1
        every { repository.findByTelegramUpdateId(3) } returns existing

        val created = service.createJob(command())

        assertNull(created)
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun failsWithBoundedDiagnostic() {
        every { repository.fail(1, any()) } returns 1
        service.markFailed(job(), "x".repeat(2000))
        verify { repository.fail(1, "x".repeat(1000)) }
    }

    @Test
    fun doesNotOverwriteTerminalJob() {
        every { repository.complete(1, "file") } returns 0
        assertFalse(service.markCompleted(job(), "file"))
    }

    private fun command(): CreateDownloadJobCommand {
        return CreateDownloadJobCommand(
            telegramUserId = 1,
            telegramChatId = 2,
            telegramUpdateId = 3,
            telegramRequestMessageId = 4,
            spec = DownloadSpec(
                originalUrl = "https://example.com/raw",
                normalizedUrl = "https://example.com/normalized",
                cacheKey = "cache-key",
                outputType = OutputType.AUDIO,
                platform = DownloadPlatform.YOUTUBE,
                presetName = "preset",
                formatSelector = "format",
                extraArgs = listOf("-x", "--audio-format", "mp3"),
                postText = "Post text",
                playlistDeliveryMode = PlaylistDeliveryMode.ZIP,
                playlistTitle = "My playlist",
            ),
            telegramStatusMessageId = 10,
        )
    }

    private fun job(): DownloadJob {
        return DownloadJob(
            id = 1,
            telegramChatId = 2,
        )
    }
}
