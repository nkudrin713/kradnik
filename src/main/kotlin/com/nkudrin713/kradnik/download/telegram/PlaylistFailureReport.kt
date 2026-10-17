package com.nkudrin713.kradnik.download.telegram

import com.nkudrin713.kradnik.download.DownloadEngine
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.domain.PlaylistItemFailure
import com.nkudrin713.kradnik.localization.BotLanguage
import com.nkudrin713.kradnik.localization.MessageKey
import com.nkudrin713.kradnik.localization.Messages

/** Bounds encoded HTML as well as visible text; every chunk has complete links and original positions. */
class PlaylistFailureReport(private val messages: Messages, private val engine: DownloadEngine) {
    fun render(header: String, entries: List<PlaylistAudioEntry>, failures: List<PlaylistAudioResult>, language: BotLanguage): List<String> {
        val byPosition = entries.associateBy { it.position }
        val chunks = mutableListOf<String>()
        var chunk = escape(header)
        failures.sortedBy { it.position }.forEach { failure ->
            val entry = byPosition[failure.position] ?: return@forEach
            val title = entry.title.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ")
                .let { it.substring(0, it.offsetByCodePoints(0, minOf(160, it.codePointCount(0, it.length)))) }
            val url = engine.playlistSource(entry).playlistItemUrl(entry)
            val reason = failure.failure ?: PlaylistItemFailure.UNKNOWN
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

private val PlaylistItemFailure.message: MessageKey
    get() = when (this) {
        PlaylistItemFailure.DELETED -> MessageKey.PLAYLIST_ITEM_DELETED
        PlaylistItemFailure.PRIVATE -> MessageKey.PLAYLIST_ITEM_PRIVATE
        PlaylistItemFailure.GEO_BLOCKED -> MessageKey.PLAYLIST_ITEM_GEO_BLOCKED
        PlaylistItemFailure.UNAVAILABLE -> MessageKey.PLAYLIST_ITEM_UNAVAILABLE
        PlaylistItemFailure.AUTHENTICATION -> MessageKey.PLAYLIST_ITEM_AUTHENTICATION
        PlaylistItemFailure.NO_AUDIO -> MessageKey.PLAYLIST_ITEM_NO_AUDIO
        PlaylistItemFailure.TOO_LARGE -> MessageKey.PLAYLIST_ITEM_TOO_LARGE
        PlaylistItemFailure.TIMEOUT -> MessageKey.PLAYLIST_ITEM_TIMEOUT
        PlaylistItemFailure.RATE_LIMITED -> MessageKey.PLAYLIST_ITEM_RATE_LIMITED
        PlaylistItemFailure.NETWORK -> MessageKey.PLAYLIST_ITEM_NETWORK
        PlaylistItemFailure.SOURCE -> MessageKey.PLAYLIST_ITEM_SOURCE
        PlaylistItemFailure.UPLOAD -> MessageKey.PLAYLIST_ITEM_UPLOAD
        PlaylistItemFailure.UNKNOWN -> MessageKey.PLAYLIST_ITEM_UNKNOWN
    }
