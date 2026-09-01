package com.nkudrin713.kradnik.telegram.localization

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramUserPreferenceServiceTest {
    private val repository: TelegramUserPreferenceRepository = mockk()
    private val service = TelegramUserPreferenceService(repository)

    @Test
    fun defaultsToEnglishWhenLanguageWasNotSelected() {
        every { repository.findById(300) } returns Optional.empty()

        assertEquals(BotLanguage.EN, service.resolveLanguage(300))
    }

    @Test
    fun persistsExplicitLanguageSelection() {
        val preference = slot<TelegramUserPreference>()
        every { repository.findById(300) } returns Optional.empty()
        every { repository.save(capture(preference)) } answers { preference.captured }

        service.selectLanguage(300, BotLanguage.RU)

        assertEquals(300, preference.captured.telegramUserId)
        assertEquals(BotLanguage.RU, preference.captured.language)
        verify(exactly = 1) { repository.save(any()) }
    }
}
