package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.analytics.DownloadAnalytics
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.identity.UnsupportedUrlException
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import com.nkudrin713.kradnik.download.platform.UnsupportedPlatformException
import com.nkudrin713.kradnik.download.service.CreateDownloadJobCommand
import com.nkudrin713.kradnik.download.service.CreateDownloadJobResult
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.video.TelegramVideoPolicy
import com.nkudrin713.kradnik.settings.DownloadSettingsService
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.handler.TelegramUpdateContext
import com.nkudrin713.kradnik.telegram.handler.command.TelegramCommandHandler
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Component
@Order(20)
class VideoUrlHandler(
    private val downloadJobService: DownloadJobService,
    private val downloadSettingsService: DownloadSettingsService,
    private val platformResolver: PlatformResolver,
    private val telegramSender: TelegramSender,
    private val downloadAnalytics: DownloadAnalytics,
) : TelegramCommandHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun supports(context: TelegramUpdateContext): Boolean {
        return context.text.startsWith("http://") ||
                context.text.startsWith("https://")
    }

    override fun handle(context: TelegramUpdateContext) {
        val message = requireNotNull(context.message)
        val outputType = downloadSettingsService.getOutputType(context.chatId)
        val resolvedDownload = try {
            platformResolver.resolve(context.text, outputType)
        } catch (error: UnsupportedPlatformException) {
            telegramSender.sendMessage(context.chatId, error.message ?: "Платформа не поддерживается")
            return
        } catch (error: UnsupportedUrlException) {
            telegramSender.sendMessage(context.chatId, error.message ?: "Ссылка не поддерживается")
            return
        }
        val request = resolvedDownload.request
        val identity = resolvedDownload.identity
        val statusMessageId = telegramSender.sendStatus(
            context.chatId,
            TelegramDownloadStatus.QUEUED,
        )

        val command = CreateDownloadJobCommand(
            telegramUserId = message.from().id(),
            telegramChatId = context.chatId,
            telegramUpdateId = context.update.updateId(),
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
            deleteStatusBestEffort(context.chatId, statusMessageId)
            throw error
        }

        when (result) {
            is CreateDownloadJobResult.Created -> downloadAnalytics.recordDownloadRequested(command, result.job)
            is CreateDownloadJobResult.Existing -> deleteStatusBestEffort(context.chatId, statusMessageId)
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
