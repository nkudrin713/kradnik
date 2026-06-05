package com.nkudrin713.kradnik.download.telegram

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.service.DownloadedFileResult
import com.nkudrin713.kradnik.telegram.TelegramSender
import org.springframework.stereotype.Component

@Component
class TelegramFileSender(
    private val telegramSender: TelegramSender,
) {
    suspend fun send(job: DownloadJob, file: DownloadedFile): TelegramFileSendResult {
        val result = when (job.outputType) {
            OutputType.VIDEO -> telegramSender.sendVideo(job.telegramChatId, file.file)
            OutputType.AUDIO -> telegramSender.sendAudio(job.telegramChatId, file.file)
        }

        return TelegramFileSendResult(
            telegramFileId = result.fileId,
            telegramFileSize = result.fileSize,
            downloadedFileSize = file.sizeBytes,
        )
    }

    fun sendCached(
        job: DownloadJob,
        fileId: String,
        downloadedFileSize: Long?,
    ): TelegramFileSendResult {
        val result = when (job.outputType) {
            OutputType.VIDEO -> telegramSender.sendCachedVideo(job.telegramChatId, fileId)
            OutputType.AUDIO -> telegramSender.sendCachedAudio(job.telegramChatId, fileId)
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
