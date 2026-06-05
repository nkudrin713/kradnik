package com.nkudrin713.kradnik.settings

import com.nkudrin713.kradnik.download.domain.OutputType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DownloadSettingsServiceTest {
    private val repository: DownloadSettingsRepository = mockk()
    private val service = DownloadSettingsService(repository)

    @Test
    fun returnsVideoByDefault() {
        every { repository.findByChatId(100) } returns null

        val actual = service.getOutputType(100)

        assertEquals(OutputType.VIDEO, actual)
    }

    @Test
    fun returnsSavedOutputType() {
        every { repository.findByChatId(100) } returns DownloadSettings(
            chatId = 100,
            mode = OutputType.AUDIO,
        )

        val actual = service.getOutputType(100)

        assertEquals(OutputType.AUDIO, actual)
    }

    @Test
    fun createsSettingsWhenMissing() {
        every { repository.findByChatId(100) } returns null
        every { repository.save(any()) } answers { firstArg() }

        val actual = service.setMode(
            DownloadSettingsDto(
                chatId = 100,
                mode = "audio",
            )
        )

        assertEquals(100, actual.chatId)
        assertEquals(OutputType.AUDIO, actual.mode)
        verify { repository.save(any()) }
    }

    @Test
    fun updatesExistingSettings() {
        val settings = DownloadSettings(
            chatId = 100,
            mode = OutputType.VIDEO,
        )
        every { repository.findByChatId(100) } returns settings

        val actual = service.setMode(
            DownloadSettingsDto(
                chatId = 100,
                mode = "audio",
            )
        )

        assertEquals(settings, actual)
        assertEquals(OutputType.AUDIO, settings.mode)
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun rejectsInvalidMode() {
        assertFailsWith<IllegalArgumentException> {
            service.setMode(
                DownloadSettingsDto(
                    chatId = 100,
                    mode = "invalid",
                )
            )
        }
    }
}
