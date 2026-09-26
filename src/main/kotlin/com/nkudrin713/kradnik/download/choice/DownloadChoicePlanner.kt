package com.nkudrin713.kradnik.download.choice

import com.nkudrin713.kradnik.download.DownloadEngine
import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import com.nkudrin713.kradnik.download.playlist.YouTubePlaylistPlanner
import com.nkudrin713.kradnik.download.repository.DownloadRequestMapper
import com.nkudrin713.kradnik.download.source.PreparedSource
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessages
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/** Routes supported workloads and persists no live source preparation data. */
@Component
class DownloadChoicePlanner(
    private val platformResolver: PlatformResolver,
    private val downloadEngine: DownloadEngine,
    private val standard: StandardMediaChoicePlanner,
    private val instagram: InstagramChoicePlanner,
    private val messages: TelegramMessages,
    private val youtubePlaylistPlanner: YouTubePlaylistPlanner,
) {
    suspend fun plan(url: String, language: BotLanguage = BotLanguage.EN): DownloadChoicePlan {
        youtubePlaylistPlanner.planOrNull(url, language)?.let { return it }
        val specs = platformResolver.resolve(url)
        val metadata = extractCatalog(specs.video, language).metadata
        val isInstagram = specs.video.platform == DownloadPlatform.INSTAGRAM
        val options = if (isInstagram) instagram.options(specs, metadata, language) else standard.options(specs, metadata, language)
        if (options.isEmpty()) throw DownloadChoicePlanningException(messages.text(language, TelegramMessage.ERROR_NO_OPTIONS))
        return DownloadChoicePlan(
            DownloadChoiceMediaInfo(
                title = metadata.title,
                durationSeconds = metadata.duration?.takeIf { it >= BigDecimal.ZERO }?.setScale(0, RoundingMode.DOWN)?.toLong(),
                authorUsername = if (isInstagram) metadata.channel ?: metadata.uploader else null,
            ),
            options,
        )
    }

    private suspend fun extractCatalog(spec: DownloadSpec, language: BotLanguage): PreparedSource {
        try {
            return downloadEngine.prepare(DownloadRequestMapper.source(spec), catalog = true)
        } catch (error: DownloadFailure) {
            val message = when (error.reason) {
                DownloadFailureReason.SOURCE_UNAVAILABLE -> TelegramMessage.ERROR_SOURCE_UNAVAILABLE
                DownloadFailureReason.SOURCE_RATE_LIMITED -> TelegramMessage.ERROR_INSTAGRAM_RATE_LIMITED
                DownloadFailureReason.SOURCE_REQUEST_FAILED -> TelegramMessage.ERROR_INSTAGRAM_UNAVAILABLE
                DownloadFailureReason.METADATA_UNAVAILABLE -> TelegramMessage.ERROR_METADATA_UNAVAILABLE
                else -> throw error
            }
            throw DownloadChoicePlanningException(messages.text(language, message), error)
        }
    }
}
