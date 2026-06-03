package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.download.PlatformResolver
import com.nkudrin713.kradnik.download.service.CreateDownloadJobCommand
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.settings.DownloadSettingsService
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

        downloadJobService.createJob(
            CreateDownloadJobCommand(
                telegramUserId = message.from().id(),
                telegramChatId = context.chatId,
                originalUrl = request.originalUrl,
                normalizedUrl = request.normalizedUrl,
                outputType = request.outputType,
                downloadPreset = request.presetName,
                selectedFormat = request.formatSelector,
            )
        )

        telegramSender.sendMessage(context.chatId, "Принял, поставил в очередь")
    }
}
