package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.download.identity.UrlIdentityResolver
import com.nkudrin713.kradnik.download.identity.UnsupportedUrlException
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import com.nkudrin713.kradnik.download.service.CreateDownloadJobCommand
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.settings.DownloadSettingsService
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.handler.TelegramUpdateContext
import com.nkudrin713.kradnik.telegram.handler.command.TelegramCommandHandler
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Component
@Order(20)
class VideoUrlHandler(
    private val downloadJobService: DownloadJobService,
    private val downloadSettingsService: DownloadSettingsService,
    private val platformResolver: PlatformResolver,
    private val urlIdentityResolver: UrlIdentityResolver,
    private val telegramSender: TelegramSender,
) : TelegramCommandHandler {

    override fun supports(context: TelegramUpdateContext): Boolean {
        return context.text.startsWith("http://") ||
                context.text.startsWith("https://")
    }

    override fun handle(context: TelegramUpdateContext) {
        val message = requireNotNull(context.message)
        val outputType = downloadSettingsService.getOutputType(context.chatId)
        val handler = platformResolver.resolve(context.text)
        val request = handler.buildRequest(context.text, outputType)
        val identity = try {
            urlIdentityResolver.resolve(
                url = context.text,
                outputType = outputType,
                presetName = request.presetName,
            )
        } catch (error: UnsupportedUrlException) {
            telegramSender.sendMessage(context.chatId, error.message ?: "Ссылка не поддерживается")
            return
        }
        val statusMessageId = telegramSender.sendStatus(
            context.chatId,
            TelegramDownloadStatus.QUEUED,
        )

        downloadJobService.createJob(
            CreateDownloadJobCommand(
                telegramUserId = message.from().id(),
                telegramChatId = context.chatId,
                originalUrl = identity.originalUrl,
                normalizedUrl = identity.normalizedUrl,
                cacheKey = identity.cacheKey,
                outputType = request.outputType,
                downloadPreset = request.presetName,
                selectedFormat = request.formatSelector,
                downloadExtraArgs = request.extraArgs,
                telegramStatusMessageId = statusMessageId,
            )
        )
    }
}
