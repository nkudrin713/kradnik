package com.nkudrin713.kradnik.ytdlp.client

import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.pipeline.MetadataExtractor
import org.springframework.stereotype.Service

@Service
class YtDlpMetadataExtractor(
    private val ytDlpService: YtDlpService,
) : MetadataExtractor {
    override suspend fun extract(url: String): MediaMetadata {
        val metadata = ytDlpService.extractMetadata(url)
        return MediaMetadata(
            title = metadata.title,
            extractor = metadata.extractor,
            durationSeconds = metadata.duration?.toLong(),
            webpageUrl = metadata.webpageUrl,
        )
    }
}
