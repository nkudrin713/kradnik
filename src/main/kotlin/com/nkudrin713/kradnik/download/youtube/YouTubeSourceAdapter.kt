package com.nkudrin713.kradnik.download.youtube

import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.identity.ResultKeyFactory
import com.nkudrin713.kradnik.download.identity.extractQueryParameter
import com.nkudrin713.kradnik.download.identity.parseUrlOrNull
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.platform.PlatformDownloadSpecs
import com.nkudrin713.kradnik.download.source.MediaSelection
import com.nkudrin713.kradnik.download.source.PlaylistSourceAdapter
import com.nkudrin713.kradnik.download.source.PreparedSource
import com.nkudrin713.kradnik.download.source.SourceMediaType
import com.nkudrin713.kradnik.download.source.SourceRequest
import com.nkudrin713.kradnik.ytdlp.YtDlpPreparedSource
import com.nkudrin713.kradnik.ytdlp.YtDlpPresets
import com.nkudrin713.kradnik.ytdlp.YtDlpSelections
import com.nkudrin713.kradnik.ytdlp.YtDlpService
import com.nkudrin713.kradnik.ytdlp.toMediaMetadata
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path

@Component
class YouTubeSourceAdapter(
    private val ytDlp: YtDlpService,
    @Value($$"${download.youtube.po-token-provider-url:}") private val poTokenProviderUrl: String = "",
) : PlaylistSourceAdapter {
    override val platform = DownloadPlatform.YOUTUBE
    override fun supports(url: String): Boolean = isYouTubeHost(url)
    override fun select(spec: DownloadSpec, selection: MediaSelection): DownloadSpec = YtDlpSelections.select(spec, selection)
    override fun resolve(url: String): PlatformDownloadSpecs {
        val media = parseYouTubeMediaUrl(url)

        return PlatformDownloadSpecs(
            video = DownloadSpec(
                originalUrl = media.original,
                normalizedUrl = media.normalized,
                cacheKey = ResultKeyFactory.source("youtube:video:${media.id}", OutputType.VIDEO, "youtube_h264_mobile_2gb"),
                outputType = OutputType.VIDEO,
                platform = DownloadPlatform.YOUTUBE,
                presetName = "youtube_h264_mobile_2gb",
                formatSelector =
                "bv[height<=1280][vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a][ext=m4a]/" +
                    "b[height<=1280][vcodec^=avc1][ext=mp4]/" +
                    "b[height<=1280]/best",
                extraArgs = YtDlpPresets.MERGE_MP4_ARGS,
            ),
            audio = DownloadSpec(
                originalUrl = media.original,
                normalizedUrl = media.normalized,
                cacheKey = ResultKeyFactory.source("youtube:video:${media.id}", OutputType.AUDIO, "youtube_audio"),
                outputType = OutputType.AUDIO,
                platform = DownloadPlatform.YOUTUBE,
                presetName = "youtube_audio",
                formatSelector = YtDlpPresets.AUDIO_FORMAT,
                extraArgs = YtDlpPresets.TAGGED_MP3_ARGS,
            ),
        )
    }
    override suspend fun prepare(request: SourceRequest, catalog: Boolean): PreparedSource {
        val metadata = if (catalog) ytDlp.extractCatalogMetadata(request, sourceOptions()) else ytDlp.extractMetadata(request, sourceOptions())
        return YtDlpPreparedSource(metadata.toMediaMetadata())
    }

    override suspend fun download(request: SourceRequest, type: SourceMediaType, prepared: PreparedSource, directory: Path): DownloadedFile {
        require(prepared is YtDlpPreparedSource)
        return ytDlp.download(request, directory, sourceOptions())
    }

    override suspend fun playlist(url: String, audioQuality: String): DownloadSpec? {
        if (!supports(url)) return null
        val original = url.trim()
        val uri = parseUrlOrNull(original) ?: return null
        val id = extractQueryParameter(uri, "list")?.takeIf(String::isNotBlank) ?: return null
        val metadata = ytDlp.extractPlaylistMetadata(original, sourceOptions())
        val entries = metadata.entries.orEmpty().mapIndexedNotNull { index, item ->
            val sourceId = item.id?.takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
            PlaylistAudioEntry(
                position = index + 1,
                sourceId = sourceId,
                url = item.webpageUrl?.takeIf(String::isNotBlank)
                    ?: item.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    ?: "https://www.youtube.com/watch?v=$sourceId",
                title = item.title?.takeIf(String::isNotBlank) ?: "Audio ${index + 1}",
                durationSeconds = item.duration?.takeIf { it >= BigDecimal.ZERO }?.setScale(0, RoundingMode.DOWN)?.toInt(),
            )
        }
        return DownloadSpec(
            originalUrl = original,
            normalizedUrl = "https://www.youtube.com/playlist?list=$id",
            cacheKey = "youtube:playlist:$id:audio:${audioQuality.removeSuffix("K")}:cbr:v1",
            outputType = OutputType.AUDIO,
            platform = platform,
            presetName = "youtube_playlist_audio_${audioQuality.removeSuffix("K")}",
            formatSelector = YtDlpPresets.AUDIO_FORMAT,
            extraArgs = YtDlpPresets.playlistAudioArgs(audioQuality),
            workloadType = DownloadWorkloadType.PLAYLIST_AUDIO,
            playlistTitle = metadata.title,
            playlistEntries = entries,
        )
    }

    override fun playlistItemUrl(entry: PlaylistAudioEntry): String = "https://www.youtube.com/watch?v=" + URLEncoder.encode(entry.sourceId, StandardCharsets.UTF_8)

    override fun playlistItemCacheKey(entry: PlaylistAudioEntry, source: SourceRequest?): String {
        val args = source?.extraArgs ?: YtDlpPresets.playlistAudioArgs("96K")
        val quality = args.getOrNull(args.indexOf("--audio-quality") + 1)?.takeIf { "--audio-quality" in args } ?: "96K"
        return if (quality == "96K") {
            ResultKeyFactory.choice(ResultKeyFactory.source("youtube:video:${entry.sourceId}", OutputType.AUDIO, "youtube_audio"), ResultKeyFactory.audioSuffix("96K"))
        } else {
            "youtube:video:${entry.sourceId}:playlist_audio:$quality:cbr:v1"
        }
    }

    override suspend fun downloadPlaylistItem(entry: PlaylistAudioEntry, directory: Path, source: SourceRequest?): DownloadedFile {
        val request = (source ?: SourceRequest(entry.url, playlistItemUrl(entry), platform, YtDlpPresets.AUDIO_FORMAT, YtDlpPresets.playlistAudioArgs("96K"), "youtube_audio"))
            .copy(originalUrl = entry.url, normalizedUrl = "https://www.youtube.com/watch?v=${entry.sourceId}")
        val selected = when (request.formatSelector) {
            YtDlpPresets.AUDIO_FORMAT, "ba", "bestaudio" -> request.copy(formatSelector = YtDlpPresets.AUDIO_FORMAT_WITH_VIDEO_FALLBACK)
            else -> request
        }
        return ytDlp.download(selected, directory, sourceOptions())
    }

    private fun sourceOptions(): List<String> {
        val provider = poTokenProviderUrl.trim().trimEnd('/')
        return if (provider.isEmpty()) emptyList() else listOf("--extractor-args", "youtube:player_client=mweb", "--extractor-args", "youtubepot-bgutilhttp:base_url=$provider")
    }
}
