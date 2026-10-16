package com.nkudrin713.kradnik.download.choice

import com.nkudrin713.kradnik.download.DownloadEngine
import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.platform.PlatformAvailability
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import com.nkudrin713.kradnik.download.playlist.YouTubePlaylistPlanner
import com.nkudrin713.kradnik.download.repository.DownloadRequestMapper
import com.nkudrin713.kradnik.download.source.PreparedPostSource
import com.nkudrin713.kradnik.download.source.PreparedSource
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
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
    private val posts: PostChoicePlanner,
    private val messages: TelegramMessages,
    private val youtubePlaylistPlanner: YouTubePlaylistPlanner,
    private val availability: PlatformAvailability,
) {
    suspend fun plan(url: String, language: BotLanguage = BotLanguage.EN): DownloadChoicePlan {
        availability.requireEnabled(platformResolver.platformOf(url))
        youtubePlaylistPlanner.planOrNull(url, language)?.let { return it }
        var specs = platformResolver.resolve(url)
        val prepared = extractCatalog(specs.video, language)
        val metadata = prepared.metadata
        prepared.canonicalUrl?.let { specs = platformResolver.resolve(it) }
        val options = if (prepared is PreparedPostSource) posts.options(specs, prepared, language) else standard.options(specs, metadata, language)
        if (options.isEmpty()) throw DownloadChoicePlanningException(messages.text(language, TelegramMessage.ERROR_NO_OPTIONS))
        return DownloadChoicePlan(
            DownloadChoiceMediaInfo(
                title = metadata.title,
                durationSeconds = metadata.duration?.takeIf { it >= BigDecimal.ZERO }?.setScale(0, RoundingMode.DOWN)?.toLong(),
                authorUsername = (prepared as? PreparedPostSource)?.post?.authorUsername,
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
                DownloadFailureReason.SOURCE_RATE_LIMITED -> if (spec.platform == DownloadPlatform.INSTAGRAM) TelegramMessage.ERROR_INSTAGRAM_RATE_LIMITED else TelegramMessage.STATUS_SOURCE_RATE_LIMITED
                DownloadFailureReason.SOURCE_REQUEST_FAILED -> if (spec.platform == DownloadPlatform.INSTAGRAM) TelegramMessage.ERROR_INSTAGRAM_UNAVAILABLE else TelegramMessage.STATUS_SOURCE_REQUEST_FAILED
                DownloadFailureReason.METADATA_UNAVAILABLE -> TelegramMessage.ERROR_METADATA_UNAVAILABLE
                else -> TelegramDownloadStatus.forFailure(error.reason).message
            }
            throw DownloadChoicePlanningException(messages.text(language, message), error)
        }
    }
}
