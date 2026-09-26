package com.nkudrin713.kradnik.download.domain

import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.ytdlp.YtDlpPresets

/** Persisted menu snapshot. Runtime execution uses separate single-media and playlist requests. */
data class DownloadSpec(
    val originalUrl: String,
    val normalizedUrl: String,
    val cacheKey: String,
    val outputType: OutputType,
    val platform: DownloadPlatform,
    val formatSelector: String,
    val extraArgs: List<String> = emptyList(),
    val presetName: String,
    val postText: String? = null,
    val workloadType: DownloadWorkloadType = DownloadWorkloadType.SINGLE,
    val playlistDeliveryMode: PlaylistDeliveryMode = PlaylistDeliveryMode.AUDIO_MESSAGES,
    val playlistTitle: String? = null,
    val playlistEntries: List<PlaylistAudioEntry> = emptyList(),
) {
    fun withAudioQuality(audioQuality: String): DownloadSpec = copy(extraArgs = YtDlpPresets.withAudioQuality(extraArgs, audioQuality))

    companion object {
        fun fromJob(job: DownloadJob): DownloadSpec {
            return DownloadSpec(
                originalUrl = job.originalUrl,
                normalizedUrl = job.normalizedUrl,
                cacheKey = job.cacheKey,
                outputType = job.outputType,
                platform = job.platform,
                formatSelector = job.selectedFormat,
                extraArgs = job.downloadExtraArgs,
                presetName = job.downloadPreset,
                postText = job.sourcePostText,
                workloadType = job.workloadType,
                playlistEntries = job.playlistEntries,
                playlistDeliveryMode = job.playlistDeliveryMode,
                playlistTitle = job.playlistTitle,
            )
        }
    }
}
