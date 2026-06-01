package com.nkudrin713.kradnik.download.pipeline

import com.nkudrin713.kradnik.download.domain.MediaMetadata

interface MetadataExtractor {
    suspend fun extract(url: String): MediaMetadata
}
