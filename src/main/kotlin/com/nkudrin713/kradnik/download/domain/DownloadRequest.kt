package com.nkudrin713.kradnik.download.domain

import com.nkudrin713.kradnik.download.source.SourceRequest

sealed interface DownloadRequest

data class SingleMediaRequest(
    val source: SourceRequest,
    val outputType: OutputType,
    val cacheKey: String,
    val postText: String? = null,
) : DownloadRequest {
    fun withAudioQuality(quality: String): SingleMediaRequest = copy(source = source.withAudioQuality(quality))
}

data class PlaylistAudioRequest(
    val entries: List<PlaylistAudioEntry>,
    val title: String?,
    val deliveryMode: PlaylistDeliveryMode,
    val completedEntries: List<PlaylistAudioResult>,
) : DownloadRequest
