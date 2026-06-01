package com.nkudrin713.kradnik.download.pipeline

import com.nkudrin713.kradnik.download.domain.DownloadOutputType
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import org.springframework.stereotype.Service

@Service
class DownloaderRouter(
    private val downloaders: List<MediaDownloader>,
) {
    fun findDownloader(metadata: MediaMetadata, outputType: DownloadOutputType): MediaDownloader =
        downloaders.firstOrNull { it.supports(metadata, outputType) }
            ?: throw UnsupportedDownloaderException(metadata.extractor, outputType)
}

class UnsupportedDownloaderException(extractor: String?, outputType: DownloadOutputType) :
    RuntimeException("No downloader found for extractor=$extractor outputType=$outputType")
