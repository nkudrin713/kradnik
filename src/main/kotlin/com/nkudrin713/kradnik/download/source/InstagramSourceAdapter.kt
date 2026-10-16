package com.nkudrin713.kradnik.download.source

import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaContentType
import com.nkudrin713.kradnik.download.domain.PostMediaKind
import com.nkudrin713.kradnik.download.instagram.InstagramEmbedDownloader
import com.nkudrin713.kradnik.download.instagram.InstagramEmbedException
import com.nkudrin713.kradnik.download.instagram.InstagramPreparedDownload
import com.nkudrin713.kradnik.ytdlp.YtDlpException
import com.nkudrin713.kradnik.ytdlp.YtDlpService
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class InstagramSourceAdapter(private val instagram: InstagramEmbedDownloader, private val ytDlp: YtDlpService) : PostSourceAdapter {
    override suspend fun prepare(request: SourceRequest, catalog: Boolean): InstagramPreparedSource {
        val prepared = try {
            instagram.prepare(request)
        } catch (embedError: InstagramEmbedException) {
            val metadata = try {
                ytDlp.extractInstagramImageMetadata(request)
            } catch (fallbackError: YtDlpException) {
                if (fallbackError.reason != DownloadFailureReason.SOURCE_FAILED) {
                    fallbackError.addSuppressed(embedError)
                    throw fallbackError
                }
                embedError.addSuppressed(fallbackError)
                throw embedError
            }
            instagram.prepareFromMetadata(request, metadata) ?: throw embedError
        }
        return InstagramPreparedSource(prepared)
    }

    override suspend fun downloadPostItem(request: SourceRequest, prepared: PreparedPostSource, index: Int, directory: Path): DownloadedFile {
        require(prepared is InstagramPreparedSource)
        val item = prepared.content.items[index]
        return if (item.uri != null) {
            instagram.downloadItem(item, directory)
        } else {
            ytDlp.download(request.copy(extraArgs = request.extraArgs + listOf("--playlist-items", (index + 1).toString())), directory)
        }
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

data class InstagramPreparedSource(val content: InstagramPreparedDownload) : PreparedPostSource {
    override val mediaKinds = content.items.map { it.kind }
    override val metadata = content.metadata.toMediaMetadata().copy(
        contentType = when {
            content.items.all { it.kind == PostMediaKind.PHOTO } -> MediaContentType.IMAGES
            content.items.size > 1 -> MediaContentType.CAROUSEL
            else -> MediaContentType.VIDEO
        },
    )
}
