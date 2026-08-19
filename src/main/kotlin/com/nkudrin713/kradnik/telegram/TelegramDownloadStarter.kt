package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.analytics.DownloadAnalytics
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.identity.UnsupportedUrlException
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import com.nkudrin713.kradnik.download.platform.ResolvedDownload
import com.nkudrin713.kradnik.download.platform.UnsupportedPlatformException
import com.nkudrin713.kradnik.download.service.CreateDownloadJobCommand
import com.nkudrin713.kradnik.download.service.CreateDownloadJobResult
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.video.TelegramVideoPolicy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class TelegramDownloadStarter(
    private val downloadJobService: DownloadJobService,
    private val platformResolver: PlatformResolver,
    private val telegramSender: TelegramSender,
    private val downloadAnalytics: DownloadAnalytics,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun validate(
        chatId: Long,
        url: String,
    ): Boolean {
        return resolveOrNotify(
            chatId = chatId,
            url = url,
            outputType = OutputType.VIDEO,
        ) != null
    }

    fun start(
        telegramUserId: Long,
        telegramChatId: Long,
        telegramUpdateId: Int,
        telegramRequestMessageId: Int,
        url: String,
        outputType: OutputType,
    ): Boolean {
        val resolvedDownload = resolveOrNotify(
            chatId = telegramChatId,
            url = url,
            outputType = outputType,
        ) ?: return false
        val request = resolvedDownload.request
        val identity = resolvedDownload.identity
        val statusMessageId = telegramSender.sendStatus(
            telegramChatId,
            TelegramDownloadStatus.QUEUED,
        )
        val command = CreateDownloadJobCommand(
            telegramUserId = telegramUserId,
            telegramChatId = telegramChatId,
            telegramUpdateId = telegramUpdateId,
            telegramRequestMessageId = telegramRequestMessageId,
            originalUrl = identity.originalUrl,
            normalizedUrl = identity.normalizedUrl,
            cacheKey = when (request.outputType) {
                OutputType.VIDEO -> TelegramVideoPolicy.versionCacheKey(identity.cacheKey)
                OutputType.AUDIO -> identity.cacheKey
            },
            outputType = request.outputType,
            downloadPreset = request.presetName,
            selectedFormat = request.formatSelector,
            downloadExtraArgs = request.extraArgs,
            telegramStatusMessageId = statusMessageId,
        )
        val result = try {
            downloadJobService.createJob(command)
        } catch (error: Exception) {
            deleteStatusBestEffort(telegramChatId, statusMessageId)
            throw error
        }

        when (result) {
            is CreateDownloadJobResult.Created -> downloadAnalytics.recordDownloadRequested(command, result.job)
            is CreateDownloadJobResult.Existing -> deleteStatusBestEffort(telegramChatId, statusMessageId)
        }
        return true
    }

    private fun resolveOrNotify(
        chatId: Long,
        url: String,
        outputType: OutputType,
    ): ResolvedDownload? {
        return try {
            platformResolver.resolve(url, outputType)
        } catch (error: UnsupportedPlatformException) {
            telegramSender.sendMessage(chatId, error.message ?: "Платформа не поддерживается")
            null
        } catch (error: UnsupportedUrlException) {
            telegramSender.sendMessage(chatId, error.message ?: "Ссылка не поддерживается")
            null
        }
    }

    private fun deleteStatusBestEffort(chatId: Long, messageId: Int) {
        runCatching {
            telegramSender.deleteMessage(chatId, messageId)
        }.onFailure {
            logger.warn(
                "Duplicate or failed job status message deletion failed: chatId={}, messageId={}",
                chatId,
                messageId,
                it,
            )
        }
    }
}
