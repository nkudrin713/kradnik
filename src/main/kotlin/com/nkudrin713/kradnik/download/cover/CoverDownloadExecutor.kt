package com.nkudrin713.kradnik.download.cover

import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.executor.DownloadExecutor
import com.nkudrin713.kradnik.download.executor.DownloadPreparation
import com.nkudrin713.kradnik.download.executor.DownloadStrategy
import com.nkudrin713.kradnik.download.executor.PreparedDownloadSession
import com.nkudrin713.kradnik.download.instagram.InstagramDownloadExecutor
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class CoverDownloadExecutor(
    private val ytDlpService: YtDlpService,
    private val instagramDownloadExecutor: InstagramDownloadExecutor,
    private val coverDownloader: CoverDownloader,
) : DownloadExecutor {
    override val strategies = setOf(
        DownloadStrategy.COVER_YT_DLP,
        DownloadStrategy.COVER_INSTAGRAM_EMBED,
    )

    override suspend fun prepare(spec: DownloadSpec): DownloadPreparation {
        if (spec.strategy == DownloadStrategy.COVER_INSTAGRAM_EMBED) {
            return when (val preparation = instagramDownloadExecutor.prepare(spec)) {
                is DownloadPreparation.Ready -> ready(preparation.session.metadata)
                is DownloadPreparation.NotReady -> preparation
                is DownloadPreparation.RetryableFailure -> preparation
                is DownloadPreparation.SourceUnavailable -> preparation
                is DownloadPreparation.TerminalFailure -> preparation
            }
        }

        return ready(ytDlpService.extractCatalogMetadata(spec))
    }

    private fun ready(metadata: YtDlpMetadataDto): DownloadPreparation {
        if (metadata.thumbnail.isNullOrBlank()) {
            return DownloadPreparation.TerminalFailure("Cover is unavailable")
        }
        return DownloadPreparation.Ready(CoverPreparedDownloadSession(metadata))
    }

    private inner class CoverPreparedDownloadSession(
        override val metadata: YtDlpMetadataDto,
    ) : PreparedDownloadSession {
        override suspend fun download(spec: DownloadSpec, outputDir: Path): DownloadedFile {
            return coverDownloader.download(requireNotNull(metadata.thumbnail), outputDir)
        }
    }
}
