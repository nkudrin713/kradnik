package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.domain.OutputType
import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramDownloadChoiceViewTest {
    private val view = TelegramDownloadChoiceView()

    @Test
    fun createsChoiceCallbacksBoundToOriginalUpdate() {
        val keyboard = view.keyboard(400).inlineKeyboard().single()

        assertEquals(listOf("Видео", "Звук"), keyboard.map { it.text })
        assertEquals(
            listOf("download:400:video", "download:400:audio"),
            keyboard.map { it.callbackData },
        )
    }

    @Test
    fun parsesValidCallbackAndRejectsInvalidOne() {
        assertEquals(
            DownloadChoiceCallback(
                telegramUpdateId = 400,
                outputType = OutputType.AUDIO,
            ),
            DownloadChoiceCallback.parse("download:400:audio"),
        )
        assertEquals(null, DownloadChoiceCallback.parse("download:invalid:audio"))
        assertEquals(null, DownloadChoiceCallback.parse("mode:audio"))
    }
}
