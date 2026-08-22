package com.nkudrin713.kradnik.download.vk

import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.executor.DownloadExecutor
import com.nkudrin713.kradnik.download.executor.DownloadPreparation
import com.nkudrin713.kradnik.download.executor.PreparedDownloadSession
import com.nkudrin713.kradnik.download.platform.VK_AUDIO_PRESET
import com.nkudrin713.kradnik.download.platform.VK_VIDEO_PRESET
import com.nkudrin713.kradnik.download.request.DownloadRequest
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
@Order(20)
class VkDownloadExecutor(
    private val ytDlpService: YtDlpService,
) : DownloadExecutor {
    override fun supports(request: DownloadRequest): Boolean {
        return request.presetName == VK_VIDEO_PRESET || request.presetName == VK_AUDIO_PRESET
    }

    override suspend fun prepare(request: DownloadRequest): DownloadPreparation {
        return DownloadPreparation.Ready(
            VkPreparedDownloadSession(
                metadata = ytDlpService.extractMetadata(request),
            )
        )
    }

    private inner class VkPreparedDownloadSession(
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
