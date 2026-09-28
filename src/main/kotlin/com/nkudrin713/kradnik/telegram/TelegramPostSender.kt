package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.domain.PostMedia
import com.nkudrin713.kradnik.download.domain.PostMediaKind
import com.nkudrin713.kradnik.download.telegram.CachedPostItem
import com.nkudrin713.kradnik.download.telegram.DeliveryContext
import com.nkudrin713.kradnik.download.video.VideoMetadata
import com.nkudrin713.kradnik.download.video.VideoMetadataProbe
import com.nkudrin713.kradnik.telegram.config.TelegramBotProperties
import com.pengrad.telegrambot.model.LinkPreviewOptions
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.request.InputMedia
import com.pengrad.telegrambot.model.request.InputMediaPhoto
import com.pengrad.telegrambot.model.request.InputMediaVideo
import com.pengrad.telegrambot.model.request.ReplyParameters
import com.pengrad.telegrambot.request.SendMediaGroup
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.request.SendPhoto
import com.pengrad.telegrambot.request.SendVideo
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component
import java.io.File

/** Sends ordinary photo/video albums and returns ordered, typed file references for repeat delivery. */
@Component
class TelegramPostSender(
    private val api: TelegramApiClient,
    private val probe: VideoMetadataProbe,
    private val properties: TelegramBotProperties,
) {
    private data class Item(val kind: PostMediaKind, val file: File? = null, val reference: String? = null, val metadata: VideoMetadata? = null)

    suspend fun send(context: DeliveryContext, items: List<PostMedia>): List<CachedPostItem> {
        validate(context, items.size)
        return sendItems(
            context,
            items.map { item ->
                Item(
                    item.kind,
                    file = item.file.toFile().takeUnless { properties.localApi },
                    reference = item.file.toAbsolutePath().normalize().toUri().toString().takeIf { properties.localApi },
                    metadata = if (item.kind == PostMediaKind.VIDEO) probe.probe(item.file) else null,
                )
            },
        )
    }

    suspend fun sendCached(context: DeliveryContext, items: List<CachedPostItem>): List<CachedPostItem> {
        validate(context, items.size)
        return sendItems(context, items.map { Item(it.kind, reference = it.fileId) })
    }

    private fun validate(context: DeliveryContext, count: Int) {
        require(context.inlineMessageId == null) { "Full posts are available only in direct chats" }
        require(count in 1..20) { "A post must contain 1 to 20 items" }
    }

    private suspend fun sendItems(context: DeliveryContext, items: List<Item>): List<CachedPostItem> {
        val text = context.postText?.takeIf(String::isNotBlank)
        val returned = mutableListOf<CachedPostItem>()
        var firstMessageId: Int? = null
        // Eleven attachments need 9 + 2, so the final attachment stays in an album.
        val groups = items.chunked(if (items.size == 11) 9 else 10)
        val caption = text?.takeIf { it.length + (if (groups.size > 1) 5 else 0) <= 1024 }
        for ((index, group) in groups.withIndex()) {
            if (index > 0) delay(1000)
            val reply = context.replyToMessageId?.takeIf { index == 0 }?.let { ReplyParameters(it).allowSendingWithoutReply(true) }
            val groupCaption = buildString {
                if (index == 0 && caption != null) append(caption)
                if (groups.size > 1) {
                    if (isNotEmpty()) append("\n\n")
                    append("${index + 1}/${groups.size}")
                }
            }.takeIf(String::isNotEmpty)
            val messages = if (group.size == 1) {
                listOf(sendSingle(context.chatId, group.single(), groupCaption, reply))
            } else {
                val media = group.map(::media)
                groupCaption?.let { media.first().caption(it) }
                val request = SendMediaGroup(context.chatId, *media.toTypedArray())
                reply?.let(request::replyParameters)
                api.executeIo(request).messages()?.toList() ?: throw TelegramSendException("Telegram response does not contain a post album")
            }
            if (messages.size != group.size) throw TelegramSendException("Telegram post media count does not match the upload")
            if (firstMessageId == null) firstMessageId = messages.first().messageId()
            returned += messages.zip(group).map { (message, item) -> fileId(message, item.kind) }
        }
        if (text != null && caption == null) {
            var start = 0
            while (start < text.length) {
                var end = minOf(start + 4096, text.length)
                if (end < text.length && text[end - 1].isHighSurrogate()) end--
                val request = SendMessage(context.chatId, text.substring(start, end)).linkPreviewOptions(LinkPreviewOptions().isDisabled(true))
                firstMessageId?.let { request.replyParameters(ReplyParameters(it).allowSendingWithoutReply(true)) }
                api.executeIo(request)
                start = end
            }
        }
        return returned
    }

    private fun media(item: Item): InputMedia<*> = when (item.kind) {
        PostMediaKind.PHOTO -> if (item.file != null) InputMediaPhoto(item.file) else InputMediaPhoto(requireNotNull(item.reference))

        PostMediaKind.VIDEO -> {
            val video = if (item.file != null) InputMediaVideo(item.file) else InputMediaVideo(requireNotNull(item.reference))
            video.supportsStreaming(true)
            item.metadata?.let { metadata ->
                video.width(metadata.width).height(metadata.height)
                metadata.durationSeconds?.let(video::duration)
            }
            video
        }
    }

    private suspend fun sendSingle(chatId: Long, item: Item, caption: String?, reply: ReplyParameters?): Message {
        val response = when (item.kind) {
            PostMediaKind.PHOTO -> {
                val request = if (item.file != null) SendPhoto(chatId, item.file) else SendPhoto(chatId, requireNotNull(item.reference))
                caption?.let(request::caption)
                reply?.let(request::replyParameters)
                api.executeIo(request)
            }

            PostMediaKind.VIDEO -> {
                val request = if (item.file != null) SendVideo(chatId, item.file) else SendVideo(chatId, requireNotNull(item.reference))
                request.supportsStreaming(true)
                item.metadata?.let { metadata ->
                    request.width(metadata.width).height(metadata.height)
                    metadata.durationSeconds?.let(request::duration)
                }
                caption?.let(request::caption)
                reply?.let(request::replyParameters)
                api.executeIo(request)
            }
        }
        return response.message() ?: throw TelegramSendException("Telegram response does not contain post media")
    }

    private fun fileId(message: Message, kind: PostMediaKind): CachedPostItem {
        val id = when (kind) {
            PostMediaKind.PHOTO -> message.photo()?.lastOrNull()?.fileId()
            PostMediaKind.VIDEO -> message.video()?.fileId
        } ?: throw TelegramSendException("Telegram post media does not match the upload")
        return CachedPostItem(kind, id)
    }
}
