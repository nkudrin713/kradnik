package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.identity.normalizeGeneric
import com.nkudrin713.kradnik.download.identity.parseUrlOrNull
import com.nkudrin713.kradnik.download.request.DownloadRequest
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Component
@Order(20)
class InstagramDownloadHandler : PlatformDownloadHandler {

    override fun supports(url: String): Boolean {
        val uri = parseUrlOrNull(url.trim()) ?: return false
        return isInstagramHost(uri.host)
    }

    override fun normalize(url: String): String {
        val uri = parseUrlOrNull(url.trim()) ?: return url
        return normalizeGeneric(uri)
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
                presetName = "instagram_mobile_video",
                formatSelector =
                    "bv*[height<=1280][ext=mp4]+ba[ext=m4a]/" +
                            "b[height<=1280][ext=mp4]/" +
                            "b[height<=1280]/best",
                extraArgs = listOf("--merge-output-format", "mp4"),
            )

            OutputType.AUDIO -> DownloadRequest(
                originalUrl = url,
                normalizedUrl = normalized,
                outputType = outputType,
                presetName = "instagram_audio",
                formatSelector = "ba/bestaudio/best",
                extraArgs = listOf("-x", "--audio-format", "mp3"),
            )
        }
    }

    private fun isInstagramHost(host: String?): Boolean {
        return when (host?.lowercase()) {
            "instagram.com", "www.instagram.com", "m.instagram.com" -> true
            else -> false
        }
    }
}
