package com.nkudrin713.kradnik.download

import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.source.InstagramSourceAdapter
import com.nkudrin713.kradnik.download.source.PreparedSource
import com.nkudrin713.kradnik.download.source.SourceAdapter
import com.nkudrin713.kradnik.download.source.SourceMediaType
import com.nkudrin713.kradnik.download.source.SourceRequest
import com.nkudrin713.kradnik.download.source.YtDlpSourceAdapter
import org.springframework.stereotype.Component
import java.nio.file.Path

/** Explicit source registry. Output processing and delivery belong to callers. */
@Component
class DownloadEngine(ytDlp: YtDlpSourceAdapter, instagram: InstagramSourceAdapter) {
    private val adapters: Map<DownloadPlatform, SourceAdapter> = mapOf(
        DownloadPlatform.YOUTUBE to ytDlp,
        DownloadPlatform.VK to ytDlp,
        DownloadPlatform.INSTAGRAM to instagram,
    )

    suspend fun prepare(request: SourceRequest, catalog: Boolean = false): PreparedSource = adapters.getValue(request.platform).prepare(request, catalog)

    suspend fun download(request: SourceRequest, type: SourceMediaType, prepared: PreparedSource, directory: Path): DownloadedFile = adapters.getValue(request.platform).download(request, type, prepared, directory)
}
