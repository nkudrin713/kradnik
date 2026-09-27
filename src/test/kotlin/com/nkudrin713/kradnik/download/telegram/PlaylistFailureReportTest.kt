package com.nkudrin713.kradnik.download.telegram

import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.playlist.PlaylistItemFailure
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.telegramMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistFailureReportTest {
    private val report = PlaylistFailureReport(telegramMessages())

    @Test
    fun escapesTitlesUsesSafeLinksAndKeepsOriginalPositionsAndLegacyReasons() {
        val entries = listOf(PlaylistAudioEntry(9, "abc\"&", "javascript:bad", "<script> & title", null), PlaylistAudioEntry(3, "def", "url", "Other", null))
        val failures = listOf(PlaylistAudioResult(9, error = "Video has been removed"), PlaylistAudioResult(3, failure = PlaylistItemFailure.UPLOAD))
        val text = report.render("Failed", entries, failures, BotLanguage.EN).single()
        assertTrue(text.indexOf("3. <a") < text.indexOf("9. <a"))
        assertTrue("&lt;script&gt; &amp; title" in text)
        assertTrue("https://www.youtube.com/watch?v=abc%22%26" in text)
        assertTrue("Recording deleted" in text)
        assertTrue("Could not send to Telegram" in text)
        assertFalse("javascript:" in text)
    }

    @Test
    fun splitsHundredLongTitlesWithoutBreakingLinksOrUnicode() {
        val entries = (1..100).map { PlaylistAudioEntry(it, "id-$it", "url", "😀<&".repeat(200), null) }
        val chunks = report.render("Failed", entries, entries.map { PlaylistAudioResult(it.position, failure = PlaylistItemFailure.TIMEOUT) }, BotLanguage.RU_INFORMAL)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 3500 })
        assertEquals(100, chunks.sumOf { Regex("</a>").findAll(it).count() })
        chunks.forEach { assertEquals(Regex("<a ").findAll(it).count(), Regex("</a>").findAll(it).count()) }
        assertTrue("100. <a" in chunks.last())
    }
}
