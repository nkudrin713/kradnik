package com.nkudrin713.kradnik.download.instagram

import com.nkudrin713.kradnik.download.DownloadPreparation
import com.nkudrin713.kradnik.download.PreparedDownloadSession
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.ratelimit.RateLimitDecision
import com.nkudrin713.kradnik.download.ratelimit.RateLimitPermit
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class InstagramDownloader(
    private val embedDownloader: InstagramEmbedDownloader,
    private val rateLimiter: InstagramRateLimiter,
    private val ytDlpService: YtDlpService,
) {
    suspend fun prepare(spec: DownloadSpec): DownloadPreparation {
        if (!embedDownloader.supports(spec)) {
            return DownloadPreparation.TerminalFailure(UNSUPPORTED_MESSAGE)
        }

        val permit = when (val decision = rateLimiter.acquire()) {
            is RateLimitDecision.Granted -> decision.permit
            is RateLimitDecision.Deferred -> return DownloadPreparation.NotReady(
                retryAt = decision.retryAt,
                reason = RATE_LIMIT_MESSAGE,
            )
        }

        return try {
            val prepared = embedDownloader.prepare(spec)
            rateLimiter.recordSuccess(permit)
            DownloadPreparation.Ready(InstagramSession(prepared))
        } catch (error: InstagramHttpException) {
            classifyHttpError(error, permit)
        } catch (error: InstagramContentUnavailableException) {
            DownloadPreparation.SourceUnavailable(error.message ?: error.javaClass.simpleName)
        } catch (error: InstagramEmbedException) {
            DownloadPreparation.TerminalFailure(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun classifyHttpError(
        error: InstagramHttpException,
        permit: RateLimitPermit,
    ): DownloadPreparation {
        val reason = error.message ?: error.javaClass.simpleName
        return when (error.statusCode) {
            403, 429 -> DownloadPreparation.RetryableFailure(
                retryAt = rateLimiter.recordThrottle(permit, error.retryAfter),
                reason = reason,
            )

            in 400..499 -> DownloadPreparation.TerminalFailure(reason)
            else -> throw error
        }
    }

    private inner class InstagramSession(
        private val prepared: InstagramPreparedDownload,
    ) : PreparedDownloadSession {
        override val metadata: YtDlpMetadataDto = prepared.metadata

        override suspend fun download(
            spec: DownloadSpec,
            outputDir: Path,
        ): DownloadedFile {
            return if (spec.outputType == OutputType.VIDEO && prepared.mediaUri != null) {
                embedDownloader.download(prepared, outputDir)
            } else {
                ytDlpService.download(spec, outputDir)
            }
        }
    }

    private companion object {
        private const val UNSUPPORTED_MESSAGE = "Instagram request is not supported by embed downloader"
        private const val RATE_LIMIT_MESSAGE = "Instagram request deferred by rate limiter"
    }
}
