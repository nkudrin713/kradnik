package com.nkudrin713.kradnik.download.pipeline

import com.nkudrin713.kradnik.download.domain.DownloadOutputType
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import org.springframework.stereotype.Service
import java.nio.file.Path

interface MediaSourceService {
    fun supports(extractor: String?): Boolean

    suspend fun download(
        url: String,
        metadata: MediaMetadata,
        outputType: DownloadOutputType,
        outputDir: Path,
        chatId: Long,
        taskId: Long,
    ): DownloadedFile
}

@Service
class MediaSourceRouter(
    private val services: List<MediaSourceService>,
) {
    fun find(metadata: MediaMetadata): MediaSourceService =
        services.firstOrNull { it.supports(metadata.extractor) }
            ?: throw UnsupportedMediaSourceException(metadata.extractor)
}

class UnsupportedMediaSourceException(extractor: String?) :
    RuntimeException("No media source service found for extractor=$extractor")
