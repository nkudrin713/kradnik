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
                    "bv*[filesize<40M][height<=1280][vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a][ext=m4a]/" +
                            "bv*[height<=720][filesize<40M][vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a][ext=m4a]/" +
                            "bv*[height<=480][vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a][ext=m4a]/" +
                            "b[height<=720][vcodec^=avc1][ext=mp4]/b",
                extraArgs = listOf("--merge-output-format", "mp4"),
            )

            OutputType.AUDIO -> DownloadRequest(
                originalUrl = url,
                normalizedUrl = url,
                outputType = outputType,
                presetName = "default_audio",
                formatSelector = "ba/bestaudio",
                extraArgs = listOf("-x", "--audio-format", "mp3"),
            )
        }
    }
}
