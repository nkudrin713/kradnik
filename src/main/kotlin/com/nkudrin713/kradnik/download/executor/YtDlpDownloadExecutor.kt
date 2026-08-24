package com.nkudrin713.kradnik.download.executor

import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.request.DownloadRequest
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class YtDlpDownloadExecutor(
    private val ytDlpService: YtDlpService,
) : DownloadExecutor {
    override val strategies = setOf(
        DownloadStrategy.YT_DLP,
        DownloadStrategy.YOUTUBE_YT_DLP,
        DownloadStrategy.VK_YT_DLP,
    )

    override suspend fun prepare(request: DownloadRequest): DownloadPreparation {
        return ready(ytDlpService.extractMetadata(request))
    }

    override suspend fun prepareCatalog(request: DownloadRequest): DownloadPreparation {
        return ready(ytDlpService.extractCatalogMetadata(request))
    }

    private fun ready(metadata: YtDlpMetadataDto): DownloadPreparation {
        return DownloadPreparation.Ready(YtDlpPreparedDownloadSession(metadata))
    }

    private inner class YtDlpPreparedDownloadSession(
        override val metadata: YtDlpMetadataDto,
    ) : PreparedDownloadSession {
        override suspend fun download(
            request: DownloadRequest,
            outputDir: Path,
        ): DownloadedFile {
            return ytDlpService.download(request, outputDir)
        }
    }
}
