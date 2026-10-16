package com.nkudrin713.kradnik.download

import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.source.InstagramSourceAdapter
import com.nkudrin713.kradnik.download.source.PostSourceAdapter
import com.nkudrin713.kradnik.download.source.PreparedPostSource
import com.nkudrin713.kradnik.download.source.PreparedSource
import com.nkudrin713.kradnik.download.source.SourceAdapter
import com.nkudrin713.kradnik.download.source.SourceMediaType
import com.nkudrin713.kradnik.download.source.SourceRequest
import com.nkudrin713.kradnik.download.source.ThreadsSourceAdapter
import com.nkudrin713.kradnik.download.source.VkSourceAdapter
import com.nkudrin713.kradnik.download.source.YtDlpSourceAdapter
import org.springframework.stereotype.Component
import java.nio.file.Path

/** Explicit source registry. Output processing and delivery belong to callers. */
@Component
class DownloadEngine(ytDlp: YtDlpSourceAdapter, instagram: InstagramSourceAdapter, threads: ThreadsSourceAdapter, vk: VkSourceAdapter) {
    private val adapters: Map<DownloadPlatform, SourceAdapter> = mapOf(
        DownloadPlatform.YOUTUBE to ytDlp,
        DownloadPlatform.VK to vk,
        DownloadPlatform.INSTAGRAM to instagram,
        DownloadPlatform.THREADS to threads,
    )

    suspend fun prepare(request: SourceRequest, catalog: Boolean = false): PreparedSource = adapters.getValue(request.platform).prepare(request, catalog)

    suspend fun preparePost(request: SourceRequest): PreparedPostSource = prepare(request) as? PreparedPostSource
        ?: throw DownloadFailure(DownloadFailureReason.METADATA_UNAVAILABLE, "Source does not provide a post")

    suspend fun download(request: SourceRequest, type: SourceMediaType, prepared: PreparedSource, directory: Path): DownloadedFile = adapters.getValue(request.platform).download(request, type, prepared, directory)

    suspend fun downloadPostItem(request: SourceRequest, prepared: PreparedPostSource, index: Int, directory: Path): DownloadedFile = (adapters.getValue(request.platform) as PostSourceAdapter).downloadPostItem(request, prepared, index, directory)
}
