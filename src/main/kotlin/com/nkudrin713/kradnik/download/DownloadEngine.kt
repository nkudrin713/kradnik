package com.nkudrin713.kradnik.download

import com.nkudrin713.kradnik.download.cover.CoverDownloader
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.instagram.InstagramDownloader
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Instant

/**
 * Chooses [YtDlpService], [InstagramDownloader], or [CoverDownloader] from the requested specification.
 * It exposes platform-specific preparation as [DownloadPreparation], allowing
 * [DownloadJobProcessor][com.nkudrin713.kradnik.download.processing.DownloadJobProcessor] to handle one lifecycle.
 */
@Component
class DownloadEngine(
    private val ytDlpService: YtDlpService,
    private val instagramDownloader: InstagramDownloader,
    private val coverDownloader: CoverDownloader,
) {
    suspend fun prepare(spec: DownloadSpec): DownloadPreparation {
        if (spec.outputType == OutputType.COVER) {
            return prepareCover(spec)
        }

        return when (spec.platform) {
            DownloadPlatform.INSTAGRAM -> instagramDownloader.prepare(spec)
            DownloadPlatform.YOUTUBE, DownloadPlatform.VK -> prepareYtDlp(spec, catalog = false)
        }
    }

    /**
     * Prepares unfiltered source metadata for
     * [DownloadChoicePlanner][com.nkudrin713.kradnik.download.choice.DownloadChoicePlanner].
     * Unlike [prepare], this keeps the complete format catalog needed to build quality options.
     */
    suspend fun prepareCatalog(spec: DownloadSpec): DownloadPreparation {
        return when (spec.platform) {
            DownloadPlatform.INSTAGRAM -> instagramDownloader.prepare(spec)
            DownloadPlatform.YOUTUBE, DownloadPlatform.VK -> prepareYtDlp(spec, catalog = true)
        }
    }

    private suspend fun prepareCover(spec: DownloadSpec): DownloadPreparation {
        val source = when (spec.platform) {
            DownloadPlatform.INSTAGRAM -> instagramDownloader.prepare(spec)
            DownloadPlatform.YOUTUBE, DownloadPlatform.VK -> prepareYtDlp(spec, catalog = true)
        }

        return when (source) {
            is DownloadPreparation.Ready -> {
                val thumbnail = source.session.metadata.thumbnail
                    ?.takeIf { it.isNotBlank() }
                    ?: return DownloadPreparation.TerminalFailure("Cover is unavailable")
                DownloadPreparation.Ready(
                    object : PreparedDownloadSession {
                        override val metadata = source.session.metadata

                        override suspend fun download(spec: DownloadSpec, outputDir: Path): DownloadedFile {
                            return coverDownloader.download(thumbnail, outputDir)
                        }
                    }
                )
            }

            is DownloadPreparation.NotReady -> source
            is DownloadPreparation.RetryableFailure -> source
            is DownloadPreparation.SourceUnavailable -> source
            is DownloadPreparation.TerminalFailure -> source
        }
    }

    private suspend fun prepareYtDlp(
        spec: DownloadSpec,
        catalog: Boolean,
    ): DownloadPreparation {
        val metadata = if (catalog) {
            ytDlpService.extractCatalogMetadata(spec)
        } else {
            ytDlpService.extractMetadata(spec)
        }
        return DownloadPreparation.Ready(
            object : PreparedDownloadSession {
                override val metadata = metadata

                override suspend fun download(spec: DownloadSpec, outputDir: Path): DownloadedFile {
                    return ytDlpService.download(spec, outputDir)
                }
            }
        )
    }
}

/**
 * Binds a metadata snapshot to the downloader that produced it for one processing attempt.
 * [DownloadJobProcessor][com.nkudrin713.kradnik.download.processing.DownloadJobProcessor] therefore preflights
 * and downloads through the same prepared source path.
 */
interface PreparedDownloadSession {
    val metadata: YtDlpMetadataDto

    suspend fun download(
        spec: DownloadSpec,
        outputDir: Path,
    ): DownloadedFile
}

/**
 * A platform-neutral result consumed by
 * [DownloadJobProcessor][com.nkudrin713.kradnik.download.processing.DownloadJobProcessor].
 * It distinguishes a ready session, deferral, retryable source failure, unavailable content, and terminal failure.
 */
sealed interface DownloadPreparation {
    data class Ready(
        val session: PreparedDownloadSession,
    ) : DownloadPreparation

    /** Defers processing before a source request; [DownloadJobService][com.nkudrin713.kradnik.download.service.DownloadJobService] must not consume an attempt. */
    data class NotReady(
        val retryAt: Instant,
        val reason: String,
    ) : DownloadPreparation

    /** Reports a transient source failure that [DownloadJobLifecycle][com.nkudrin713.kradnik.download.processing.DownloadJobLifecycle] counts toward the retry limit. */
    data class RetryableFailure(
        val retryAt: Instant,
        val reason: String,
    ) : DownloadPreparation

    data class SourceUnavailable(
        val reason: String,
    ) : DownloadPreparation

    data class TerminalFailure(
        val reason: String,
    ) : DownloadPreparation
}
