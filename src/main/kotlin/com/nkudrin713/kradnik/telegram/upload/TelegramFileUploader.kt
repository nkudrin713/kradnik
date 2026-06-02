package com.nkudrin713.kradnik.telegram.upload

import com.nkudrin713.kradnik.download.domain.DownloadOutputType
import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.service.TelegramFileResult
import com.nkudrin713.kradnik.util.byteToMB
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.telegram.telegrambots.meta.api.methods.send.SendAudio
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.generics.TelegramClient

const val TELEGRAM_UPLOAD_LIMIT_BYTES: Long = 50 * 1024 * 1024

@Service
class TelegramFileUploader(
    private val telegramClient: TelegramClient,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun upload(
        chatId: Long,
        taskId: Long,
        outputType: DownloadOutputType,
        downloadedFile: DownloadedFile,
        sourceTitle: String?,
    ): TelegramFileResult {
        if (downloadedFile.sizeBytes > TELEGRAM_UPLOAD_LIMIT_BYTES) {
            throw TelegramFileTooLargeException(downloadedFile.sizeBytes, TELEGRAM_UPLOAD_LIMIT_BYTES)
        }

        logger.info("CHAT[{}] TASK[{}] upload start", chatId, taskId)
        return when (outputType) {
            DownloadOutputType.AUDIO -> uploadAudio(chatId, downloadedFile, sourceTitle)
            DownloadOutputType.VIDEO -> throw UnsupportedTelegramUploadException(outputType)
        }
    }

    private fun uploadAudio(chatId: Long, downloadedFile: DownloadedFile, sourceTitle: String?): TelegramFileResult {
        val fileName = telegramFileName(sourceTitle, downloadedFile)
        logger.info("CHAT[{}] telegram sendAudio: file={}", chatId, fileName)
        val message = telegramClient.execute(
            SendAudio.builder()
                .chatId(chatId)
                .audio(InputFile(downloadedFile.file.toFile(), fileName))
                .build()
        )

        val audio = message.audio ?: throw TelegramUploadException("Telegram response does not contain audio")

        return TelegramFileResult(
            fileId = audio.fileId,
            fileSize = audio.fileSize ?: downloadedFile.sizeBytes,
        )
    }

    private fun telegramFileName(sourceTitle: String?, downloadedFile: DownloadedFile): String {
        val title = sourceTitle
            ?.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(120)
            ?.takeIf { it.isNotBlank() }
            ?: downloadedFile.file.fileName.toString().substringBeforeLast(".")

        return "$title.${downloadedFile.ext}"
    }
}

class TelegramFileTooLargeException(sizeBytes: Long, limitBytes: Long) :
    RuntimeException("Telegram file is too large: ${byteToMB(sizeBytes)} MB. Limit is ${byteToMB(limitBytes)} MB")

class UnsupportedTelegramUploadException(outputType: DownloadOutputType) :
    RuntimeException("Telegram upload is not supported for outputType=$outputType")

class TelegramUploadException(message: String) : RuntimeException(message)
