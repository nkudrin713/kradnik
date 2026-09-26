package com.nkudrin713.kradnik.telegram.handler

import com.nkudrin713.kradnik.download.choice.CancelDownloadChoiceCommand
import com.nkudrin713.kradnik.download.choice.DownloadChoiceCancellation
import com.nkudrin713.kradnik.download.choice.DownloadChoiceSelection
import com.nkudrin713.kradnik.download.choice.DownloadChoiceSessionService
import com.nkudrin713.kradnik.download.choice.SelectDownloadChoiceCommand
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.telegram.CANCEL_OPTION_KEY
import com.nkudrin713.kradnik.telegram.DownloadChoiceCallback
import com.nkudrin713.kradnik.telegram.TelegramDownloadStarter
import com.nkudrin713.kradnik.telegram.TelegramDownloadStatus
import com.nkudrin713.kradnik.telegram.TelegramMessageAddress
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessages
import com.nkudrin713.kradnik.telegram.localization.TelegramUserPreferenceService
import com.pengrad.telegrambot.model.CallbackQuery
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Parses a Telegram callback and asks [DownloadChoiceSessionService] to validate ownership and atomically select it.
 * A ready choice is passed to [TelegramDownloadStarter]; enqueue failure releases the session, while success answers
 * the callback and removes the direct-chat menu through [TelegramSender].
 */
