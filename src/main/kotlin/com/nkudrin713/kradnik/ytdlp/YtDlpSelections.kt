package com.nkudrin713.kradnik.ytdlp

import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.source.MediaSelection

/** Serializes output choices into the existing menu/job format without changing old snapshots. */
internal object YtDlpSelections {
    fun select(spec: DownloadSpec, selection: MediaSelection): DownloadSpec = when (selection) {
        MediaSelection.OriginalVideo -> spec.copy(presetName = "${spec.platform.dbValue}_video_original")

        is MediaSelection.Video -> spec.copy(
            formatSelector = selection.audioId?.let { "${selection.videoId}+$it" } ?: selection.videoId,
            presetName = "${spec.platform.dbValue}_video_${selection.key.removePrefix("video_")}",
        )

        is MediaSelection.Audio -> spec.copy(extraArgs = YtDlpPresets.withAudioQuality(spec.extraArgs, selection.quality))

        MediaSelection.Cover -> spec.copy(outputType = OutputType.COVER, formatSelector = "best", extraArgs = emptyList(), presetName = "${spec.platform.dbValue}_cover")

        is MediaSelection.Post -> spec.copy(outputType = OutputType.POST, presetName = "${spec.platform.dbValue}_post", postText = selection.text)

        MediaSelection.VideoCollection -> spec.copy(outputType = OutputType.POST, presetName = "${spec.platform.dbValue}_videos")
    }
}
