package com.nkudrin713.kradnik.download.playlist

import com.nkudrin713.kradnik.download.choice.DownloadChoiceMediaInfo
import com.nkudrin713.kradnik.download.choice.DownloadChoiceOptionSnapshot
import com.nkudrin713.kradnik.download.choice.DownloadChoicePlan
import com.nkudrin713.kradnik.download.choice.DownloadChoicePlanningException
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistDeliveryMode
import com.nkudrin713.kradnik.download.identity.extractQueryParameter
import com.nkudrin713.kradnik.download.identity.parseUrlOrNull
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessages
import com.nkudrin713.kradnik.ytdlp.YtDlpPresets
import com.nkudrin713.kradnik.ytdlp.YtDlpService
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

@Component
class YouTubePlaylistPlanner(
    private val ytDlpService: YtDlpService,
    private val uploadLimits: TelegramUploadLimits,
    private val messages: TelegramMessages,
) {
    suspend fun planOrNull(url: String, language: BotLanguage): DownloadChoicePlan? {
        val originalUrl = url.trim()
        val uri = parseUrlOrNull(originalUrl) ?: return null
        if (uri.host?.lowercase() !in YOUTUBE_HOSTS) return null
        val playlistId = extractQueryParameter(uri, "list")?.takeIf(String::isNotBlank) ?: return null
        val metadata = ytDlpService.extractPlaylistMetadata(originalUrl)
        val entries = metadata.entries.orEmpty().mapIndexedNotNull { index, entry ->
            val videoId = entry.id?.takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
            val duration = entry.duration
                ?.takeIf { it >= BigDecimal.ZERO }
                ?.setScale(0, RoundingMode.DOWN)
                ?.toInt()
            PlaylistAudioEntry(
                position = index + 1,
                videoId = videoId,
                url = entry.webpageUrl?.takeIf(String::isNotBlank)
                    ?: entry.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    ?: "https://www.youtube.com/watch?v=$videoId",
                title = entry.title?.takeIf(String::isNotBlank) ?: "Audio ${index + 1}",
                durationSeconds = duration,
            )
        }
        if (entries.isEmpty()) {
            throw DownloadChoicePlanningException(messages.text(language, TelegramMessage.ERROR_NO_OPTIONS))
        }

        val durationSeconds = entries.mapNotNull(PlaylistAudioEntry::durationSeconds).sumOf(Int::toLong)
        val estimatedSize = durationSeconds.takeIf { it > 0 }?.let(::estimatedSize)
        val normalizedUrl = "https://www.youtube.com/playlist?list=$playlistId"
        val ranges = if (entries.size <= MAX_PLAYLIST_ITEMS) {
            listOf(PlaylistRange("playlist_all", entries, TelegramMessage.CHOICE_PLAYLIST_ALL, TelegramMessage.CHOICE_PLAYLIST_ZIP_ALL))
        } else {
            listOf(
                PlaylistRange("playlist_first", entries.take(MAX_PLAYLIST_ITEMS), TelegramMessage.CHOICE_PLAYLIST_FIRST, TelegramMessage.CHOICE_PLAYLIST_ZIP_FIRST),
                PlaylistRange("playlist_last", entries.takeLast(MAX_PLAYLIST_ITEMS), TelegramMessage.CHOICE_PLAYLIST_LAST, TelegramMessage.CHOICE_PLAYLIST_ZIP_LAST),
            )
        }
        val options = ranges.flatMap { range ->
            val oversized = range.entries.firstOrNull { entry ->
                entry.durationSeconds?.let { estimatedSize(it.toLong()) > uploadLimits.maxUploadBytes } == true
            }
            val audioOption = DownloadChoiceOptionSnapshot(
                key = range.key,
                label = messages.text(language, range.label, range.entries.size),
                sizeBytes = range.entries.mapNotNull(PlaylistAudioEntry::durationSeconds)
                    .sumOf { estimatedSize(it.toLong()) },
                approximateSize = true,
                available = oversized == null,
                unavailableReason = oversized?.let {
                    messages.text(language, TelegramMessage.ERROR_PLAYLIST_ITEM_TOO_LARGE, it.position)
                },
                spec = DownloadSpec(
                    originalUrl = originalUrl,
                    normalizedUrl = normalizedUrl,
                    cacheKey = "youtube:playlist:$playlistId:audio:96:${range.key}",
                    outputType = OutputType.AUDIO,
                    platform = DownloadPlatform.YOUTUBE,
                    formatSelector = YtDlpPresets.YOUTUBE_AUDIO_FORMAT,
                    extraArgs = YtDlpPresets.PLAYLIST_AUDIO_ARGS,
                    presetName = "youtube_playlist_audio_96",
                    workloadType = DownloadWorkloadType.PLAYLIST_AUDIO,
                    playlistEntries = range.entries,
                    playlistTitle = metadata.title,
                ),
            )
            val knownSize = range.entries.mapNotNull(PlaylistAudioEntry::durationSeconds).sumOf { estimatedSize(it.toLong()) }
            val zipTooLarge = knownSize > uploadLimits.maxUploadBytes
            val zipOption = audioOption.copy(
                key = "${range.key}_zip",
                label = messages.text(language, range.zipLabel, range.entries.size),
                sizeBytes = knownSize.takeIf { range.entries.all { it.durationSeconds != null } },
                available = !zipTooLarge,
                unavailableReason = if (zipTooLarge) messages.text(language, TelegramMessage.ERROR_PLAYLIST_ZIP_TOO_LARGE) else null,
                spec = audioOption.spec.copy(
                    cacheKey = "${audioOption.spec.cacheKey}:zip",
                    playlistDeliveryMode = PlaylistDeliveryMode.ZIP,
                ),
            )
            listOf(audioOption, zipOption)
        }
        return DownloadChoicePlan(
            mediaInfo = DownloadChoiceMediaInfo(
                title = metadata.title,
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
        const val AUDIO_BITRATE_KBPS = 96L
        val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")
    }
}
