package com.nkudrin713.kradnik.download.choice

import com.nkudrin713.kradnik.download.DownloadEngine
import com.nkudrin713.kradnik.download.DownloadPreparation
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.limit.AudioUploadPlan
import com.nkudrin713.kradnik.download.limit.AudioUploadPlanner
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpFormatDto
import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

@Component
class DownloadChoicePlanner(
    private val platformResolver: PlatformResolver,
    private val downloadEngine: DownloadEngine,
    private val audioUploadPlanner: AudioUploadPlanner,
    private val uploadLimits: TelegramUploadLimits,
) {
    suspend fun plan(url: String): DownloadChoicePlan {
        val specs = platformResolver.resolve(url)
        val video = specs.video
        val audio = specs.audio
        val metadata = extractCatalog(video)

        val options = buildList {
            addAll(videoOptions(video, metadata))
            audioOption(audio, metadata)?.let(::add)
            coverOption(video, metadata)?.let(::add)
        }
        if (options.isEmpty()) {
            throw DownloadChoicePlanningException("Не удалось определить доступные варианты")
        }

        return DownloadChoicePlan(
            mediaInfo = DownloadChoiceMediaInfo(
                channelName = metadata.channel ?: metadata.uploader,
                title = metadata.title,
                durationSeconds = metadata.duration
                    ?.takeIf { it >= BigDecimal.ZERO }
                    ?.setScale(0, RoundingMode.DOWN)
                    ?.toLong(),
            ),
            options = options,
        )
    }

    private suspend fun extractCatalog(spec: DownloadSpec): YtDlpMetadataDto {
        return when (val preparation = downloadEngine.prepareCatalog(spec)) {
            is DownloadPreparation.Ready -> preparation.session.metadata
            is DownloadPreparation.NotReady -> throw DownloadChoicePlanningException(
                "Instagram временно ограничил запросы. Попробуйте позже",
            )
            is DownloadPreparation.RetryableFailure -> throw DownloadChoicePlanningException(
                "Instagram временно недоступен. Попробуйте позже",
            )
            is DownloadPreparation.SourceUnavailable -> throw DownloadChoicePlanningException(
                "Публикация недоступна для скачивания",
            )
            is DownloadPreparation.TerminalFailure -> throw DownloadChoicePlanningException(
                "Не удалось получить данные публикации",
            )
        }
    }

    private fun videoOptions(
        spec: DownloadSpec,
        metadata: YtDlpMetadataDto,
    ): List<DownloadChoiceOptionSnapshot> {
        val formats = metadata.formats.orEmpty()
        if (formats.isEmpty()) {
            return fallbackOriginalOption(spec, metadata)?.let(::listOf).orEmpty()
        }

        return buildList {
            selectVideo(formats, metadata, targetHeight = null)?.let { selected ->
                add(videoOption(spec, VIDEO_ORIGINAL_KEY, "Оригинал", selected))
            }
            TARGET_HEIGHTS.forEach { height ->
                selectVideo(formats, metadata, targetHeight = height)?.let { selected ->
                    add(videoOption(spec, "video_$height", "${height}p", selected))
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
            sizeBytes = totalSize,
            approximateSize = videoSize.approximate || audioSize?.approximate == true,
        )
    }

    private fun videoOption(
        spec: DownloadSpec,
        key: String,
        label: String,
        selected: SelectedMedia,
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
        )
    }

    private fun fallbackOriginalOption(
        spec: DownloadSpec,
        metadata: YtDlpMetadataDto,
    ): DownloadChoiceOptionSnapshot? {
        val size = metadata.filesize ?: metadata.filesizeApprox ?: return null
        return option(
            spec = spec.copy(
                presetName = "${presetPrefix(spec)}_video_original",
            ),
            key = VIDEO_ORIGINAL_KEY,
            label = "Оригинал",
            sizeBytes = size,
            approximateSize = metadata.filesize == null,
        )
    }

    private fun audioOption(
        spec: DownloadSpec,
        metadata: YtDlpMetadataDto,
    ): DownloadChoiceOptionSnapshot? {
        val plan = audioUploadPlanner.plan(metadata)
        if (plan !is AudioUploadPlan.Allowed) {
            return null
        }
        val selectedSpec = spec.withAudioQuality(plan.audioQuality)
        return option(
            spec = selectedSpec,
            key = AUDIO_KEY,
            label = "Только звук",
            sizeBytes = plan.estimatedSizeBytes,
            approximateSize = true,
            cacheKeySuffix = "audio:${plan.audioQuality}",
        )
    }

    private fun coverOption(
        spec: DownloadSpec,
        metadata: YtDlpMetadataDto,
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
            label = "Обложка",
            sizeBytes = null,
            approximateSize = false,
            cacheKeySuffix = "cover:v1",
        )
    }

    private fun option(
        spec: DownloadSpec,
        key: String,
        label: String,
        sizeBytes: Long?,
        approximateSize: Boolean,
        cacheKeySuffix: String = "choice:$key:${spec.presetName}",
    ): DownloadChoiceOptionSnapshot {
        val tooLarge = sizeBytes != null && sizeBytes > uploadLimits.maxUploadBytes
        return DownloadChoiceOptionSnapshot(
            key = key,
            label = label,
            sizeBytes = sizeBytes,
            approximateSize = approximateSize,
            available = !tooLarge,
            unavailableReason = if (tooLarge) {
                "Размер превышает лимит Telegram"
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
    val sizeBytes: Long,
    val approximateSize: Boolean,
)

private data class FormatSize(
    val bytes: Long,
    val approximate: Boolean,
)
