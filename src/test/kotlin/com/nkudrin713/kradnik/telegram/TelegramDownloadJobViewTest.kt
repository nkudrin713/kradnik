package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.telegramMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TelegramDownloadJobViewTest {
    private val view = TelegramDownloadJobView(telegramMessages())

    @Test
    fun createsCancelAndBackCallbacks() {
        val cancel = view.cancelKeyboard(42, BotLanguage.RU).inlineKeyboard().single().single()
        val back = view.backKeyboard(42, BotLanguage.EN).inlineKeyboard().single().single()

        assertEquals("Отмена", cancel.text)
        assertEquals(
            DownloadJobCallback(DownloadJobAction.CANCEL, 42),
            DownloadJobCallback.parse(requireNotNull(cancel.callbackData)),
        )
        assertEquals("Back to options", back.text)
        assertEquals(
            DownloadJobCallback(DownloadJobAction.BACK, 42),
            DownloadJobCallback.parse(requireNotNull(back.callbackData)),
        )
        assertNull(DownloadJobCallback.parse("job:unknown:42"))
        assertNull(DownloadJobCallback.parse("job:cancel:not-a-number"))
    }
}
