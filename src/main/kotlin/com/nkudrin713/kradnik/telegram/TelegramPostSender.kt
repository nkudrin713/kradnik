package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.domain.PostMedia
import com.nkudrin713.kradnik.download.domain.PostMediaKind
import com.nkudrin713.kradnik.download.telegram.CachedPostItem
import com.nkudrin713.kradnik.download.telegram.DeliveryContext
import com.nkudrin713.kradnik.download.video.VideoMetadataProbe
import com.nkudrin713.kradnik.telegram.config.TelegramBotProperties
import com.pengrad.telegrambot.model.request.InputMediaPhoto
import com.pengrad.telegrambot.model.request.InputMediaVideo
import com.pengrad.telegrambot.model.request.ReplyParameters
import com.pengrad.telegrambot.model.request.richmessages.InputRichMessage
import com.pengrad.telegrambot.model.request.richmessages.richblock.InputRichBlock
import com.pengrad.telegrambot.model.request.richmessages.richblock.InputRichBlockPhoto
import com.pengrad.telegrambot.model.request.richmessages.richblock.InputRichBlockSlideshow
import com.pengrad.telegrambot.model.request.richmessages.richblock.InputRichBlockVideo
import com.pengrad.telegrambot.model.richmessages.richblock.RichBlock
import com.pengrad.telegrambot.model.richmessages.richblock.RichBlockCaption
import com.pengrad.telegrambot.model.richmessages.richblock.RichBlockPhoto
import com.pengrad.telegrambot.model.richmessages.richblock.RichBlockSlideshow
import com.pengrad.telegrambot.model.richmessages.richblock.RichBlockVideo
import com.pengrad.telegrambot.model.richmessages.richtext.RichTextPlain
import com.pengrad.telegrambot.request.richmessages.SendRichMessage
import org.springframework.stereotype.Component

/** Sends the post in one message and returns ordered, typed file references for repeat delivery. */
@Component
class TelegramPostSender(
    private val api: TelegramApiClient,
    private val probe: VideoMetadataProbe,
    private val properties: TelegramBotProperties,
) {
    suspend fun send(context: DeliveryContext, items: List<PostMedia>): List<CachedPostItem> {
        val blocks = items.map { item ->
            val uri = item.file.toAbsolutePath().normalize().toUri().toString()
            when (item.kind) {
                PostMediaKind.PHOTO -> InputRichBlockPhoto(
                    if (properties.localApi) InputMediaPhoto(uri) else InputMediaPhoto(item.file.toFile()),
                )

                PostMediaKind.VIDEO -> {
                    val metadata = probe.probe(item.file)
                    val video = if (properties.localApi) InputMediaVideo(uri) else InputMediaVideo(item.file.toFile())
                    video.width(metadata.width).height(metadata.height).supportsStreaming(true)
                    metadata.durationSeconds?.let(video::duration)
                    InputRichBlockVideo(video)
                }
            }
        }
        return sendBlocks(context, blocks, items.map { it.kind })
    }

    suspend fun sendCached(context: DeliveryContext, items: List<CachedPostItem>): List<CachedPostItem> {
        val blocks = items.map { item ->
            when (item.kind) {
                PostMediaKind.PHOTO -> InputRichBlockPhoto(InputMediaPhoto(item.fileId))
                PostMediaKind.VIDEO -> InputRichBlockVideo(InputMediaVideo(item.fileId).supportsStreaming(true))
            }
        }
        return sendBlocks(context, blocks, items.map { it.kind })
    }

    private suspend fun sendBlocks(context: DeliveryContext, blocks: List<InputRichBlock>, kinds: List<PostMediaKind>): List<CachedPostItem> {
        require(context.inlineMessageId == null) { "Full posts are available only in direct chats" }
        require(blocks.size in 1..20) { "A post must contain 1 to 20 items" }
        val caption = context.postText?.takeIf(String::isNotBlank)?.let { RichBlockCaption(RichTextPlain(it)) }
        val block = if (blocks.size == 1) {
            when (val single = blocks.single()) {
                is InputRichBlockPhoto -> single.apply { caption?.let(::caption) }
                is InputRichBlockVideo -> single.apply { caption?.let(::caption) }
                else -> error("Unsupported post media")
            }
        } else {
            InputRichBlockSlideshow(*blocks.toTypedArray()).apply { caption?.let(::caption) }
        }
        val request = SendRichMessage(context.chatId, InputRichMessage().blocks(block))
        context.replyToMessageId?.let { request.replyParameters(ReplyParameters(it).allowSendingWithoutReply(true)) }
        val response = api.executeIo(request)
        val returned = response.message()?.richMessage()?.blocks?.flatMap(::fileIds)
            ?: throw TelegramSendException("Telegram response does not contain a rich post")
        if (returned.map { it.kind } != kinds) throw TelegramSendException("Telegram rich post media does not match the upload")
        return returned
    }

    private fun fileIds(block: RichBlock): List<CachedPostItem> = when (block) {
        is RichBlockSlideshow -> block.blocks.flatMap(::fileIds)

        is RichBlockPhoto -> listOf(
            CachedPostItem(PostMediaKind.PHOTO, block.photo.lastOrNull()?.fileId() ?: throw TelegramSendException("Missing post photo")),
        )

        is RichBlockVideo -> listOf(CachedPostItem(PostMediaKind.VIDEO, block.video.fileId))

        else -> throw TelegramSendException("Unexpected block in Telegram rich post")
    }
}
