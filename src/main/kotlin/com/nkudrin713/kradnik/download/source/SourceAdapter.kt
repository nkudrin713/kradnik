package com.nkudrin713.kradnik.download.source

import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import java.nio.file.Path

interface SourceAdapter {
    suspend fun prepare(request: SourceRequest, catalog: Boolean): PreparedSource
    suspend fun download(request: SourceRequest, type: SourceMediaType, prepared: PreparedSource, directory: Path): DownloadedFile
}

sealed interface PreparedSource {
    val metadata: MediaMetadata
}