@Component
class DownloadChoiceHandler(
    private val sessionService: DownloadChoiceSessionService,
    private val telegramDownloadStarter: TelegramDownloadStarter,
    private val telegramSender: TelegramSender,
    private val preferenceService: TelegramUserPreferenceService,
    private val messages: TelegramMessages,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun handle(callbackQuery: CallbackQuery) {
        val callback = DownloadChoiceCallback.parse(callbackQuery.data().trim()) ?: return
        val fallbackLanguage = preferenceService.resolveLanguage(callbackQuery.from().id())
        val address = callbackQuery.inlineMessageId()
            ?.let(TelegramMessageAddress::Inline)
            ?: callbackQuery.maybeInaccessibleMessage()?.let {
                TelegramMessageAddress.Chat(it.chat().id(), it.messageId())
            }
            ?: return answer(
                callbackQuery.id(),
                fallbackLanguage,
                TelegramMessage.CHOICE_MENU_INVALID,
                showAlert = true,
            )
        if (callback.optionKey == CANCEL_OPTION_KEY) {
            cancelMenu(callbackQuery, callback, address, fallbackLanguage)
            return
        }
        val selection = sessionService.select(
            SelectDownloadChoiceCommand(
                token = callback.sessionToken,
                optionKey = callback.optionKey,
                telegramUserId = callbackQuery.from().id(),
                telegramChatId = (address as? TelegramMessageAddress.Chat)?.chatId,
                telegramMenuMessageId = (address as? TelegramMessageAddress.Chat)?.messageId,
                telegramInlineMessageId = (address as? TelegramMessageAddress.Inline)?.inlineMessageId,
            ),
        )

        when (selection) {
            is DownloadChoiceSelection.Ready -> startDownload(
                address = address,
                callbackQueryId = callbackQuery.id(),
                callback = callback,
                selection = selection,
            )

            is DownloadChoiceSelection.Unavailable -> answer(callbackQuery.id(), selection.reason, showAlert = true)

            DownloadChoiceSelection.NotOwner -> answer(
                callbackQuery.id(),
                fallbackLanguage,
                TelegramMessage.CHOICE_NOT_OWNER,
                showAlert = true,
            )

            DownloadChoiceSelection.AlreadySelected -> answer(
                callbackQuery.id(),
                fallbackLanguage,
                TelegramMessage.CHOICE_ALREADY_SELECTED,
            )

            DownloadChoiceSelection.Invalid -> answer(
                callbackQuery.id(),
                fallbackLanguage,
                TelegramMessage.CHOICE_MENU_INVALID,
                showAlert = true,
            )
        }
    }

    private fun cancelMenu(
        callbackQuery: CallbackQuery,
        callback: DownloadChoiceCallback,
        address: TelegramMessageAddress,
        fallbackLanguage: BotLanguage,
    ) {
        val result = sessionService.cancel(
            CancelDownloadChoiceCommand(
                token = callback.sessionToken,
                telegramUserId = callbackQuery.from().id(),
                telegramChatId = (address as? TelegramMessageAddress.Chat)?.chatId,
                telegramMenuMessageId = (address as? TelegramMessageAddress.Chat)?.messageId,
                telegramInlineMessageId = (address as? TelegramMessageAddress.Inline)?.inlineMessageId,
            ),
        )
        when (result) {
            is DownloadChoiceCancellation.Cancelled -> {
                telegramSender.answerCallback(callbackQuery.id())
                if (address is TelegramMessageAddress.Chat) {
                    deleteMenuBestEffort(address.chatId, address.messageId)
                } else {
                    telegramSender.editStatus(address, TelegramDownloadStatus.CANCELLED, result.language)
                }
            }

            DownloadChoiceCancellation.NotOwner -> answer(
                callbackQuery.id(),
                fallbackLanguage,
                TelegramMessage.CHOICE_NOT_OWNER,
                showAlert = true,
            )

            DownloadChoiceCancellation.AlreadySelected -> answer(
                callbackQuery.id(),
                fallbackLanguage,
                TelegramMessage.CHOICE_ALREADY_SELECTED,
            )

            DownloadChoiceCancellation.Invalid -> answer(
                callbackQuery.id(),
                fallbackLanguage,
                TelegramMessage.CHOICE_MENU_INVALID,
                showAlert = true,
            )
        }
    }

    private fun startDownload(
        address: TelegramMessageAddress,
        callbackQueryId: String,
        callback: DownloadChoiceCallback,
        selection: DownloadChoiceSelection.Ready,
    ) {
        if (address is TelegramMessageAddress.Inline &&
            selection.option.spec.workloadType == DownloadWorkloadType.PLAYLIST_AUDIO
        ) {
            sessionService.release(callback.sessionToken)
            answer(
                callbackQueryId,
                selection.session.language,
                TelegramMessage.ERROR_PLAYLIST_DIRECT_CHAT_ONLY,
                showAlert = true,
            )
            return
        }
        if (address is TelegramMessageAddress.Inline && selection.option.spec.outputType in setOf(OutputType.IMAGES, OutputType.POST)) {
            sessionService.release(callback.sessionToken)
            answer(
                callbackQueryId = callbackQueryId,
                language = selection.session.language,
                message = if (selection.option.key == POST_OPTION_KEY) {
                    TelegramMessage.ERROR_POST_DIRECT_CHAT_ONLY
                } else {
                    TelegramMessage.ERROR_IMAGES_DIRECT_CHAT_ONLY
                },
                showAlert = true,
            )
            return
        }
        if (address is TelegramMessageAddress.Inline && !selection.option.spec.postText.isNullOrBlank()) {
            sessionService.release(callback.sessionToken)
            answer(
                callbackQueryId = callbackQueryId,
                language = selection.session.language,
                message = TelegramMessage.ERROR_POST_DIRECT_CHAT_ONLY,
                showAlert = true,
            )
            return
        }
        try {
            telegramDownloadStarter.start(
                telegramUserId = selection.session.telegramUserId,
                telegramChatId = selection.session.telegramChatId,
                telegramUpdateId = selection.session.telegramUpdateId,
                telegramRequestMessageId = selection.session.telegramRequestMessageId,
                messageAddress = address,
                spec = selection.option.spec,
                language = selection.session.language,
            )
        } catch (error: Exception) {
            sessionService.release(callback.sessionToken)
            throw error
        }

        answer(
            callbackQueryId = callbackQueryId,
            text = messages.text(
                selection.session.language,
                TelegramMessage.CHOICE_SELECTED,
                selection.option.label,
            ),
        )
        if (address is TelegramMessageAddress.Chat) {
            deleteMenuBestEffort(address.chatId, address.messageId)
        }
    }

    private fun answer(callbackQueryId: String, text: String, showAlert: Boolean = false) {
        telegramSender.answerCallback(callbackQueryId, text, showAlert)
    }

    private fun answer(
        callbackQueryId: String,
        language: BotLanguage,
        message: TelegramMessage,
        showAlert: Boolean = false,
    ) {
        answer(callbackQueryId, messages.text(language, message), showAlert)
    }

    private fun deleteMenuBestEffort(chatId: Long, messageId: Int) {
        runCatching {
            telegramSender.deleteMessage(chatId, messageId)
        }.onFailure {
            logger.warn(
                "Download choice menu deletion failed: chatId={}, messageId={}",
                chatId,
                messageId,
                it,
            )
        }
    }

    private companion object {
        private const val POST_OPTION_KEY = "post"
    }
}
