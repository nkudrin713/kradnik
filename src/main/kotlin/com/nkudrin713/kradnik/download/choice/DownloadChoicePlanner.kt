package com.nkudrin713.kradnik.download.choice

import com.nkudrin713.kradnik.download.DownloadEngine
import com.nkudrin713.kradnik.download.PreparedDownload
import com.nkudrin713.kradnik.download.instagram.InstagramContentUnavailableException
import com.nkudrin713.kradnik.download.instagram.InstagramHttpException
import com.nkudrin713.kradnik.download.instagram.InstagramEmbedException
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.limit.AudioUploadPlan
import com.nkudrin713.kradnik.download.limit.AudioUploadPlanner
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import com.nkudrin713.kradnik.download.playlist.YouTubePlaylistPlanner
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessages
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpFormatDto
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Resolves a URL with [PlatformResolver], obtains one catalog through [DownloadEngine], and builds a stable menu snapshot.
 * Video formats are ranked for Telegram compatibility, named qualities duplicating the original are omitted, and
 * [AudioUploadPlanner] plus [TelegramUploadLimits] determine which options can be offered.
 */
@Component
class DownloadChoicePlanner(
    private val platformResolver: PlatformResolver,
    private val downloadEngine: DownloadEngine,
    private val audioUploadPlanner: AudioUploadPlanner,
    private val uploadLimits: TelegramUploadLimits,
    private val messages: TelegramMessages,
    private val youtubePlaylistPlanner: YouTubePlaylistPlanner,
) {
    suspend fun plan(url: String, language: BotLanguage = BotLanguage.EN): DownloadChoicePlan {
        youtubePlaylistPlanner.planOrNull(url, language)?.let { return it }
        val specs = platformResolver.resolve(url)
        val video = specs.video
        val audio = specs.audio
        val prepared = extractCatalog(video, language)
        val metadata = prepared.metadata

        val options = if (prepared.instagram?.imageUris?.isNotEmpty() == true) {
            listOf(imagePostOption(video, metadata.description, language))
        } else {
            val videoOptions = videoOptions(
                spec = video,
                metadata = metadata,
                language = language,
                allowUnknownOriginalSize = prepared.instagram != null,
            )
            buildList {
                if (video.platform == DownloadPlatform.INSTAGRAM) {
                    videoOptions.firstOrNull()?.let { add(videoPostOption(it, metadata.description, language)) }
                }
                addAll(videoOptions)
                audioOption(audio, metadata, language)?.let(::add)
                coverOption(video, metadata, language)?.let(::add)
            }
        }
        if (options.isEmpty()) {
            throw DownloadChoicePlanningException(messages.text(language, TelegramMessage.ERROR_NO_OPTIONS))
        }

        return DownloadChoicePlan(
            mediaInfo = DownloadChoiceMediaInfo(
                title = metadata.title,
                durationSeconds = metadata.duration
                    ?.takeIf { it >= BigDecimal.ZERO }
                    ?.setScale(0, RoundingMode.DOWN)
                    ?.toLong(),
                authorUsername = if (video.platform == DownloadPlatform.INSTAGRAM) {
                    metadata.channel ?: metadata.uploader
                } else {
                    null
                },
            ),
            options = options,
        )
    }

    private suspend fun extractCatalog(spec: DownloadSpec, language: BotLanguage): PreparedDownload {
        try {
            return downloadEngine.prepare(spec, catalog = true)
        } catch (error: InstagramContentUnavailableException) {
            throw DownloadChoicePlanningException(messages.text(language, TelegramMessage.ERROR_SOURCE_UNAVAILABLE))
        } catch (error: InstagramHttpException) {
            val message = if (error.statusCode == 403 || error.statusCode == 429) {
                TelegramMessage.ERROR_INSTAGRAM_RATE_LIMITED
            } else {
                TelegramMessage.ERROR_INSTAGRAM_UNAVAILABLE
            }
            throw DownloadChoicePlanningException(messages.text(language, message))
        } catch (error: InstagramEmbedException) {
            throw DownloadChoicePlanningException(messages.text(language, TelegramMessage.ERROR_METADATA_UNAVAILABLE))
        }
    }

    private fun videoOptions(
        spec: DownloadSpec,
        metadata: YtDlpMetadataDto,
        language: BotLanguage,
        allowUnknownOriginalSize: Boolean = false,
    ): List<DownloadChoiceOptionSnapshot> {
        val formats = metadata.formats.orEmpty()
        if (formats.isEmpty()) {
            return fallbackOriginalOption(
                spec = spec,
                metadata = metadata,
                language = language,
                allowUnknownSize = allowUnknownOriginalSize,
            )?.let(::listOf).orEmpty()
        }

        return buildList {
            val original = selectVideo(formats, metadata, targetHeight = null)
            original?.let {
                add(
                    videoOption(
                        spec = spec,
                        key = VIDEO_ORIGINAL_KEY,
                        label = originalLabel(language, it.height),
                        selected = it,
                        language = language,
                    )
                )
            }
            TARGET_HEIGHTS.forEach { height ->
                selectVideo(formats, metadata, targetHeight = height)?.let { selected ->
                    if (selected != original) {
                        add(
                            videoOption(
                                spec = spec,
                                key = "video_$height",
                                label = "${height}p",
                                selected = selected,
                                language = language,
                            )
                        )
                    }
                }
            }
        }
    }

    private fun selectVideo(
        formats: List<YtDlpFormatDto>,
        metadata: YtDlpMetadataDto,
        targetHeight: Int?,
    ): SelectedMedia? {
        val videoFormats = formats.filter { it.isVideo() }
        val matching = if (targetHeight == null) {
            val maxHeight = videoFormats.maxOfOrNull { requireNotNull(it.height) } ?: return null
            videoFormats.filter { it.height == maxHeight }
        } else {
            videoFormats.filter { it.height == targetHeight }
        }
        val video = matching.maxWithOrNull(videoComparator) ?: return null
        val audio = if (video.hasAudio()) {
            null
        } else {
            formats.filter { it.isAudioOnly() }.maxWithOrNull(audioComparator)
        }

        val videoSize = sizeOf(video, metadata.duration) ?: return null
        val audioSize = audio?.let { sizeOf(it, metadata.duration) }
        if (audio != null && audioSize == null) {
            return null
        }
        val totalSize = runCatching {
            Math.addExact(videoSize.bytes, audioSize?.bytes ?: 0L)
        }.getOrNull() ?: return null
        val videoId = requireNotNull(video.formatId)
        val selector = audio?.formatId?.let { "$videoId+$it" } ?: videoId

        return SelectedMedia(
            formatSelector = selector,
            height = requireNotNull(video.height),
            sizeBytes = totalSize,
            approximateSize = videoSize.approximate || audioSize?.approximate == true,
        )
    }

    private fun videoOption(
        spec: DownloadSpec,
        key: String,
        label: String,
        selected: SelectedMedia,
        language: BotLanguage,
    ): DownloadChoiceOptionSnapshot {
        val selectedSpec = spec.copy(
            formatSelector = selected.formatSelector,
            presetName = "${presetPrefix(spec)}_video_${key.removePrefix("video_")}",
        )
        return option(
            spec = selectedSpec,
            key = key,
            label = label,
            sizeBytes = selected.sizeBytes,
            approximateSize = selected.approximateSize,
            language = language,
        )
    }

    private fun fallbackOriginalOption(
        spec: DownloadSpec,
        metadata: YtDlpMetadataDto,
        language: BotLanguage,
        allowUnknownSize: Boolean,
    ): DownloadChoiceOptionSnapshot? {
        val size = metadata.filesize ?: metadata.filesizeApprox
        if (size == null && !allowUnknownSize) {
            return null
        }
        return option(
            spec = spec.copy(
                presetName = "${presetPrefix(spec)}_video_original",
            ),
            key = VIDEO_ORIGINAL_KEY,
            label = originalLabel(language, metadata.height),
            sizeBytes = size,
            approximateSize = metadata.filesize == null && metadata.filesizeApprox != null,
            language = language,
        )
    }

    private fun originalLabel(language: BotLanguage, height: Int?): String {
        val label = messages.text(language, TelegramMessage.CHOICE_ORIGINAL)
        return height?.let { "$label · ${it}p" } ?: label
    }

    private fun audioOption(
        spec: DownloadSpec,
        metadata: YtDlpMetadataDto,
        language: BotLanguage,
    ): DownloadChoiceOptionSnapshot? {
        val plan = audioUploadPlanner.plan(metadata)
        if (plan !is AudioUploadPlan.Allowed) {
            return null
        }
        val selectedSpec = spec.withAudioQuality(plan.audioQuality)
        return option(
            spec = selectedSpec,
            key = AUDIO_KEY,
            label = messages.text(language, TelegramMessage.CHOICE_AUDIO),
            sizeBytes = plan.estimatedSizeBytes,
            approximateSize = true,
            cacheKeySuffix = "audio:${plan.audioQuality}",
            language = language,
        )
    }

    private fun coverOption(
        spec: DownloadSpec,
        metadata: YtDlpMetadataDto,
        language: BotLanguage,
    ): DownloadChoiceOptionSnapshot? {
        metadata.thumbnail?.takeIf { it.isNotBlank() } ?: return null
        val coverSpec = spec.copy(
            outputType = OutputType.COVER,
            formatSelector = "best",
            extraArgs = emptyList(),
            presetName = "${presetPrefix(spec)}_cover",
        )
        return option(
            spec = coverSpec,
            key = COVER_KEY,
            label = messages.text(language, TelegramMessage.CHOICE_COVER),
            sizeBytes = null,
            approximateSize = false,
            cacheKeySuffix = "cover:v1",
            language = language,
        )
    }

    private fun videoPostOption(
        original: DownloadChoiceOptionSnapshot,
        postText: String?,
        language: BotLanguage,
    ): DownloadChoiceOptionSnapshot {
        return original.copy(
            key = POST_KEY,
            label = messages.text(language, TelegramMessage.CHOICE_POST),
            spec = original.spec.copy(postText = postText?.takeIf(String::isNotBlank)),
        )
    }

    private fun imagePostOption(
        spec: DownloadSpec,
        postText: String?,
        language: BotLanguage,
    ): DownloadChoiceOptionSnapshot {
        return option(
            spec = spec.copy(
                outputType = OutputType.IMAGES,
                formatSelector = IMAGES_FORMAT_SELECTOR,
                extraArgs = emptyList(),
                presetName = "${presetPrefix(spec)}_images",
                postText = postText?.takeIf(String::isNotBlank),
            ),
            key = POST_KEY,
            label = messages.text(language, TelegramMessage.CHOICE_POST),
            sizeBytes = null,
            approximateSize = false,
            language = language,
        )
    }

    private fun option(
        spec: DownloadSpec,
        key: String,
        label: String,
        sizeBytes: Long?,
        approximateSize: Boolean,
        cacheKeySuffix: String = "choice:$key:${spec.presetName}",
        language: BotLanguage,
    ): DownloadChoiceOptionSnapshot {
        val tooLarge = sizeBytes != null && sizeBytes > uploadLimits.maxUploadBytes
        return DownloadChoiceOptionSnapshot(
            key = key,
            label = label,
            sizeBytes = sizeBytes,
            approximateSize = approximateSize,
            available = !tooLarge,
            unavailableReason = if (tooLarge) {
                messages.text(language, TelegramMessage.ERROR_TOO_LARGE)
            } else {
                null
            },
            spec = spec.copy(cacheKey = "${spec.cacheKey}:$cacheKeySuffix"),
        )
    }

    private fun sizeOf(format: YtDlpFormatDto, duration: BigDecimal?): FormatSize? {
        format.filesize?.let { return FormatSize(it, approximate = false) }
        format.filesizeApprox?.let { return FormatSize(it, approximate = true) }
        val seconds = duration?.takeIf { it > BigDecimal.ZERO } ?: return null
        val bitrate = format.tbr ?: format.vbr ?: format.abr ?: return null
        val bytes = bitrate
            .multiply(seconds)
            .multiply(BigDecimal.valueOf(BITS_IN_KILOBIT))
            .divide(BigDecimal.valueOf(BITS_IN_BYTE), 0, RoundingMode.CEILING)
            .toLong()
        return FormatSize(bytes, approximate = true)
    }

    private fun presetPrefix(spec: DownloadSpec): String {
        return spec.platform.dbValue
    }

    private companion object {
        private const val VIDEO_ORIGINAL_KEY = "video_original"
        private const val AUDIO_KEY = "audio"
        private const val COVER_KEY = "cover"
        private const val POST_KEY = "post"
        private const val IMAGES_FORMAT_SELECTOR = "images"
        private const val BITS_IN_KILOBIT = 1000L
        private const val BITS_IN_BYTE = 8L
        private val TARGET_HEIGHTS = listOf(1080, 720, 480, 360)
        private val videoComparator = compareBy<YtDlpFormatDto>(
            { it.telegramVideoScore() },
            { it.fps ?: BigDecimal.ZERO },
            { it.vbr ?: it.tbr ?: BigDecimal.ZERO },
        )
        private val audioComparator = compareBy<YtDlpFormatDto>(
            { it.telegramAudioScore() },
            { it.abr ?: it.tbr ?: BigDecimal.ZERO },
        )

        private fun YtDlpFormatDto.isVideo(): Boolean {
            return !formatId.isNullOrBlank() && height != null && vcodec.isPresentCodec()
        }

        private fun YtDlpFormatDto.isAudioOnly(): Boolean {
            return !formatId.isNullOrBlank() && !vcodec.isPresentCodec() && acodec.isPresentCodec()
        }

        private fun YtDlpFormatDto.hasAudio(): Boolean = acodec.isPresentCodec()

        private fun String?.isPresentCodec(): Boolean {
            return !isNullOrBlank() && !equals("none", ignoreCase = true)
        }

        private fun YtDlpFormatDto.telegramVideoScore(): Int {
            return (if (ext == "mp4") 2 else 0) +
                    (if (vcodec?.startsWith("avc1") == true || vcodec == "h264") 2 else 0) +
                    (if (hasAudio()) 1 else 0)
        }

        private fun YtDlpFormatDto.telegramAudioScore(): Int {
            return (if (ext == "m4a" || ext == "mp4") 1 else 0) +
                    (if (acodec?.startsWith("mp4a") == true || acodec == "aac") 1 else 0)
        }
    }
}

private data class SelectedMedia(
    val formatSelector: String,
    val height: Int,
    val sizeBytes: Long,
    val approximateSize: Boolean,
)

private data class FormatSize(
    val bytes: Long,
    val approximate: Boolean,
)
