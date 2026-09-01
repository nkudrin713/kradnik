package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.choice.CreateDownloadChoiceSessionCommand
import com.nkudrin713.kradnik.download.choice.DownloadChoicePlanner
import com.nkudrin713.kradnik.download.choice.DownloadChoicePlanningException
import com.nkudrin713.kradnik.download.choice.DownloadChoiceSessionService
import com.nkudrin713.kradnik.download.identity.UnsupportedUrlException
import com.nkudrin713.kradnik.download.platform.UnsupportedPlatformException
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessages
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.Executors

/**
 * Moves catalog extraction off [TelegramPollingService]'s listener thread after publishing an analyzing status.
 * [DownloadChoicePlanner] builds the menu, [DownloadChoiceSessionService] persists the rendered snapshot, and
 * [TelegramSender] replaces the status message with callbacks tied to that session; failures replace it with safe copy.
 */
@Component
class DownloadChoiceCoordinator(
    private val planner: DownloadChoicePlanner,
    private val sessionService: DownloadChoiceSessionService,
    private val telegramSender: TelegramSender,
    private val messages: TelegramMessages,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "download-choice-worker").apply { isDaemon = true }
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdown()
    }

    fun prepare(command: PrepareDownloadChoiceCommand) {
        val messageAddress = command.guestQueryId
            ?.let {
                telegramSender.answerGuestMessage(
                    guestQueryId = it,
                    text = messages.text(command.language, TelegramDownloadStatus.ANALYZING.message),
                    language = command.language,
                )
            }
            ?: TelegramMessageAddress.Chat(
                chatId = command.telegramChatId,
                messageId = telegramSender.sendStatus(
                    chatId = command.telegramChatId,
                    status = TelegramDownloadStatus.ANALYZING,
                    language = command.language,
                    replyToMessageId = command.telegramRequestMessageId,
                ),
            )
        executor.submit {
            runBlocking {
                prepareAsync(command, messageAddress)
            }
        }
    }

    internal suspend fun prepareAsync(
        command: PrepareDownloadChoiceCommand,
        messageAddress: TelegramMessageAddress,
    ) {
        try {
            val plan = planner.plan(command.url, command.language)
            val session = sessionService.create(
                CreateDownloadChoiceSessionCommand(
                    telegramUserId = command.telegramUserId,
                    telegramChatId = command.telegramChatId,
                    telegramUpdateId = command.telegramUpdateId,
                    telegramRequestMessageId = command.telegramRequestMessageId,
                    telegramMenuMessageId = (messageAddress as? TelegramMessageAddress.Chat)?.messageId,
                    telegramInlineMessageId = (messageAddress as? TelegramMessageAddress.Inline)?.inlineMessageId,
                    language = command.language,
                    plan = plan,
                )
            )
            telegramSender.editDownloadChoice(
                address = messageAddress,
                sessionToken = session.token,
                mediaInfo = plan.mediaInfo,
                options = session.options,
                language = session.language,
            )
        } catch (error: Exception) {
            logger.warn(
                "Download choice preparation failed: chatId={}, updateId={}",
                command.telegramChatId,
                command.telegramUpdateId,
                error,
            )
            telegramSender.editMessage(
                address = messageAddress,
                text = error.userMessage(command.language),
            )
        }
    }

    private fun Exception.userMessage(language: BotLanguage): String {
        return when (this) {
            is DownloadChoicePlanningException -> userMessage
            is UnsupportedPlatformException -> messages.text(
                language,
                TelegramMessage.ERROR_UNSUPPORTED_PLATFORM,
                DownloadPlatform.entries.joinToString(", ") { it.displayName },
            )
            is UnsupportedUrlException -> messages.text(language, TelegramMessage.ERROR_UNSUPPORTED_URL)
            else -> messages.text(language, TelegramMessage.ERROR_CHOICE_PREPARATION)
        }
    }
}

data class PrepareDownloadChoiceCommand(
    val telegramUserId: Long,
    val telegramChatId: Long,
    val telegramUpdateId: Int,
    val telegramRequestMessageId: Int,
    val url: String,
    val language: BotLanguage,
    val guestQueryId: String? = null,
)
