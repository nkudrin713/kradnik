package com.nkudrin713.kradnik.download

import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import com.nkudrin713.kradnik.download.source.PlaylistSourceAdapter
import com.nkudrin713.kradnik.download.source.PostSourceAdapter
import com.nkudrin713.kradnik.download.source.PreparedPostSource
import com.nkudrin713.kradnik.download.source.PreparedSource
import com.nkudrin713.kradnik.download.source.SourceMediaType
import com.nkudrin713.kradnik.download.source.SourceRequest
import org.springframework.stereotype.Component
import java.nio.file.Path

/** Executes source operations through the registry. Output processing and delivery belong to callers. */
@Component
class DownloadEngine(private val sources: PlatformResolver) {
    fun playlistSource(entry: PlaylistAudioEntry, source: SourceRequest? = null): PlaylistSourceAdapter = sources.adapter(source?.platform ?: entry.platform) as? PlaylistSourceAdapter
        ?: throw DownloadFailure(DownloadFailureReason.METADATA_UNAVAILABLE, "Source does not provide a playlist")

    suspend fun prepare(request: SourceRequest, catalog: Boolean = false): PreparedSource = sources.adapter(request.platform).prepare(request, catalog)

    suspend fun preparePost(request: SourceRequest): PreparedPostSource = prepare(request) as? PreparedPostSource
        ?: throw DownloadFailure(DownloadFailureReason.METADATA_UNAVAILABLE, "Source does not provide a post")

    suspend fun download(request: SourceRequest, type: SourceMediaType, prepared: PreparedSource, directory: Path): DownloadedFile = sources.adapter(request.platform).download(request, type, prepared, directory)

    suspend fun downloadPostItem(request: SourceRequest, prepared: PreparedPostSource, index: Int, directory: Path): DownloadedFile = (
        sources.adapter(request.platform) as? PostSourceAdapter
            ?: throw DownloadFailure(DownloadFailureReason.METADATA_UNAVAILABLE, "Source does not provide a post")
        ).downloadPostItem(request, prepared, index, directory)
}
