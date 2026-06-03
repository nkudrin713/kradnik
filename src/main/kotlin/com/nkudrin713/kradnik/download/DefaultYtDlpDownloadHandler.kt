package com.nkudrin713.kradnik.download

import com.nkudrin713.kradnik.download.domain.OutputType
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Component
@Order(Int.MAX_VALUE)
class DefaultYtDlpDownloadHandler : PlatformDownloadHandler {

    override fun supports(url: String): Boolean = true

    override fun normalize(url: String): String = url

    override fun buildRequest(
        url: String,
        outputType: OutputType,
    ): DownloadRequest {
        return when (outputType) {
            OutputType.VIDEO -> DownloadRequest(
                originalUrl = url,
                normalizedUrl = url,
                outputType = outputType,
                presetName = "default_mobile_video",
                formatSelector =
                    "bv*[height<=1280][vcodec^=avc1]+ba[acodec^=mp4a]/" +
                            "b[height<=1280][vcodec^=avc1]/b",
                extraArgs = listOf("--merge-output-format", "mp4"),
            )

            OutputType.AUDIO -> DownloadRequest(
                originalUrl = url,
                normalizedUrl = url,
                outputType = outputType,
                presetName = "default_audio",
                formatSelector = "ba[acodec^=mp4a]/ba/bestaudio",
            )
        }
    }
}
