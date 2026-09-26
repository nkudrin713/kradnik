package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.video.VideoMetadataProbe
import com.nkudrin713.kradnik.telegram.config.TelegramBotProperties
import com.pengrad.telegrambot.model.request.InputMediaAudio
import com.pengrad.telegrambot.model.request.InputMediaDocument
import com.pengrad.telegrambot.model.request.InputMediaPhoto
import com.pengrad.telegrambot.model.request.InputMediaVideo
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.model.request.ReplyParameters
import com.pengrad.telegrambot.model.request.richmessages.InputRichMessage
import com.pengrad.telegrambot.model.request.richmessages.richblock.InputRichBlockAudio
import com.pengrad.telegrambot.model.richmessages.richblock.RichBlockAudio
import com.pengrad.telegrambot.request.EditMessageMedia
import com.pengrad.telegrambot.request.SendAudio
import com.pengrad.telegrambot.request.SendDocument
import com.pengrad.telegrambot.request.SendMediaGroup
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.request.SendPhoto
import com.pengrad.telegrambot.request.SendVideo
import com.pengrad.telegrambot.request.richmessages.SendRichMessage
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
            ),
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
        title: String? = null,
        performer: String? = null,
        durationSeconds: Int? = null,
        replyToMessageId: Int? = null,
    ): String {
        val request = SendAudio(chatId, fileId)
        title?.let(request::title)
        performer?.let(request::performer)
        durationSeconds?.let(request::duration)
        addReplyParameters(request, replyToMessageId)
        val response = apiClient.executeIo(request)
        val audio = response.message()?.audio()
            ?: throw TelegramSendException("Telegram response does not contain audio")
        return audio.fileId ?: throw TelegramSendException("Telegram audio file_id is empty")
    }

    suspend fun sendCachedAudios(
        chatId: Long,
        audios: List<TelegramAudio>,
        replyToMessageId: Int? = null,
    ): List<String> {
        require(audios.isNotEmpty()) { "At least one audio file is required" }
        return audios.chunked(MAX_RICH_AUDIO_COUNT).flatMapIndexed { index, chunk ->
            val blocks = chunk.map { audio ->
                val media = InputMediaAudio(audio.fileId).title(audio.title)
                audio.performer?.let(media::performer)
                audio.durationSeconds?.let(media::duration)
                InputRichBlockAudio(media)
            }
            val request = SendRichMessage(chatId, InputRichMessage().blocks(*blocks.toTypedArray()))
            if (index == 0) addReplyParameters(request, replyToMessageId)
            val returned = apiClient.executeIo(request).message()?.richMessage()?.blocks
                ?: throw TelegramSendException("Telegram response does not contain playlist audio")
            if (returned.size != chunk.size) throw TelegramSendException("Telegram playlist audio count does not match")
            returned.map { block ->
                (block as? RichBlockAudio)?.audio?.fileId
                    ?: throw TelegramSendException("Telegram playlist response does not contain audio")
            }
        }
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

    suspend fun sendPhotos(
        chatId: Long,
        files: List<Path>,
        replyToMessageId: Int? = null,
    ): List<String> {
        require(files.isNotEmpty()) { "At least one photo is required" }
        return files.chunked(MAX_MEDIA_GROUP_SIZE).flatMapIndexed { index, chunk ->
            val replyId = replyToMessageId.takeIf { index == 0 }
            if (chunk.size == 1) {
                listOf(sendPhoto(chatId, chunk.single(), replyId))
            } else {
                sendPhotoGroup(chatId, chunk, replyId)
            }
        }
    }

    suspend fun sendCachedPhotos(
        chatId: Long,
        fileIds: List<String>,
        replyToMessageId: Int? = null,
    ): List<String> {
        require(fileIds.isNotEmpty()) { "At least one photo file ID is required" }
        return fileIds.chunked(MAX_MEDIA_GROUP_SIZE).flatMapIndexed { index, chunk ->
            val replyId = replyToMessageId.takeIf { index == 0 }
            if (chunk.size == 1) {
                listOf(sendCachedPhoto(chatId, chunk.single(), replyId))
            } else {
                sendCachedPhotoGroup(chatId, chunk, replyId)
            }
        }
    }

    suspend fun sendMonospaceText(
        chatId: Long,
        text: String,
        replyToMessageId: Int? = null,
    ) {
        val request = SendMessage(chatId, "<pre>${text.escapeHtml()}</pre>")
            .parseMode(ParseMode.HTML)
        addReplyParameters(request, replyToMessageId)
        apiClient.executeIo(request)
    }

    private suspend fun sendPhoto(chatId: Long, file: Path, replyToMessageId: Int?): String {
        val request = if (properties.localApi) {
            SendPhoto(chatId, localFileUri(file))
        } else {
            SendPhoto(chatId, file.toFile())
        }
        addReplyParameters(request, replyToMessageId)
        val response = apiClient.executeIo(
            request,
            errorContext = "(sizeMb=${formatMegabytes(fileSize(file))})",
        )
        return response.message()?.photo()?.lastOrNull()?.fileId()
            ?: throw TelegramSendException("Telegram response does not contain photo")
    }

    private suspend fun sendCachedPhoto(chatId: Long, fileId: String, replyToMessageId: Int?): String {
        val request = SendPhoto(chatId, fileId)
        addReplyParameters(request, replyToMessageId)
        val response = apiClient.executeIo(request)
        return response.message()?.photo()?.lastOrNull()?.fileId()
            ?: throw TelegramSendException("Telegram response does not contain photo")
    }

    private suspend fun sendPhotoGroup(
        chatId: Long,
        files: List<Path>,
        replyToMessageId: Int?,
    ): List<String> {
        val media = files.map { file ->
            if (properties.localApi) InputMediaPhoto(localFileUri(file)) else InputMediaPhoto(file.toFile())
        }
        val request = SendMediaGroup(chatId, *media.toTypedArray())
        addReplyParameters(request, replyToMessageId)
        val response = apiClient.executeIo(request)
        return response.messages()?.map { message ->
            message.photo()?.lastOrNull()?.fileId()
                ?: throw TelegramSendException("Telegram media group response does not contain photo")
        } ?: throw TelegramSendException("Telegram response does not contain media group")
    }

    private suspend fun sendCachedPhotoGroup(
        chatId: Long,
        fileIds: List<String>,
        replyToMessageId: Int?,
    ): List<String> {
        val request = SendMediaGroup(chatId, *fileIds.map(::InputMediaPhoto).toTypedArray())
        addReplyParameters(request, replyToMessageId)
        val response = apiClient.executeIo(request)
        return response.messages()?.map { message ->
            message.photo()?.lastOrNull()?.fileId()
                ?: throw TelegramSendException("Telegram cached media group response does not contain photo")
        } ?: throw TelegramSendException("Telegram response does not contain cached media group")
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

    private fun String.escapeHtml(): String {
        return replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun addReplyParameters(
        request: com.pengrad.telegrambot.request.AbstractSendRequest<*>,
        replyToMessageId: Int?,
    ) {
        replyToMessageId ?: return
        request.replyParameters(
            ReplyParameters(replyToMessageId)
                .allowSendingWithoutReply(true),
        )
    }

    private fun addReplyParameters(request: SendMediaGroup, replyToMessageId: Int?) {
        replyToMessageId ?: return
        request.replyParameters(
            ReplyParameters(replyToMessageId)
                .allowSendingWithoutReply(true),
        )
    }

    private companion object {
        private const val BYTES_IN_MEGABYTE = 1024.0 * 1024.0
        private const val MAX_MEDIA_GROUP_SIZE = 10
        private const val MAX_RICH_AUDIO_COUNT = 50
    }
}

data class TelegramAudio(
    val fileId: String,
    val title: String,
    val performer: String? = null,
    val durationSeconds: Int? = null,
)
