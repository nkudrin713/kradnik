package com.nkudrin713.kradnik.download.choice

import com.nkudrin713.kradnik.download.DownloadEngine
import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.DownloadRequestMapper
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import com.nkudrin713.kradnik.download.platform.SourceAdmission
import com.nkudrin713.kradnik.download.playlist.PlaylistChoicePlanner
import com.nkudrin713.kradnik.download.source.PreparedPostSource
import com.nkudrin713.kradnik.download.source.PreparedSource
import com.nkudrin713.kradnik.localization.BotLanguage
import com.nkudrin713.kradnik.localization.MessageKey
import com.nkudrin713.kradnik.localization.Messages
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
    private val messages: Messages,
    private val playlistPlanner: PlaylistChoicePlanner,
    private val availability: SourceAdmission,
) {
    suspend fun plan(url: String, language: BotLanguage = BotLanguage.EN): DownloadChoicePlan {
        availability.requireEnabled(platformResolver.platformOf(url))
        playlistPlanner.planOrNull(url, language)?.let { return it }
        var specs = platformResolver.resolve(url)
        val prepared = extractCatalog(specs.video, language)
        val metadata = prepared.metadata
        prepared.canonicalUrl?.let { specs = platformResolver.resolve(it) }
        val options = if (prepared is PreparedPostSource) posts.options(specs, prepared, language) else standard.options(specs, metadata, language)
        if (options.isEmpty()) throw DownloadChoicePlanningException(messages.text(language, MessageKey.ERROR_NO_OPTIONS))
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
                DownloadFailureReason.SOURCE_UNAVAILABLE -> MessageKey.ERROR_SOURCE_UNAVAILABLE
                DownloadFailureReason.SOURCE_RATE_LIMITED -> if (spec.platform == DownloadPlatform.INSTAGRAM) MessageKey.ERROR_INSTAGRAM_RATE_LIMITED else MessageKey.STATUS_SOURCE_RATE_LIMITED
                DownloadFailureReason.SOURCE_REQUEST_FAILED -> if (spec.platform == DownloadPlatform.INSTAGRAM) MessageKey.ERROR_INSTAGRAM_UNAVAILABLE else MessageKey.STATUS_SOURCE_REQUEST_FAILED
                DownloadFailureReason.METADATA_UNAVAILABLE -> MessageKey.ERROR_METADATA_UNAVAILABLE
                else -> failureMessage(error.reason)
            }
            throw DownloadChoicePlanningException(messages.text(language, message), error)
        }
    }
}
