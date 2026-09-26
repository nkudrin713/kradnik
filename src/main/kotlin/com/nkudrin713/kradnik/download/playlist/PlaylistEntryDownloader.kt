package com.nkudrin713.kradnik.download.playlist

import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.ytdlp.YtDlpPresets
import com.nkudrin713.kradnik.ytdlp.YtDlpService
import org.springframework.stereotype.Component
import java.nio.file.Path

/** The caller owns the destination and decides when the downloaded file can be deleted. */
@Component
class PlaylistEntryDownloader(private val ytDlpService: YtDlpService) {
    fun cacheKey(entry: PlaylistAudioEntry): String = "youtube:video:${entry.videoId}:audio:youtube_audio:audio:96K"

    suspend fun download(entry: PlaylistAudioEntry, destination: Path): DownloadedFile = ytDlpService.download(
        DownloadSpec(
            originalUrl = entry.url,
            normalizedUrl = "https://www.youtube.com/watch?v=${entry.videoId}",
            cacheKey = cacheKey(entry),
            outputType = OutputType.AUDIO,
            platform = DownloadPlatform.YOUTUBE,
            formatSelector = YtDlpPresets.YOUTUBE_AUDIO_FORMAT,
            extraArgs = YtDlpPresets.PLAYLIST_AUDIO_ARGS,
            presetName = "youtube_audio",
        ),
        destination,
    )
}
