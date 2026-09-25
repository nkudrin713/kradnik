package com.nkudrin713.kradnik.telegram.handler

import com.nkudrin713.kradnik.download.service.DownloadCancellation
import com.nkudrin713.kradnik.download.service.DownloadCancellationService
import com.nkudrin713.kradnik.telegram.DownloadChoiceCoordinator
import com.nkudrin713.kradnik.telegram.DownloadJobAction
import com.nkudrin713.kradnik.telegram.DownloadJobCallback
import com.nkudrin713.kradnik.telegram.PrepareDownloadChoiceCommand
import com.nkudrin713.kradnik.telegram.TelegramMessageAddress
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessages
import com.nkudrin713.kradnik.telegram.localization.TelegramUserPreferenceService
import com.pengrad.telegrambot.model.CallbackQuery
import org.springframework.stereotype.Component

@Component
class DownloadCancellationHandler(
    private val cancellationService: DownloadCancellationService,
    private val choiceCoordinator: DownloadChoiceCoordinator,
    private val telegramSender: TelegramSender,
    private val preferenceService: TelegramUserPreferenceService,
    private val messages: TelegramMessages,
) {
    fun handle(callbackQuery: CallbackQuery, updateId: Int): Boolean {
        val callback = DownloadJobCallback.parse(callbackQuery.data().trim()) ?: return false
        val language = preferenceService.resolveLanguage(callbackQuery.from().id())
        val address = callbackQuery.inlineMessageId()
            ?.let(TelegramMessageAddress::Inline)
            ?: callbackQuery.maybeInaccessibleMessage()?.let {
                TelegramMessageAddress.Chat(it.chat().id(), it.messageId())
            }
            ?: run {
                telegramSender.answerCallback(
                    callbackQuery.id(),
                    messages.text(language, TelegramMessage.CHOICE_MENU_INVALID),
                    true,
                )
                return true
            }

        when (callback.action) {
            DownloadJobAction.CANCEL -> when (
                val result = cancellationService.cancel(callback.jobId, callbackQuery.from().id(), address)
            ) {
                is DownloadCancellation.Cancelled -> {
                    telegramSender.answerCallback(callbackQuery.id())
                    telegramSender.editCancelledJob(address, callback.jobId, result.job.language)
                }

                is DownloadCancellation.AlreadyCancelled -> {
                    telegramSender.answerCallback(callbackQuery.id())
                    telegramSender.editCancelledJob(address, callback.jobId, result.job.language)
                }

                DownloadCancellation.AlreadyFinished -> telegramSender.answerCallback(
                    callbackQuery.id(),
                    messages.text(language, TelegramMessage.DOWNLOAD_ALREADY_FINISHED),
                )

                DownloadCancellation.NotOwner -> telegramSender.answerCallback(
                    callbackQuery.id(),
                    messages.text(language, TelegramMessage.CHOICE_NOT_OWNER),
                    true,
                )

                DownloadCancellation.Invalid -> telegramSender.answerCallback(
                    callbackQuery.id(),
                    messages.text(language, TelegramMessage.CHOICE_MENU_INVALID),
                    true,
                )
            }

            DownloadJobAction.BACK -> {
                val job = cancellationService.cancelledJob(callback.jobId, callbackQuery.from().id(), address)
                if (job == null) {
                    telegramSender.answerCallback(
                        callbackQuery.id(),
                        messages.text(language, TelegramMessage.CHOICE_MENU_INVALID),
                        true,
                    )
                } else {
                    telegramSender.answerCallback(callbackQuery.id())
                    choiceCoordinator.prepareAgain(
                        PrepareDownloadChoiceCommand(
                            telegramUserId = job.telegramUserId,
                            telegramChatId = job.telegramChatId,
                            telegramUpdateId = updateId,
                            telegramRequestMessageId = job.telegramRequestMessageId
                                ?: (address as? TelegramMessageAddress.Chat)?.messageId
                                ?: 0,
                            url = job.originalUrl,
                            language = job.language,
                        ),
                        address,
                    )
                }
            }
        }
        return true
    }
}
