package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.request.DownloadRequest
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Component
@Order(10)
class YouTubeDownloadHandler : PlatformDownloadHandler {

    override fun supports(url: String): Boolean {
        return url.contains("youtube.com") ||
                url.contains("youtu.be")
    }

    override fun normalize(url: String): String {
        // убрать лишние query-параметры, привести shorts/watch/youtu.be к одному виду
        return url
    }

    override fun buildRequest(
        url: String,
        outputType: OutputType,
    ): DownloadRequest {
        val normalized = normalize(url)

        return when (outputType) {
            OutputType.VIDEO -> DownloadRequest(
                originalUrl = url,
                normalizedUrl = normalized,
                outputType = outputType,
                presetName = "youtube_h264_mobile",
                formatSelector =
                    "bv*[filesize<40M][height<=1280][vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a][ext=m4a]/" +
                            "bv*[height<=720][filesize<40M][vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a][ext=m4a]/" +
                            "bv*[height<=480][vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a][ext=m4a]/" +
                            "b[height<=720][vcodec^=avc1][ext=mp4]/b",
                extraArgs = listOf("--merge-output-format", "mp4"),
            )

            OutputType.AUDIO -> DownloadRequest(
                originalUrl = url,
                normalizedUrl = normalized,
                outputType = outputType,
                presetName = "youtube_audio",
                formatSelector = "ba/bestaudio",
                extraArgs = listOf(
                    "-x",
                    "--audio-format", "mp3",
                    "--embed-metadata",
                    "--embed-thumbnail",
                    "--convert-thumbnails", "jpg"
                ),
            )
        }
    }
}
