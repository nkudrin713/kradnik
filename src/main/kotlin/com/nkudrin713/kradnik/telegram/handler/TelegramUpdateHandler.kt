package com.nkudrin713.kradnik.telegram.handler

import com.nkudrin713.kradnik.telegram.DownloadChoiceCoordinator
import com.nkudrin713.kradnik.telegram.PrepareDownloadChoiceCommand
import com.nkudrin713.kradnik.telegram.TelegramDonationSender
import com.nkudrin713.kradnik.telegram.TelegramLanguageSelector
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessages
import com.nkudrin713.kradnik.telegram.localization.TelegramUserPreferenceService
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Update
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Routes updates received by [TelegramPollingService][com.nkudrin713.kradnik.telegram.TelegramPollingService] to
 * command responses, [DownloadChoiceCoordinator] for direct and guest links, or
 * [DownloadChoiceHandler] for callbacks. Pinned-service messages are removed, and unsupported text receives the short
 * link prompt without entering download preparation.
 */
@Service
class TelegramUpdateHandler(
    private val downloadChoiceCoordinator: DownloadChoiceCoordinator,
    private val downloadChoiceHandler: DownloadChoiceHandler,
    private val telegramSender: TelegramSender,
    private val telegramDonationSender: TelegramDonationSender,
    private val languageSelector: TelegramLanguageSelector,
    private val preferenceService: TelegramUserPreferenceService,
    private val messages: TelegramMessages,
    @Value("\${telegram.donation.url:}")
    private val donationUrl: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun handle(update: Update) {
        val guestMessage = update.guestMessage()
        val message = update.message()
        when {
            guestMessage?.text() != null -> handleGuestMessage(update, guestMessage)
            message?.pinnedMessage() != null -> deletePinnedServiceMessageBestEffort(
                chatId = message.chat().id(),
                messageId = message.messageId(),
            )

            message?.text() != null -> handleMessage(update, message)
            update.callbackQuery()?.data() != null -> {
                val callbackQuery = update.callbackQuery()
                if (!languageSelector.handle(callbackQuery)) {
                    downloadChoiceHandler.handle(callbackQuery)
                }
            }
        }
    }

    private fun handleGuestMessage(update: Update, message: Message) {
        val guestQueryId = message.guestQueryId() ?: return
        val language = resolveLanguage(message)
        val url = guestUrl(message.text())
        if (url == null) {
            telegramSender.answerGuestMessage(
                guestQueryId = guestQueryId,
                text = messages.text(language, TelegramMessage.LINK_REQUIRED),
                language = language,
            )
            return
        }

        downloadChoiceCoordinator.prepare(
            PrepareDownloadChoiceCommand(
                telegramUserId = message.from().id(),
                telegramChatId = message.chat().id(),
                telegramUpdateId = update.updateId(),
                telegramRequestMessageId = message.messageId(),
                url = url,
                language = language,
                guestQueryId = guestQueryId,
            )
        )
    }

    private fun guestUrl(text: String): String? {
        val parts = text.trim().split(Regex("\\s+"))
        if (parts.size != 2 || !parts[0].startsWith("@")) {
            return null
        }
        return parts[1].takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun handleMessage(update: Update, message: Message) {
        val text = message.text().trim()
        val chatId = message.chat().id()
        val selectedLanguage = preferenceService.selectedLanguage(message.from().id())

        if (text == "/start" && selectedLanguage == null) {
            languageSelector.show(chatId)
            return
        }
        if (text == "/language") {
            languageSelector.show(chatId)
            return
        }

        val language = selectedLanguage ?: BotLanguage.EN

        when {
            text == "/start" -> sendMessage(chatId, language, TelegramMessage.START_PROMPT)
            text == "/help" -> sendMessage(chatId, language, TelegramMessage.HELP)
            text == "/legal" -> sendMessage(chatId, language, TelegramMessage.LEGAL)
            text == "/donate" -> sendDonation(chatId, language)
            text.startsWith("http://") || text.startsWith("https://") ->
                prepareDownload(update, message, text, language)
            else -> sendMessage(chatId, language, TelegramMessage.LINK_REQUIRED)
        }
    }

    private fun sendDonation(chatId: Long, language: BotLanguage) {
        if (donationUrl.isBlank()) {
            sendMessage(chatId, language, TelegramMessage.DONATION_UNAVAILABLE)
        } else {
            telegramDonationSender.sendMessage(chatId, donationUrl, language)
        }
    }

    private fun prepareDownload(
        update: Update,
        message: Message,
        url: String,
        language: BotLanguage,
    ) {
        downloadChoiceCoordinator.prepare(
            PrepareDownloadChoiceCommand(
                telegramUserId = message.from().id(),
                telegramChatId = message.chat().id(),
                telegramUpdateId = update.updateId(),
                telegramRequestMessageId = message.messageId(),
                url = url,
                language = language,
            )
        )
    }

    private fun resolveLanguage(message: Message): BotLanguage {
        return preferenceService.resolveLanguage(message.from().id())
    }

    private fun sendMessage(chatId: Long, language: BotLanguage, message: TelegramMessage) {
        telegramSender.sendMessage(chatId, messages.text(language, message))
    }

    private fun deletePinnedServiceMessageBestEffort(chatId: Long, messageId: Int) {
        runCatching {
            telegramSender.deleteMessage(chatId, messageId)
        }.onFailure {
            logger.warn(
                "Pinned service message deletion failed: chatId={}, messageId={}",
                chatId,
                messageId,
                it,
            )
        }
    }
}
