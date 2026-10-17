package com.nkudrin713.kradnik.download.delivery

import com.nkudrin713.kradnik.download.source.SourceRequest

/** Looks up reusable receipts; their encoding is owned by delivery adapters. */
interface ResultCache {
    fun find(cacheKey: String): String?
    fun findPlaylistAudio(sourceId: String, source: SourceRequest): String?
}
