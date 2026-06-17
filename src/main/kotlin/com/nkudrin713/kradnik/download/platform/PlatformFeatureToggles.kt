package com.nkudrin713.kradnik.download.platform

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class PlatformFeatureToggles(
    @Value("\${download.platform.youtube.enabled:false}")
    private val youtubeEnabled: Boolean,
    @Value("\${download.platform.instagram.enabled:false}")
    private val instagramEnabled: Boolean,
) {

    fun isEnabled(platform: DownloadPlatform): Boolean {
        return when (platform) {
            DownloadPlatform.YOUTUBE -> youtubeEnabled
            DownloadPlatform.INSTAGRAM -> instagramEnabled
        }
    }

    fun enabledPlatformNames(): List<String> {
        return DownloadPlatform.entries
            .filter { isEnabled(it) }
            .map { it.displayName }
    }
}
