package com.nkudrin713.kradnik.download.source

import com.nkudrin713.kradnik.download.platform.DownloadPlatform

/** Saved source settings are opaque to workflows. Audio quality is a runtime override; the saved snapshot stays intact. */
data class SourceRequest(
    val originalUrl: String,
    val normalizedUrl: String,
    val platform: DownloadPlatform,
    val formatSelector: String,
    val extraArgs: List<String> = emptyList(),
    val presetName: String,
    val audioQuality: String? = null,
) {
    fun withAudioQuality(quality: String): SourceRequest = copy(audioQuality = quality)
}

enum class SourceMediaType { VIDEO, AUDIO, IMAGES }
