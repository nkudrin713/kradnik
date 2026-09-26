package com.nkudrin713.kradnik.download.source

import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.ytdlp.YtDlpPresets

/** Source selection, independent of the final product and Telegram delivery. */
data class SourceRequest(
    val originalUrl: String,
    val normalizedUrl: String,
    val platform: DownloadPlatform,
    val formatSelector: String,
    val extraArgs: List<String> = emptyList(),
    val presetName: String,
) {
    fun withAudioQuality(quality: String): SourceRequest = copy(extraArgs = YtDlpPresets.withAudioQuality(extraArgs, quality))
}

enum class SourceMediaType { VIDEO, AUDIO, IMAGES }
