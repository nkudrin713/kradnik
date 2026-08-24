package com.nkudrin713.kradnik.download.telegram

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.telegram.TelegramMediaSender
import org.springframework.stereotype.Component

@Component
class TelegramFileSender(
    private val telegramMediaSender: TelegramMediaSender,
) {
    suspend fun send(job: DownloadJob, file: DownloadedFile): TelegramFileSendResult {
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
        }

        return TelegramFileSendResult(
            telegramFileId = fileId,
            downloadedFileSize = file.sizeBytes,
        )
    }

    suspend fun sendCached(
        job: DownloadJob,
        fileId: String,
        downloadedFileSize: Long?,
    ): TelegramFileSendResult {
        val sentFileId = when (job.outputType) {
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

        return TelegramFileSendResult(
            telegramFileId = sentFileId,
            downloadedFileSize = downloadedFileSize,
        )
    }
}

data class TelegramFileSendResult(
    val telegramFileId: String,
    val downloadedFileSize: Long?,
)
