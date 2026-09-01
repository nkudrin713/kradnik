package com.nkudrin713.kradnik.download.telegram

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.telegram.TelegramMediaSender
import com.nkudrin713.kradnik.telegram.TelegramSendException
import com.nkudrin713.kradnik.telegram.TelegramSendFailureKind
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
    suspend fun send(job: DownloadJob, file: DownloadedFile): String {
        val inlineMessageId = job.telegramInlineMessageId
        if (inlineMessageId != null) {
            val fileId = uploadForInline(job, file)
            return editInline(job, inlineMessageId, fileId)
        }

        return when (job.outputType) {
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
        }
    }

    suspend fun sendCached(
        job: DownloadJob,
        fileId: String,
    ): String {
        val inlineMessageId = job.telegramInlineMessageId
        if (inlineMessageId != null) {
            return editInline(job, inlineMessageId, fileId)
        }

        return when (job.outputType) {
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
        }
    }

    private suspend fun uploadForInline(job: DownloadJob, file: DownloadedFile): String {
        val storageChatId = properties.fileStorageChatId ?: throw TelegramSendException(
            errorCode = null,
            description = "telegram.bot.file-storage-chat-id is not configured",
            kind = TelegramSendFailureKind.TERMINAL,
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
        }
    }
}
