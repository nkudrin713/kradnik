package com.nkudrin713.kradnik.download.telegram

import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.playlist.PlaylistItemFailure
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessages
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Bounds encoded HTML as well as visible text; every chunk has complete links and original positions. */
class PlaylistFailureReport(private val messages: TelegramMessages) {
    fun render(header: String, entries: List<PlaylistAudioEntry>, failures: List<PlaylistAudioResult>, language: BotLanguage): List<String> {
        val byPosition = entries.associateBy { it.position }
        val chunks = mutableListOf<String>()
        var chunk = escape(header)
        failures.sortedBy { it.position }.forEach { failure ->
            val entry = byPosition[failure.position] ?: return@forEach
            val title = entry.title.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ")
                .let { it.substring(0, it.offsetByCodePoints(0, minOf(160, it.codePointCount(0, it.length)))) }
            val url = "https://www.youtube.com/watch?v=" + URLEncoder.encode(entry.videoId, StandardCharsets.UTF_8)
            val reason = PlaylistItemFailure.forResult(failure)
            val line = "${entry.position}. <a href=\"${escape(url)}\">${escape(title)}</a>\n${escape(messages.text(language, reason.message))}"
            if (chunk.length + line.length + 2 > 3500) {
                chunks += chunk
                chunk = ""
            }
            chunk += (if (chunk.isEmpty()) "" else "\n\n") + line
        }
        if (chunk.isNotEmpty()) chunks += chunk
        return chunks
    }

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
