package com.nkudrin713.kradnik.download.repository

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.domain.PlaylistAudioRequest
import com.nkudrin713.kradnik.download.domain.SingleMediaRequest
import com.nkudrin713.kradnik.download.source.SourceRequest

/** Reads persisted selections verbatim; never rebuilds presets or versions an existing job key. */
object DownloadRequestMapper {
    fun single(job: DownloadJob): SingleMediaRequest = single(DownloadSpec.fromJob(job))

    fun single(spec: DownloadSpec): SingleMediaRequest {
        require(spec.workloadType == DownloadWorkloadType.SINGLE)
        return SingleMediaRequest(source(spec), spec.outputType, spec.cacheKey, spec.postText)
    }

    fun source(spec: DownloadSpec): SourceRequest = SourceRequest(
        spec.originalUrl,
        spec.normalizedUrl,
        spec.platform,
        spec.formatSelector,
        spec.extraArgs.toList(),
        spec.presetName,
    )

    fun playlist(job: DownloadJob): PlaylistAudioRequest {
        require(job.workloadType == DownloadWorkloadType.PLAYLIST_AUDIO)
        return PlaylistAudioRequest(job.playlistEntries.toList(), job.playlistTitle, job.playlistDeliveryMode, job.playlistResults.toList())
    }
}
