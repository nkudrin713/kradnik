package com.nkudrin713.kradnik.download.playlist

import com.nkudrin713.kradnik.download.choice.DownloadChoiceMediaInfo
import com.nkudrin713.kradnik.download.choice.DownloadChoiceOptionSnapshot
import com.nkudrin713.kradnik.download.choice.DownloadChoicePlan
import com.nkudrin713.kradnik.download.choice.DownloadChoicePlanningException
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistDeliveryMode
import com.nkudrin713.kradnik.download.identity.ResultKeyFactory
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import com.nkudrin713.kradnik.download.source.PlaylistSourceAdapter
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessages
import org.springframework.stereotype.Component

@Component
class PlaylistChoicePlanner(
    private val sources: PlatformResolver,
    private val messages: TelegramMessages,
) {
    suspend fun planOrNull(url: String, language: BotLanguage): DownloadChoicePlan? {
        val source = sources.adapter(sources.platformOf(url)) as? PlaylistSourceAdapter ?: return null
        val spec = source.playlist(url, "${AUDIO_BITRATE_KBPS}K") ?: return null
        val entries = spec.playlistEntries
        if (entries.isEmpty()) {
            throw DownloadChoicePlanningException(messages.text(language, TelegramMessage.ERROR_NO_OPTIONS))
        }

        val durationSeconds = entries.mapNotNull(PlaylistAudioEntry::durationSeconds).sumOf(Int::toLong)
        val estimatedSize = durationSeconds.takeIf { it > 0 && entries.all { entry -> entry.durationSeconds != null } }?.let(::estimatedSize)
        val ranges = if (entries.size <= MAX_PLAYLIST_ITEMS) {
            listOf(PlaylistRange("playlist_all", entries, TelegramMessage.CHOICE_PLAYLIST_ALL, TelegramMessage.CHOICE_PLAYLIST_ZIP_ALL))
        } else {
            listOf(
                PlaylistRange("playlist_first", entries.take(MAX_PLAYLIST_ITEMS), TelegramMessage.CHOICE_PLAYLIST_FIRST, TelegramMessage.CHOICE_PLAYLIST_ZIP_FIRST),
                PlaylistRange("playlist_last", entries.takeLast(MAX_PLAYLIST_ITEMS), TelegramMessage.CHOICE_PLAYLIST_LAST, TelegramMessage.CHOICE_PLAYLIST_ZIP_LAST),
            )
        }
        val options = ranges.flatMap { range ->
            val knownSize = range.entries.takeIf { items -> items.all { it.durationSeconds != null } }
                ?.sumOf { estimatedSize(requireNotNull(it.durationSeconds).toLong()) }
            val audioOption = DownloadChoiceOptionSnapshot(
                key = range.key,
                label = messages.text(language, range.label, range.entries.size),
                sizeBytes = knownSize,
                approximateSize = true,
                available = true,
                unavailableReason = null,
                spec = spec.copy(
                    cacheKey = ResultKeyFactory.choice(spec.cacheKey, range.key),
                    playlistEntries = range.entries,
                ),
            )
            val zipOption = audioOption.copy(
                key = "${range.key}_zip",
                label = messages.text(language, range.zipLabel, range.entries.size),
                sizeBytes = knownSize,
                available = true,
                unavailableReason = null,
                spec = audioOption.spec.copy(
                    cacheKey = ResultKeyFactory.playlistZip(audioOption.spec.cacheKey),
                    playlistDeliveryMode = PlaylistDeliveryMode.ZIP,
                ),
            )
            listOf(audioOption, zipOption)
        }
        return DownloadChoicePlan(
            mediaInfo = DownloadChoiceMediaInfo(
                title = spec.playlistTitle,
                durationSeconds = durationSeconds.takeIf { it > 0 },
                playlistCount = entries.size,
                estimatedSizeBytes = estimatedSize,
                audioBitrateKbps = AUDIO_BITRATE_KBPS,
            ),
            options = options,
        )
    }

    private fun estimatedSize(durationSeconds: Long): Long = durationSeconds * AUDIO_BITRATE_KBPS * 1000 / 8

    private data class PlaylistRange(
        val key: String,
        val entries: List<PlaylistAudioEntry>,
        val label: TelegramMessage,
        val zipLabel: TelegramMessage,
    )

    private companion object {
        const val MAX_PLAYLIST_ITEMS = 100
        const val AUDIO_BITRATE_KBPS = 320L
    }
}
