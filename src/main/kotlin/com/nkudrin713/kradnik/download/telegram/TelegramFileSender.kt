package com.nkudrin713.kradnik.download.telegram

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.service.DownloadedFileResult
import com.nkudrin713.kradnik.telegram.TelegramMediaSender
import org.springframework.stereotype.Component

@Component
class TelegramFileSender(
    private val telegramMediaSender: TelegramMediaSender,
) {
    suspend fun send(job: DownloadJob, file: DownloadedFile): TelegramFileSendResult {
        val result = when (job.outputType) {
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
        }

        return TelegramFileSendResult(
            telegramFileId = result.fileId,
            telegramFileSize = result.fileSize,
            downloadedFileSize = file.sizeBytes,
        )
    }

    suspend fun sendCached(
        job: DownloadJob,
        fileId: String,
        downloadedFileSize: Long?,
    ): TelegramFileSendResult {
        val result = when (job.outputType) {
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
        }

        return TelegramFileSendResult(
            telegramFileId = result.fileId,
            telegramFileSize = result.fileSize,
            downloadedFileSize = downloadedFileSize,
        )
    }
}

data class TelegramFileSendResult(
    val telegramFileId: String,
    val telegramFileSize: Long?,
    val downloadedFileSize: Long?,
) {
    fun toDownloadedFileResult(): DownloadedFileResult {
        return DownloadedFileResult(
            telegramFileId = telegramFileId,
            telegramFileSize = telegramFileSize,
            downloadedFileSize = downloadedFileSize,
        )
    }
}
