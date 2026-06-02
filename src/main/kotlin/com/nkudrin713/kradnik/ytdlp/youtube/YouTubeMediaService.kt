package com.nkudrin713.kradnik.ytdlp.youtube

import com.nkudrin713.kradnik.download.domain.DownloadOutputType
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.pipeline.MediaSourceService
import com.nkudrin713.kradnik.telegram.upload.TELEGRAM_UPLOAD_LIMIT_BYTES
import com.nkudrin713.kradnik.telegram.upload.TelegramFileTooLargeException
import com.nkudrin713.kradnik.util.byteToMB
import com.nkudrin713.kradnik.util.estimateAudioSizeBytes
import com.nkudrin713.kradnik.ytdlp.client.YtDlpService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Service
class YouTubeMediaService(
    private val ytDlpService: YtDlpService,
) : MediaSourceService {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun supports(extractor: String?): Boolean =
        extractor == YOUTUBE_EXTRACTOR

    override suspend fun download(
        url: String,
        metadata: MediaMetadata,
        outputType: DownloadOutputType,
        outputDir: Path,
        chatId: Long,
        taskId: Long,
    ): DownloadedFile =
        when (outputType) {
            DownloadOutputType.AUDIO -> downloadAudio(url, metadata, outputDir, chatId, taskId)
            DownloadOutputType.VIDEO -> downloadVideo(url, metadata, outputDir, chatId, taskId)
        }

    private suspend fun downloadAudio(
        url: String,
        metadata: MediaMetadata,
        outputDir: Path,
        chatId: Long,
        taskId: Long,
    ): DownloadedFile {
        for (preset in selectAudioPresets(metadata)) {
            logger.info("CHAT[{}] TASK[{}] download start", chatId, taskId)
            val downloadedFile = ytDlpService.downloadAudio(url, outputDir, preset.quality)
            if (downloadedFile.sizeBytes <= TELEGRAM_UPLOAD_LIMIT_BYTES) {
                logger.info("CHAT[{}] TASK[{}] download ok: size={}", chatId, taskId, downloadedFile.sizeBytes)
                return downloadedFile
            }
            downloadedFile.file.deleteIfExists()
        }

        throw TelegramFileTooLargeException(TELEGRAM_UPLOAD_LIMIT_BYTES + 1, TELEGRAM_UPLOAD_LIMIT_BYTES)
    }

    private suspend fun downloadVideo(
        url: String,
        metadata: MediaMetadata,
        outputDir: Path,
        chatId: Long,
        taskId: Long,
    ): DownloadedFile {
        for (preset in selectVideoPresets(metadata)) {
            logger.info("CHAT[{}] TASK[{}] video download start: crf={}", chatId, taskId, preset.crf)
            val downloadedFile = ytDlpService.downloadVideo(
                url,
                outputDir,
                preset.crf,
                preset.audioBitrate,
                preset.ffmpegPreset,
                preset.timeout,
            )
            if (downloadedFile.sizeBytes <= TELEGRAM_UPLOAD_LIMIT_BYTES) {
                logger.info("CHAT[{}] TASK[{}] video download ok: size={}", chatId, taskId, downloadedFile.sizeBytes)
                return downloadedFile
            }
            downloadedFile.file.deleteIfExists()
        }

        throw TelegramFileTooLargeException(TELEGRAM_UPLOAD_LIMIT_BYTES + 1, TELEGRAM_UPLOAD_LIMIT_BYTES)
    }

    private fun selectAudioPresets(metadata: MediaMetadata): List<AudioPreset> {
        val presets = AudioPreset.entries.filter { preset ->
            metadata.durationSeconds == null ||
                estimateAudioSizeBytes(metadata.durationSeconds, preset.bitrateBps) <= TELEGRAM_UPLOAD_LIMIT_BYTES
        }

        return presets.ifEmpty {
            throw ExpectedAudioFileTooLargeException(expectedMinSizeBytes(metadata))
        }
    }

    private fun expectedMinSizeBytes(metadata: MediaMetadata): Long? =
        metadata.durationSeconds?.let {
            estimateAudioSizeBytes(it, AudioPreset.MP3_64K.bitrateBps)
        }

    private fun selectVideoPresets(metadata: MediaMetadata): List<VideoPreset> {
        if (metadata.isShortsLike()) {
            return listOf(VideoPreset.MP4_SHORT)
        }

        val presets = VideoPreset.entries.filter { preset ->
            preset != VideoPreset.MP4_SHORT &&
                (metadata.durationSeconds == null ||
                estimateVideoSizeBytes(metadata.durationSeconds, preset.totalBitrateBps) <= TELEGRAM_UPLOAD_LIMIT_BYTES
                )
        }

        return presets.ifEmpty {
            throw ExpectedVideoFileTooLargeException(expectedMinVideoSizeBytes(metadata))
        }
    }

    private fun expectedMinVideoSizeBytes(metadata: MediaMetadata): Long? =
        metadata.durationSeconds?.let {
            estimateVideoSizeBytes(it, VideoPreset.MP4_CRF_32.totalBitrateBps)
        }

    private fun estimateVideoSizeBytes(durationSeconds: Long, bitrateBps: Long): Long =
        durationSeconds * bitrateBps / BITS_IN_BYTE

    private fun MediaMetadata.isShortsLike(): Boolean =
        durationSeconds != null &&
            durationSeconds <= SHORTS_MAX_DURATION_SECONDS &&
            isVerticalOrSquare()

    private fun MediaMetadata.isVerticalOrSquare(): Boolean {
        if (width == null || height == null) {
            return false
        }

        return height >= width
    }

    private enum class AudioPreset(
        val quality: String,
        val bitrateBps: Long,
    ) {
        MP3_128K("128K", 128_000),
        MP3_96K("96K", 96_000),
        MP3_64K("64K", 64_000),
    }

    private enum class VideoPreset(
        val crf: Int,
        val audioBitrate: String,
        val totalBitrateBps: Long,
        val ffmpegPreset: String,
        val timeout: Duration,
    ) {
        MP4_SHORT(30, "80k", 800_000, "ultrafast", 30.seconds),
        MP4_CRF_28(28, "96k", 1_100_000, "veryfast", 8.minutes),
        MP4_CRF_30(30, "80k", 800_000, "veryfast", 8.minutes),
        MP4_CRF_32(32, "64k", 600_000, "veryfast", 8.minutes),
    }

    private companion object {
        const val YOUTUBE_EXTRACTOR = "youtube"
        const val BITS_IN_BYTE = 8
        const val SHORTS_MAX_DURATION_SECONDS = 180L
    }
}

class ExpectedAudioFileTooLargeException(expectedSizeBytes: Long?) :
    RuntimeException(
        if (expectedSizeBytes == null) {
            "Expected audio file is too large. Limit is ${byteToMB(TELEGRAM_UPLOAD_LIMIT_BYTES)} MB"
        } else {
            "Expected audio file is too large: ${byteToMB(expectedSizeBytes)} MB. Limit is ${byteToMB(TELEGRAM_UPLOAD_LIMIT_BYTES)} MB"
        }
    )

class ExpectedVideoFileTooLargeException(expectedSizeBytes: Long?) :
    RuntimeException(
        if (expectedSizeBytes == null) {
            "Expected video file is too large. Limit is ${byteToMB(TELEGRAM_UPLOAD_LIMIT_BYTES)} MB"
        } else {
            "Expected video file is too large: ${byteToMB(expectedSizeBytes)} MB. Limit is ${byteToMB(TELEGRAM_UPLOAD_LIMIT_BYTES)} MB"
        }
    )
