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
            DownloadOutputType.VIDEO -> throw UnsupportedOperationException("YouTube video download is not implemented yet")
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

    private enum class AudioPreset(
        val quality: String,
        val bitrateBps: Long,
    ) {
        MP3_128K("128K", 128_000),
        MP3_96K("96K", 96_000),
        MP3_64K("64K", 64_000),
    }

    private companion object {
        const val YOUTUBE_EXTRACTOR = "youtube"
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
