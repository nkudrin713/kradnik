package com.nkudrin713.kradnik.download.source

import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.ytdlp.YtDlpService
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class YtDlpSourceAdapter(private val ytDlp: YtDlpService) : SourceAdapter {
    override suspend fun prepare(request: SourceRequest, catalog: Boolean): PreparedSource {
        val metadata = if (catalog) ytDlp.extractCatalogMetadata(request) else ytDlp.extractMetadata(request)
        return YtDlpPreparedSource(metadata.toMediaMetadata())
    }

    override suspend fun download(request: SourceRequest, type: SourceMediaType, prepared: PreparedSource, directory: Path): DownloadedFile {
        require(prepared is YtDlpPreparedSource)
        return ytDlp.download(request, directory)
    }
}

data class YtDlpPreparedSource(override val metadata: MediaMetadata) : PreparedSource
