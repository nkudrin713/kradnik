package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.settings.DownloadMode
import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramModeViewTest {
    private val view = TelegramModeView()

    @Test
    fun showsThreeModesAndMarksCurrentOne() {
        val keyboard = view.keyboard(DownloadMode.ASK).inlineKeyboard()

        assertEquals(listOf("Видео", "Звук"), keyboard[0].map { it.text })
        assertEquals(listOf("mode:video", "mode:audio"), keyboard[0].map { it.callbackData })
        assertEquals("✅ Спрашивать", keyboard[1].single().text)
        assertEquals("mode:ask", keyboard[1].single().callbackData)
    }
}
