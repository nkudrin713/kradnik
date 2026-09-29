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
        val queue = view.queueKeyboard(42, BotLanguage.EN).inlineKeyboard().first().single()

        assertEquals("✖️ Отмена", cancel.text)
        assertEquals(
            DownloadJobCallback(DownloadJobAction.CANCEL, 42),
            DownloadJobCallback.parse(requireNotNull(cancel.callbackData)),
        )
        assertEquals("↩️ Back to options", back.text)
        assertEquals(DownloadJobCallback(DownloadJobAction.REFRESH_QUEUE, 42), DownloadJobCallback.parse(requireNotNull(queue.callbackData)))
        assertEquals(
            DownloadJobCallback(DownloadJobAction.BACK, 42),
            DownloadJobCallback.parse(requireNotNull(back.callbackData)),
        )
        assertNull(DownloadJobCallback.parse("job:unknown:42"))
        assertNull(DownloadJobCallback.parse("job:cancel:not-a-number"))
    }

    @Test
    fun offersBothRetryModesForZipAndAudioOnlyForAlbums() {
        val zip = view.retryKeyboard(42, BotLanguage.RU, 3, includeZip = true).inlineKeyboard().map { it.single() }
        val audio = view.retryKeyboard(42, BotLanguage.EN, 3, includeZip = false).inlineKeyboard().map { it.single() }

        assertEquals(listOf("🔄 Повторить 3 как аудио", "📦 Повторить 3 в ZIP"), zip.map { it.text })
        assertEquals(listOf(DownloadJobAction.RETRY_AUDIO, DownloadJobAction.RETRY_ZIP), zip.map { DownloadJobCallback.parse(requireNotNull(it.callbackData))?.action })
        assertEquals(listOf("🔄 Retry 3 as audio"), audio.map { it.text })
    }
}
