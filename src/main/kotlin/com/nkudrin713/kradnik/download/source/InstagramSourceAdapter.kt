package com.nkudrin713.kradnik.download.source

import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaContentType
import com.nkudrin713.kradnik.download.instagram.InstagramEmbedDownloader
import com.nkudrin713.kradnik.download.instagram.InstagramEmbedException
import com.nkudrin713.kradnik.download.instagram.InstagramPreparedDownload
import com.nkudrin713.kradnik.ytdlp.YtDlpException
import com.nkudrin713.kradnik.ytdlp.YtDlpService
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class InstagramSourceAdapter(private val instagram: InstagramEmbedDownloader, private val ytDlp: YtDlpService) : SourceAdapter {
    override suspend fun prepare(request: SourceRequest, catalog: Boolean): PreparedSource {
        val prepared = try {
            instagram.prepare(request)
        } catch (embedError: InstagramEmbedException) {
            val metadata = try {
                ytDlp.extractInstagramImageMetadata(request)
            } catch (fallbackError: YtDlpException) {
                embedError.addSuppressed(fallbackError)
                throw embedError
            }
            instagram.prepareImages(request, metadata) ?: throw embedError
        }
        return InstagramPreparedSource(prepared)
    }

    override suspend fun download(request: SourceRequest, type: SourceMediaType, prepared: PreparedSource, directory: Path): DownloadedFile {
        require(prepared is InstagramPreparedSource)
        return when {
            type == SourceMediaType.IMAGES -> instagram.downloadImages(prepared.content, directory)
            type == SourceMediaType.VIDEO && prepared.content.mediaUri != null -> instagram.download(prepared.content, directory)
            else -> ytDlp.download(request, directory)
        }
    }
}

data class InstagramPreparedSource(val content: InstagramPreparedDownload) : PreparedSource {
    override val metadata = content.metadata.toMediaMetadata().copy(
        contentType = if (content.imageUris.isEmpty()) MediaContentType.VIDEO else MediaContentType.IMAGES,
    )
}
