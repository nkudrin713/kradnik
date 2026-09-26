package com.nkudrin713.kradnik.download.telegram

import com.nkudrin713.kradnik.download.domain.SingleMediaRequest
import com.nkudrin713.kradnik.download.service.DownloadJobService
import org.springframework.stereotype.Component

@Component
class TelegramResultCache(private val jobs: DownloadJobService) {
    fun find(request: SingleMediaRequest): CachedMedia? = jobs.findCachedFileId(request.cacheKey)?.let {
        TelegramReceiptCodec.decode(request.outputType, it)
    }

    fun findAudio(cacheKey: String): String? = jobs.findCachedFileId(cacheKey)
}
