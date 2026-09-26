package com.nkudrin713.kradnik.download.telegram

import com.nkudrin713.kradnik.download.domain.MediaArtifact
import com.nkudrin713.kradnik.telegram.TelegramMediaSender
import com.nkudrin713.kradnik.telegram.TelegramSendException
import com.nkudrin713.kradnik.telegram.config.TelegramBotProperties
import org.springframework.stereotype.Component

/**
 * Delivers typed local media through [TelegramMediaSender]. Cached delivery receives a decoded media reference;
 * persistence and cache identity stay outside this transport adapter.
 */
@Component
class TelegramFileSender(
    private val telegramMediaSender: TelegramMediaSender,
    private val properties: TelegramBotProperties,
) {
    suspend fun send(context: DeliveryContext, artifact: MediaArtifact): String {
        val inlineMessageId = context.inlineMessageId
        if (inlineMessageId != null) {
            val storageChatId = properties.fileStorageChatId ?: throw TelegramSendException(
                errorCode = null,
                description = "telegram.bot.file-storage-chat-id is not configured",
            )
            if (artifact is MediaArtifact.Photos) {
                throw TelegramSendException("Instagram image groups are unavailable in inline mode")
            }
            val fileId = sendMedia(storageChatId, null, artifact)
            return when (artifact) {
                is MediaArtifact.Video -> telegramMediaSender.editInlineVideo(inlineMessageId, fileId)

                is MediaArtifact.Audio -> telegramMediaSender.editInlineAudio(
                    inlineMessageId = inlineMessageId,
                    fileId = fileId,
                    title = artifact.metadata.title,
                    performer = artifact.metadata.performer,
                    durationSeconds = artifact.metadata.durationSeconds,
                )

                is MediaArtifact.Document -> telegramMediaSender.editInlineDocument(inlineMessageId, fileId)

                is MediaArtifact.Photos -> throw TelegramSendException("Instagram image groups are unavailable in inline mode")
            }
        }

        val fileId = sendMedia(context.chatId, context.replyToMessageId, artifact)
        sendPostText(context)
        return fileId
    }

    private suspend fun sendMedia(chatId: Long, replyToMessageId: Int?, artifact: MediaArtifact): String {
        return when (artifact) {
            is MediaArtifact.Video -> telegramMediaSender.sendVideo(
                chatId = chatId,
                file = artifact.file,
                replyToMessageId = replyToMessageId,
            )

            is MediaArtifact.Audio -> telegramMediaSender.sendAudio(
                chatId = chatId,
                file = artifact.file,
                title = artifact.metadata.title,
                performer = artifact.metadata.performer,
                durationSeconds = artifact.metadata.durationSeconds,
                replyToMessageId = replyToMessageId,
            )

            is MediaArtifact.Document -> telegramMediaSender.sendDocument(
                chatId = chatId,
                file = artifact.file,
                replyToMessageId = replyToMessageId,
            )

            is MediaArtifact.Photos -> TelegramReceiptCodec.photos(
                telegramMediaSender.sendPhotos(
                    chatId = chatId,
                    files = artifact.files,
                    replyToMessageId = replyToMessageId,
                ),
            )
        }
    }

    suspend fun sendCached(context: DeliveryContext, media: CachedMedia): String {
        val inlineId = context.inlineMessageId
        if (inlineId != null) {
            if (media is CachedMedia.Photos) throw TelegramSendException("Instagram image groups are unavailable in inline mode")
            require(media is CachedMedia.Single)
            return when (media.kind) {
                TelegramMediaKind.VIDEO -> telegramMediaSender.editInlineVideo(inlineId, media.fileId)
                TelegramMediaKind.AUDIO -> telegramMediaSender.editInlineAudio(inlineId, media.fileId, null, null, null)
                TelegramMediaKind.DOCUMENT -> telegramMediaSender.editInlineDocument(inlineId, media.fileId)
            }
        }
        val sentId = when (media) {
            is CachedMedia.Single -> when (media.kind) {
                TelegramMediaKind.VIDEO -> telegramMediaSender.sendCachedVideo(context.chatId, media.fileId, context.replyToMessageId)
                TelegramMediaKind.AUDIO -> telegramMediaSender.sendCachedAudio(context.chatId, media.fileId, replyToMessageId = context.replyToMessageId)
                TelegramMediaKind.DOCUMENT -> telegramMediaSender.sendCachedDocument(context.chatId, media.fileId, context.replyToMessageId)
            }

            is CachedMedia.Photos -> TelegramReceiptCodec.photos(telegramMediaSender.sendCachedPhotos(context.chatId, media.fileIds, context.replyToMessageId))
        }
        sendPostText(context)
        return sentId
    }

    private suspend fun sendPostText(context: DeliveryContext) {
        val postText = context.postText?.takeIf(String::isNotBlank) ?: return
        telegramMediaSender.sendMonospaceText(
            chatId = context.chatId,
            text = postText,
            replyToMessageId = context.replyToMessageId,
        )
    }
}
