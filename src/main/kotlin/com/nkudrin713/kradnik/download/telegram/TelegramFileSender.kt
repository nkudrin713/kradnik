package com.nkudrin713.kradnik.download.telegram

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.telegram.TelegramMediaSender
import com.nkudrin713.kradnik.telegram.TelegramSendException
import com.nkudrin713.kradnik.telegram.config.TelegramBotProperties
import org.springframework.stereotype.Component

/**
 * Maps each [DownloadJob.outputType] to matching fresh-file and cached-file operations on [TelegramMediaSender].
 * [DownloadJobProcessor][com.nkudrin713.kradnik.download.processing.DownloadJobProcessor] can therefore share one
 * delivery path and always receive the reusable Telegram file ID returned by the API.
 */
@Component
class TelegramFileSender(
    private val telegramMediaSender: TelegramMediaSender,
    private val properties: TelegramBotProperties,
) {
    private val objectMapper = jacksonObjectMapper()

    suspend fun send(job: DownloadJob, file: DownloadedFile): String {
        val inlineMessageId = job.telegramInlineMessageId
        if (inlineMessageId != null) {
            val fileId = uploadForInline(job, file)
            return editInline(job, inlineMessageId, fileId)
        }

        val fileId = when (job.outputType) {
            OutputType.VIDEO -> telegramMediaSender.sendVideo(
                chatId = job.telegramChatId,
                file = file.file,
                replyToMessageId = job.telegramRequestMessageId,
            )
            OutputType.AUDIO -> telegramMediaSender.sendAudio(
                chatId = job.telegramChatId,
                file = file.file,
                title = job.sourceAudioTitle,
                performer = job.sourceAudioPerformer,
                durationSeconds = job.sourceDurationSeconds,
                replyToMessageId = job.telegramRequestMessageId,
            )
            OutputType.COVER -> telegramMediaSender.sendDocument(
                chatId = job.telegramChatId,
                file = file.file,
                replyToMessageId = job.telegramRequestMessageId,
            )
            OutputType.IMAGES -> encodePhotoIds(
                telegramMediaSender.sendPhotos(
                    chatId = job.telegramChatId,
                    files = file.files,
                    replyToMessageId = job.telegramRequestMessageId,
                )
            )
        }
        sendPostText(job)
        return fileId
    }

    suspend fun sendCached(
        job: DownloadJob,
        fileId: String,
    ): String {
        val inlineMessageId = job.telegramInlineMessageId
        if (inlineMessageId != null) {
            return editInline(job, inlineMessageId, fileId)
        }

        val sentId = when (job.outputType) {
            OutputType.VIDEO -> telegramMediaSender.sendCachedVideo(
                chatId = job.telegramChatId,
                fileId = fileId,
                replyToMessageId = job.telegramRequestMessageId,
            )
            OutputType.AUDIO -> telegramMediaSender.sendCachedAudio(
                chatId = job.telegramChatId,
                fileId = fileId,
                replyToMessageId = job.telegramRequestMessageId,
            )
            OutputType.COVER -> telegramMediaSender.sendCachedDocument(
                chatId = job.telegramChatId,
                fileId = fileId,
                replyToMessageId = job.telegramRequestMessageId,
            )
            OutputType.IMAGES -> encodePhotoIds(
                telegramMediaSender.sendCachedPhotos(
                    chatId = job.telegramChatId,
                    fileIds = decodePhotoIds(fileId),
                    replyToMessageId = job.telegramRequestMessageId,
                )
            )
        }
        sendPostText(job)
        return sentId
    }

    private suspend fun uploadForInline(job: DownloadJob, file: DownloadedFile): String {
        val storageChatId = properties.fileStorageChatId ?: throw TelegramSendException(
            errorCode = null,
            description = "telegram.bot.file-storage-chat-id is not configured",
        )
        return when (job.outputType) {
            OutputType.VIDEO -> telegramMediaSender.sendVideo(storageChatId, file.file)
            OutputType.AUDIO -> telegramMediaSender.sendAudio(
                chatId = storageChatId,
                file = file.file,
                title = job.sourceAudioTitle,
                performer = job.sourceAudioPerformer,
                durationSeconds = job.sourceDurationSeconds,
            )
            OutputType.COVER -> telegramMediaSender.sendDocument(storageChatId, file.file)
            OutputType.IMAGES -> throw TelegramSendException("Instagram image groups are unavailable in inline mode")
        }
    }

    private suspend fun editInline(job: DownloadJob, inlineMessageId: String, fileId: String): String {
        return when (job.outputType) {
            OutputType.VIDEO -> telegramMediaSender.editInlineVideo(inlineMessageId, fileId)
            OutputType.AUDIO -> telegramMediaSender.editInlineAudio(
                inlineMessageId = inlineMessageId,
                fileId = fileId,
                title = job.sourceAudioTitle,
                performer = job.sourceAudioPerformer,
                durationSeconds = job.sourceDurationSeconds,
            )
            OutputType.COVER -> telegramMediaSender.editInlineDocument(inlineMessageId, fileId)
            OutputType.IMAGES -> throw TelegramSendException("Instagram image groups are unavailable in inline mode")
        }
    }

    private fun encodePhotoIds(fileIds: List<String>): String {
        return PHOTO_GROUP_PREFIX + objectMapper.writeValueAsString(fileIds)
    }

    private fun decodePhotoIds(value: String): List<String> {
        require(value.startsWith(PHOTO_GROUP_PREFIX)) { "Cached photo group has invalid format" }
        return objectMapper.readValue(value.removePrefix(PHOTO_GROUP_PREFIX))
    }

    private suspend fun sendPostText(job: DownloadJob) {
        val postText = job.sourcePostText?.takeIf(String::isNotBlank) ?: return
        telegramMediaSender.sendMonospaceText(
            chatId = job.telegramChatId,
            text = postText,
            replyToMessageId = job.telegramRequestMessageId,
        )
    }

    private companion object {
        private const val PHOTO_GROUP_PREFIX = "photo-group:"
    }
}
