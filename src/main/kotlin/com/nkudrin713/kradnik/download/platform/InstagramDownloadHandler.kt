package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.identity.UnsupportedUrlException
import com.nkudrin713.kradnik.download.instagram.isInstagramHost
import com.nkudrin713.kradnik.download.instagram.parseInstagramMediaUrl
import org.springframework.stereotype.Component

@Component
class InstagramDownloadHandler : PlatformDownloadHandler {

    override val platform: DownloadPlatform = DownloadPlatform.INSTAGRAM

    override fun supports(url: String): Boolean {
        return isInstagramHost(url)
    }

    override fun resolve(url: String): PlatformDownloadSpecs {
        val mediaUrl = parseInstagramMediaUrl(url)
            ?: throw UnsupportedUrlException("Instagram URL is not supported")

        val cacheKeyPrefix = "instagram:${mediaUrl.key}"
        return PlatformDownloadSpecs(
            video = DownloadSpec(
                originalUrl = mediaUrl.original,
                normalizedUrl = mediaUrl.normalized,
                cacheKey = "$cacheKeyPrefix:video:instagram_mobile_video",
                outputType = OutputType.VIDEO,
                platform = platform,
                presetName = "instagram_mobile_video",
                formatSelector =
                    "bv*[height<=1280][vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a][ext=m4a]/" +
                            "b[height<=1280][vcodec^=avc1][ext=mp4]/" +
                            "b[height<=1280]/best",
                extraArgs = listOf("--merge-output-format", "mp4"),
            ),
            audio = DownloadSpec(
                originalUrl = mediaUrl.original,
                normalizedUrl = mediaUrl.normalized,
                cacheKey = "$cacheKeyPrefix:audio:instagram_audio",
                outputType = OutputType.AUDIO,
                platform = platform,
                presetName = "instagram_audio",
                formatSelector = "ba/bestaudio/best",
                extraArgs = listOf("-x", "--audio-format", "mp3"),
            ),
        )
    }
}
