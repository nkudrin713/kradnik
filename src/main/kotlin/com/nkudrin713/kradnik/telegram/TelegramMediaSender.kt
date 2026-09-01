package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.video.VideoMetadataProbe
import com.nkudrin713.kradnik.telegram.config.TelegramBotProperties
import com.pengrad.telegrambot.model.request.InputMediaAudio
import com.pengrad.telegrambot.model.request.InputMediaDocument
import com.pengrad.telegrambot.model.request.InputMediaVideo
import com.pengrad.telegrambot.model.request.ReplyParameters
import com.pengrad.telegrambot.request.EditMessageMedia
import com.pengrad.telegrambot.request.SendAudio
import com.pengrad.telegrambot.request.SendDocument
import com.pengrad.telegrambot.request.SendVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Sends fresh and cached media for [TelegramFileSender][com.nkudrin713.kradnik.download.telegram.TelegramFileSender]
 * through [TelegramApiClient]. Fresh files use multipart upload in cloud mode or shared-volume file URIs in local API
 * mode; video dimensions come from [VideoMetadataProbe]. Every successful call returns the reusable Telegram file ID.
 */
@Component
class TelegramMediaSender(
    private val apiClient: TelegramApiClient,
    private val videoMetadataProbe: VideoMetadataProbe,
    private val properties: TelegramBotProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun sendVideo(
        chatId: Long,
        file: Path,
        replyToMessageId: Int? = null,
    ): String {
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
        val request = videoRequest(chatId, file)
            .width(metadata.width)
            .height(metadata.height)
            .supportsStreaming(true)
        addReplyParameters(request, replyToMessageId)
        val response = apiClient.executeIo(
            request,
            errorContext = "(sizeMb=${formatMegabytes(fileSize)})",
        )
        val video = response.message()?.video()
            ?: throw TelegramSendException("Telegram response does not contain video")
        return video.fileId
    }

    suspend fun sendCachedVideo(
        chatId: Long,
        fileId: String,
        replyToMessageId: Int? = null,
    ): String {
        val request = SendVideo(chatId, fileId).supportsStreaming(true)
        addReplyParameters(request, replyToMessageId)
        val response = apiClient.executeIo(request)
        val video = response.message()?.video()
            ?: throw TelegramSendException("Telegram response does not contain video")
        return video.fileId
    }

    suspend fun editInlineVideo(inlineMessageId: String, fileId: String): String {
        apiClient.executeIo(
            EditMessageMedia(
                inlineMessageId,
                InputMediaVideo(fileId).supportsStreaming(true),
            )
        )
        return fileId
    }

    suspend fun sendAudio(
        chatId: Long,
        file: Path,
        title: String?,
        performer: String?,
        durationSeconds: Int?,
        replyToMessageId: Int? = null,
    ): String {
        val fileSize = fileSize(file)
        val request = audioRequest(chatId, file)
        title?.let(request::title)
        performer?.let(request::performer)
        durationSeconds?.let(request::duration)
        addReplyParameters(request, replyToMessageId)

        val response = apiClient.executeIo(
            request,
            errorContext = "(sizeMb=${formatMegabytes(fileSize)})",
        )
        val audio = response.message()?.audio()
            ?: throw TelegramSendException("Telegram response does not contain audio")
        return audio.fileId ?: throw TelegramSendException("Telegram audio file_id is empty")
    }

    suspend fun sendCachedAudio(
        chatId: Long,
        fileId: String,
        replyToMessageId: Int? = null,
    ): String {
        val request = SendAudio(chatId, fileId)
        addReplyParameters(request, replyToMessageId)
        val response = apiClient.executeIo(request)
        val audio = response.message()?.audio()
            ?: throw TelegramSendException("Telegram response does not contain audio")
        return audio.fileId ?: throw TelegramSendException("Telegram audio file_id is empty")
    }

    suspend fun editInlineAudio(
        inlineMessageId: String,
        fileId: String,
        title: String?,
        performer: String?,
        durationSeconds: Int?,
    ): String {
        val media = InputMediaAudio(fileId)
        title?.let(media::title)
        performer?.let(media::performer)
        durationSeconds?.let(media::duration)
        apiClient.executeIo(EditMessageMedia(inlineMessageId, media))
        return fileId
    }

    suspend fun sendDocument(
        chatId: Long,
        file: Path,
        replyToMessageId: Int? = null,
    ): String {
        val fileSize = fileSize(file)
        val request = documentRequest(chatId, file)
        addReplyParameters(request, replyToMessageId)
        val response = apiClient.executeIo(
            request,
            errorContext = "(sizeMb=${formatMegabytes(fileSize)})",
        )
        val document = response.message()?.document()
            ?: throw TelegramSendException("Telegram response does not contain document")
        return document.fileId()
    }

    suspend fun sendCachedDocument(
        chatId: Long,
        fileId: String,
        replyToMessageId: Int? = null,
    ): String {
        val request = SendDocument(chatId, fileId)
        addReplyParameters(request, replyToMessageId)
        val response = apiClient.executeIo(request)
        val document = response.message()?.document()
            ?: throw TelegramSendException("Telegram response does not contain document")
        return document.fileId()
    }

    suspend fun editInlineDocument(inlineMessageId: String, fileId: String): String {
        apiClient.executeIo(EditMessageMedia(inlineMessageId, InputMediaDocument(fileId)))
        return fileId
    }

    private suspend fun fileSize(file: Path): Long {
        return withContext(Dispatchers.IO) {
            Files.size(file)
        }
    }

    private fun videoRequest(chatId: Long, file: Path): SendVideo {
        return if (properties.localApi) {
            SendVideo(chatId, localFileUri(file))
        } else {
            SendVideo(chatId, file.toFile())
        }
    }

    private fun audioRequest(chatId: Long, file: Path): SendAudio {
        return if (properties.localApi) {
            SendAudio(chatId, localFileUri(file))
        } else {
            SendAudio(chatId, file.toFile())
        }
    }

    private fun documentRequest(chatId: Long, file: Path): SendDocument {
        return if (properties.localApi) {
            SendDocument(chatId, localFileUri(file))
        } else {
            SendDocument(chatId, file.toFile())
        }
    }

    private fun localFileUri(file: Path): String {
        return file.toAbsolutePath().normalize().toUri().toString()
    }

    private fun formatMegabytes(bytes: Long): String {
        return String.format(Locale.US, "%.2f", bytes / BYTES_IN_MEGABYTE)
    }

    private fun addReplyParameters(
        request: com.pengrad.telegrambot.request.AbstractSendRequest<*>,
        replyToMessageId: Int?,
    ) {
        replyToMessageId ?: return
        request.replyParameters(
            ReplyParameters(replyToMessageId)
                .allowSendingWithoutReply(true)
        )
    }

    private companion object {
        private const val BYTES_IN_MEGABYTE = 1024.0 * 1024.0
    }
}
