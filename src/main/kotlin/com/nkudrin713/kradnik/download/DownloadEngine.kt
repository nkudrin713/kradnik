package com.nkudrin713.kradnik.download

import com.nkudrin713.kradnik.download.cover.CoverDownloader
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.instagram.InstagramEmbedDownloader
import com.nkudrin713.kradnik.download.instagram.InstagramPreparedDownload
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import org.springframework.stereotype.Component
import java.nio.file.Path

/** Explicit source routing. Prepared data belongs to one call and is never shared between workers. */
@Component
class DownloadEngine(
    private val ytDlpService: YtDlpService,
    private val instagramDownloader: InstagramEmbedDownloader,
    private val coverDownloader: CoverDownloader,
) {
    suspend fun prepare(spec: DownloadSpec, catalog: Boolean = false): PreparedDownload {
        if (spec.platform == DownloadPlatform.INSTAGRAM) {
            val instagram = instagramDownloader.prepare(spec)
            return PreparedDownload(metadata = instagram.metadata, instagram = instagram)
        }
        val metadata = if (catalog || spec.outputType == OutputType.COVER) {
            ytDlpService.extractCatalogMetadata(spec)
        } else {
            ytDlpService.extractMetadata(spec)
        }
        return PreparedDownload(metadata = metadata)
    }

    suspend fun download(spec: DownloadSpec, prepared: PreparedDownload, outputDir: Path): DownloadedFile {
        if (spec.outputType == OutputType.COVER) {
            val thumbnail = prepared.metadata.thumbnail
            require(!thumbnail.isNullOrBlank()) { "Cover is unavailable" }
            return coverDownloader.download(thumbnail, outputDir)
        }
        val instagram = prepared.instagram
        return if (spec.outputType == OutputType.VIDEO && instagram?.mediaUri != null) {
            instagramDownloader.download(instagram, outputDir)
        } else {
            ytDlpService.download(spec, outputDir)
        }
    }
}

/** Metadata and the optional direct Instagram URL retrieved in the same request. */
data class PreparedDownload(
    val metadata: YtDlpMetadataDto,
    val instagram: InstagramPreparedDownload? = null,
)
