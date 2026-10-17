package com.nkudrin713.kradnik.download.instagram

import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaContentType
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.PostMediaKind
import com.nkudrin713.kradnik.download.identity.ResultKeyFactory
import com.nkudrin713.kradnik.download.identity.UnsupportedUrlException
import com.nkudrin713.kradnik.download.instagram.InstagramEmbedDownloader
import com.nkudrin713.kradnik.download.instagram.InstagramEmbedException
import com.nkudrin713.kradnik.download.instagram.InstagramPreparedDownload
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.platform.PlatformDownloadSpecs
import com.nkudrin713.kradnik.download.source.MediaSelection
import com.nkudrin713.kradnik.download.source.PostSourceAdapter
import com.nkudrin713.kradnik.download.source.PreparedPostSource
import com.nkudrin713.kradnik.download.source.PreparedSource
import com.nkudrin713.kradnik.download.source.SourceMediaType
import com.nkudrin713.kradnik.download.source.SourcePost
import com.nkudrin713.kradnik.download.source.SourcePostItem
import com.nkudrin713.kradnik.download.source.SourceRequest
import com.nkudrin713.kradnik.ytdlp.YtDlpException
import com.nkudrin713.kradnik.ytdlp.YtDlpPresets
import com.nkudrin713.kradnik.ytdlp.YtDlpSelections
import com.nkudrin713.kradnik.ytdlp.YtDlpService
import com.nkudrin713.kradnik.ytdlp.toMediaMetadata
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class InstagramSourceAdapter(private val instagram: InstagramEmbedDownloader, private val ytDlp: YtDlpService) : PostSourceAdapter {
    override val platform = DownloadPlatform.INSTAGRAM
    override fun supports(url: String): Boolean = isInstagramHost(url)
    override fun select(spec: DownloadSpec, selection: MediaSelection): DownloadSpec = YtDlpSelections.select(spec, selection)

    override fun resolve(url: String): PlatformDownloadSpecs {
        val mediaUrl = parseInstagramMediaUrl(url)
            ?: throw UnsupportedUrlException("Instagram URL is not supported")

        val cacheKeyPrefix = "instagram:${mediaUrl.key}"
        return PlatformDownloadSpecs(
            video = DownloadSpec(
                originalUrl = mediaUrl.original,
                normalizedUrl = mediaUrl.normalized,
                cacheKey = ResultKeyFactory.source(cacheKeyPrefix, OutputType.VIDEO, "instagram_mobile_video"),
                outputType = OutputType.VIDEO,
                platform = DownloadPlatform.INSTAGRAM,
                presetName = "instagram_mobile_video",
                formatSelector =
                "bv*[height<=1280][vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a][ext=m4a]/" +
                    "b[height<=1280][vcodec^=avc1][ext=mp4]/" +
                    "b[height<=1280]/best",
                extraArgs = YtDlpPresets.MERGE_MP4_ARGS,
            ),
            audio = DownloadSpec(
                originalUrl = mediaUrl.original,
                normalizedUrl = mediaUrl.normalized,
                cacheKey = ResultKeyFactory.source(cacheKeyPrefix, OutputType.AUDIO, "instagram_audio"),
                outputType = OutputType.AUDIO,
                platform = DownloadPlatform.INSTAGRAM,
                presetName = "instagram_audio",
                formatSelector = YtDlpPresets.AUDIO_FORMAT_WITH_VIDEO_FALLBACK,
                extraArgs = YtDlpPresets.MP3_AUDIO_ARGS,
            ),
        )
    }

    override suspend fun prepare(request: SourceRequest, catalog: Boolean): InstagramPreparedSource {
        val prepared = try {
            instagram.prepare(request)
        } catch (embedError: InstagramEmbedException) {
            val metadata = try {
                ytDlp.extractImageMetadata(request)
            } catch (fallbackError: YtDlpException) {
                if (fallbackError.reason != DownloadFailureReason.SOURCE_FAILED) {
                    fallbackError.addSuppressed(embedError)
                    throw fallbackError
                }
                embedError.addSuppressed(fallbackError)
                throw embedError
            }
            instagram.prepareFromMetadata(request, metadata) ?: throw embedError
        }
        return InstagramPreparedSource(prepared)
    }

    override suspend fun downloadPostItem(request: SourceRequest, prepared: PreparedPostSource, index: Int, directory: Path): DownloadedFile {
        require(prepared is InstagramPreparedSource)
        val item = prepared.content.items[index]
        return if (item.uri != null) {
            instagram.downloadItem(item, directory)
        } else {
            ytDlp.download(request.copy(extraArgs = request.extraArgs + listOf("--playlist-items", (index + 1).toString())), directory)
        }
    }

    override suspend fun download(request: SourceRequest, type: SourceMediaType, prepared: PreparedSource, directory: Path): DownloadedFile {
        require(prepared is InstagramPreparedSource)
        return when {
            type == SourceMediaType.IMAGES -> instagram.downloadImages(prepared.content, directory)
            type == SourceMediaType.VIDEO && prepared.content.mediaUri != null -> instagram.download(prepared.content, directory)
            else -> ytDlp.download(request, directory)
        }
    }
}

data class InstagramPreparedSource(val content: InstagramPreparedDownload) : PreparedPostSource {
    override val post = SourcePost(
        content.items.mapIndexed { index, item -> SourcePostItem(item.kind, index.toString()) },
        content.metadata.description,
        authorUsername = content.metadata.channel ?: content.metadata.uploader,
    )
    override val metadata = content.metadata.toMediaMetadata().copy(
        contentType = when {
            content.items.all { it.kind == PostMediaKind.PHOTO } -> MediaContentType.IMAGES
            content.items.size > 1 -> MediaContentType.CAROUSEL
            else -> MediaContentType.VIDEO
        },
    )
}
