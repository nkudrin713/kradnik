package com.nkudrin713.kradnik.settings

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadSettingsServiceTest {
    private val repository: DownloadSettingsRepository = mockk()
    private val service = DownloadSettingsService(repository)

    @Test
    fun returnsAskByDefault() {
        every { repository.findByChatId(100) } returns null

        val actual = service.getMode(100)

        assertEquals(DownloadMode.ASK, actual)
    }

    @Test
    fun returnsSavedMode() {
        every { repository.findByChatId(100) } returns DownloadSettings(
            chatId = 100,
            mode = DownloadMode.AUDIO,
        )

        val actual = service.getMode(100)

        assertEquals(DownloadMode.AUDIO, actual)
    }

    @Test
    fun createsDefaultSettingsWhenFirstMenuIsOpened() {
        every { repository.findByChatId(100) } returns null
        every { repository.save(any()) } answers { firstArg() }

        val previousMessageId = service.replaceModeMenu(
            chatId = 100,
            messageId = 200,
        )

        assertEquals(null, previousMessageId)
        verify {
            repository.save(
                match {
                    it.chatId == 100L &&
                            it.mode == DownloadMode.ASK &&
                            it.modeMenuMessageId == 200
                }
            )
        }
    }

    @Test
    fun replacesCurrentMenuAndReturnsPreviousMessageId() {
        val settings = DownloadSettings(
            chatId = 100,
            mode = DownloadMode.VIDEO,
            modeMenuMessageId = 150,
        )
        every { repository.findByChatId(100) } returns settings

        val previousMessageId = service.replaceModeMenu(
            chatId = 100,
            messageId = 200,
        )

        assertEquals(150, previousMessageId)
        assertEquals(200, settings.modeMenuMessageId)
    }

    @Test
    fun selectsModeFromCurrentMenuAndClearsIt() {
        val settings = DownloadSettings(
            chatId = 100,
            mode = DownloadMode.ASK,
            modeMenuMessageId = 200,
        )
        every { repository.findByChatId(100) } returns settings

        val selected = service.selectMode(
            chatId = 100,
            menuMessageId = 200,
            mode = DownloadMode.AUDIO,
        )

        assertEquals(true, selected)
        assertEquals(DownloadMode.AUDIO, settings.mode)
        assertEquals(null, settings.modeMenuMessageId)
    }

    @Test
    fun rejectsSelectionFromStaleMenu() {
        val settings = DownloadSettings(
            chatId = 100,
            mode = DownloadMode.ASK,
            modeMenuMessageId = 201,
        )
        every { repository.findByChatId(100) } returns settings

        val selected = service.selectMode(
            chatId = 100,
            menuMessageId = 200,
            mode = DownloadMode.AUDIO,
        )

        assertEquals(false, selected)
        assertEquals(DownloadMode.ASK, settings.mode)
        assertEquals(201, settings.modeMenuMessageId)
    }
}
