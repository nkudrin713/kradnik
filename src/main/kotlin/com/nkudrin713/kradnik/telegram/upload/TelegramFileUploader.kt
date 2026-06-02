package com.nkudrin713.kradnik.telegram.upload

import com.nkudrin713.kradnik.download.domain.DownloadOutputType
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.service.TelegramFileResult
import com.nkudrin713.kradnik.util.byteToMB
import org.springframework.stereotype.Service
import org.telegram.telegrambots.meta.api.methods.send.SendAudio
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.generics.TelegramClient

const val TELEGRAM_UPLOAD_LIMIT_BYTES: Long = 50 * 1024 * 1024

@Service
class TelegramFileUploader(
    private val telegramClient: TelegramClient,
) {
    fun upload(chatId: Long, outputType: DownloadOutputType, downloadedFile: DownloadedFile): TelegramFileResult {
        if (downloadedFile.sizeBytes > TELEGRAM_UPLOAD_LIMIT_BYTES) {
            throw TelegramFileTooLargeException(downloadedFile.sizeBytes, TELEGRAM_UPLOAD_LIMIT_BYTES)
        }

        return when (outputType) {
            DownloadOutputType.AUDIO -> uploadAudio(chatId, downloadedFile)
            DownloadOutputType.VIDEO -> throw UnsupportedTelegramUploadException(outputType)
        }
    }

    private fun uploadAudio(chatId: Long, downloadedFile: DownloadedFile): TelegramFileResult {
        val message = telegramClient.execute(
            SendAudio.builder()
                .chatId(chatId)
                .audio(InputFile(downloadedFile.file.toFile(), downloadedFile.file.fileName.toString()))
                .build()
        )

        val audio = message.audio ?: throw TelegramUploadException("Telegram response does not contain audio")

        return TelegramFileResult(
            fileId = audio.fileId,
            fileSize = audio.fileSize ?: downloadedFile.sizeBytes,
        )
    }
}

class TelegramFileTooLargeException(sizeBytes: Long, limitBytes: Long) :
    RuntimeException("Telegram file is too large: ${byteToMB(sizeBytes)} MB. Limit is ${byteToMB(limitBytes)} MB")

class UnsupportedTelegramUploadException(outputType: DownloadOutputType) :
    RuntimeException("Telegram upload is not supported for outputType=$outputType")

class TelegramUploadException(message: String) : RuntimeException(message)
