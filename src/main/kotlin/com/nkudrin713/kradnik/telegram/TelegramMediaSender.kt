package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.video.VideoMetadataProbe
import com.pengrad.telegrambot.request.SendAudio
import com.pengrad.telegrambot.request.SendVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

@Component
class TelegramMediaSender(
    private val apiClient: TelegramApiClient,
    private val videoMetadataProbe: VideoMetadataProbe,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun sendVideo(chatId: Long, file: Path): TelegramSendResult {
        val fileSize = fileSize(file)
        val metadata = videoMetadataProbe.probe(file)
        logger.info(
            "Telegram video upload metadata: fileName={}, container={}, width={}, height={}, sar={}, dar={}, " +
                    "videoCodec={}, audioCodec={}, codecTag={}, profile={}, level={}, pixelFormat={}, " +
                    "frameRate={}, colorSpace={}, colorTransfer={}, colorPrimaries={}",
            file.fileName,
            metadata.containerFormat,
            metadata.width,
            metadata.height,
            metadata.sampleAspectRatio,
            metadata.displayAspectRatio,
            metadata.videoCodec,
            metadata.audioCodec,
            metadata.codecTag,
            metadata.codecProfile,
            metadata.codecLevel,
            metadata.pixelFormat,
            metadata.frameRate,
            metadata.colorSpace,
            metadata.colorTransfer,
            metadata.colorPrimaries,
        )
        val response = apiClient.executeIo(
            SendVideo(chatId, file.toFile())
                .width(metadata.width)
                .height(metadata.height)
                .supportsStreaming(true),
            errorContext = "(sizeMb=${formatMegabytes(fileSize)})",
        )
        val video = response.message()?.video()
            ?: throw TelegramSendException("Telegram response does not contain video")
        return TelegramSendResult(video.fileId, video.fileSize)
    }

    suspend fun sendCachedVideo(chatId: Long, fileId: String): TelegramSendResult {
        val response = apiClient.executeIo(
            SendVideo(chatId, fileId).supportsStreaming(true),
        )
        val video = response.message()?.video()
            ?: throw TelegramSendException("Telegram response does not contain video")
        return TelegramSendResult(video.fileId, video.fileSize)
    }

    suspend fun sendAudio(
        chatId: Long,
        file: Path,
        title: String?,
        performer: String?,
        durationSeconds: Int?,
    ): TelegramSendResult {
        val fileSize = fileSize(file)
        val request = SendAudio(chatId, file.toFile())
        title?.let(request::title)
        performer?.let(request::performer)
        durationSeconds?.let(request::duration)

        val response = apiClient.executeIo(
            request,
            errorContext = "(sizeMb=${formatMegabytes(fileSize)})",
        )
        val audio = response.message()?.audio()
            ?: throw TelegramSendException("Telegram response does not contain audio")
        return TelegramSendResult(
            fileId = audio.fileId ?: throw TelegramSendException("Telegram audio file_id is empty"),
            fileSize = audio.fileSize,
        )
    }

    suspend fun sendCachedAudio(chatId: Long, fileId: String): TelegramSendResult {
        val response = apiClient.executeIo(SendAudio(chatId, fileId))
        val audio = response.message()?.audio()
            ?: throw TelegramSendException("Telegram response does not contain audio")
        return TelegramSendResult(
            fileId = audio.fileId ?: throw TelegramSendException("Telegram audio file_id is empty"),
            fileSize = audio.fileSize,
        )
    }

    private suspend fun fileSize(file: Path): Long {
        return withContext(Dispatchers.IO) {
            Files.size(file)
        }
    }

    private fun formatMegabytes(bytes: Long): String {
        return String.format(Locale.US, "%.2f", bytes / BYTES_IN_MEGABYTE)
    }

    private companion object {
        private const val BYTES_IN_MEGABYTE = 1024.0 * 1024.0
    }
}

data class TelegramSendResult(
    val fileId: String,
    val fileSize: Long?,
)
