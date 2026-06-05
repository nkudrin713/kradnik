package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import org.springframework.stereotype.Component

@Component
class MediaMetadataMapper {
    fun toMediaMetadata(metadata: YtDlpMetadataDto): MediaMetadata {
        return MediaMetadata(
            title = metadata.title,
            extractor = metadata.extractor,
            durationSeconds = metadata.duration?.toLong(),
            audioTitle = metadata.track
                ?: metadata.title
                ?: DEFAULT_AUDIO_TITLE,
            audioPerformer = metadata.artist
                ?: metadata.uploader
                ?: metadata.channel
                ?: metadata.extractor
                ?: DEFAULT_AUDIO_PERFORMER,
            width = metadata.width,
            height = metadata.height,
            webpageUrl = metadata.webpageUrl,
        )
    }

    private companion object {
        private const val DEFAULT_AUDIO_TITLE = "Audio"
        private const val DEFAULT_AUDIO_PERFORMER = "Unknown"
    }
}
