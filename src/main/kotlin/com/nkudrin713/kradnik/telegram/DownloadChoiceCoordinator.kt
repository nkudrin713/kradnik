package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.choice.CreateDownloadChoiceSessionCommand
import com.nkudrin713.kradnik.download.choice.DownloadChoicePlanner
import com.nkudrin713.kradnik.download.choice.DownloadChoicePlanningException
import com.nkudrin713.kradnik.download.choice.DownloadChoiceSessionService
import com.nkudrin713.kradnik.download.identity.UnsupportedUrlException
import com.nkudrin713.kradnik.download.platform.UnsupportedPlatformException
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutorService

@Component
class DownloadChoiceCoordinator(
    private val planner: DownloadChoicePlanner,
    private val sessionService: DownloadChoiceSessionService,
    private val telegramSender: TelegramSender,
    @Qualifier("downloadChoiceExecutor")
    private val executor: ExecutorService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun prepare(command: PrepareDownloadChoiceCommand) {
        val statusMessageId = telegramSender.sendStatus(
            chatId = command.telegramChatId,
            status = TelegramDownloadStatus.ANALYZING,
            replyToMessageId = command.telegramRequestMessageId,
        )
        executor.submit {
            runBlocking {
                prepareAsync(command, statusMessageId)
            }
        }
    }

    internal suspend fun prepareAsync(
        command: PrepareDownloadChoiceCommand,
        statusMessageId: Int,
    ) {
        try {
            val plan = planner.plan(command.url)
            val session = sessionService.create(
                CreateDownloadChoiceSessionCommand(
                    telegramUserId = command.telegramUserId,
                    telegramChatId = command.telegramChatId,
                    telegramUpdateId = command.telegramUpdateId,
                    telegramRequestMessageId = command.telegramRequestMessageId,
                    telegramMenuMessageId = statusMessageId,
                    plan = plan,
                )
            )
            telegramSender.editDownloadChoice(
                chatId = command.telegramChatId,
                messageId = statusMessageId,
                sessionToken = session.token,
                mediaInfo = plan.mediaInfo,
                options = session.options,
            )
        } catch (error: Exception) {
            logger.warn(
                "Download choice preparation failed: chatId={}, updateId={}",
                command.telegramChatId,
                command.telegramUpdateId,
                error,
            )
            telegramSender.editMessage(
                chatId = command.telegramChatId,
                messageId = statusMessageId,
                text = error.userMessage(),
            )
        }
    }

    private fun Exception.userMessage(): String {
        return when (this) {
            is DownloadChoicePlanningException -> userMessage
            is UnsupportedPlatformException -> message ?: "Платформа не поддерживается"
            is UnsupportedUrlException -> message ?: "Ссылка не поддерживается"
            else -> "Не удалось получить варианты загрузки"
        }
    }
}

data class PrepareDownloadChoiceCommand(
    val telegramUserId: Long,
    val telegramChatId: Long,
    val telegramUpdateId: Int,
    val telegramRequestMessageId: Int,
    val url: String,
)
