package com.nkudrin713.kradnik.download.choice

import com.nkudrin713.kradnik.telegram.REPEAT_LAST_OPTION_KEY
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessages
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Stores [DownloadChoiceSession] snapshots and serializes callback selection with a database row lock.
 * Ownership, menu identity, option availability, and single-selection rules are checked before
 * [DownloadChoiceHandler][com.nkudrin713.kradnik.telegram.handler.DownloadChoiceHandler] starts a job.
 */
@Service
class DownloadChoiceSessionService(
    private val repository: DownloadChoiceSessionRepository,
    private val messages: TelegramMessages,
    private val clock: Clock,
    @Value($$"${download.choice-session-ttl:30m}")
    private val consumedSessionTtl: Duration = Duration.ofMinutes(30),
    @Value($$"${download.choice-session-max-age:30d}")
    private val unselectedSessionMaxAge: Duration = Duration.ofDays(30),
) {
    init {
        require(consumedSessionTtl.isPositive) { "download.choice-session-ttl must be positive" }
        require(unselectedSessionMaxAge.isPositive) { "download.choice-session-max-age must be positive" }
    }

    /**
     * Persists the offered options, owner, language, and Telegram message identifiers for later callbacks.
     * Unselected menus expire by creation age; their initial cleanup deadline does not consume the session.
     */
    @Transactional
    fun create(command: CreateDownloadChoiceSessionCommand): DownloadChoiceSession {
        val now = clock.instant()
        return repository.save(
            DownloadChoiceSession(
                telegramUserId = command.telegramUserId,
                telegramChatId = command.telegramChatId,
                telegramUpdateId = command.telegramUpdateId,
                telegramRequestMessageId = command.telegramRequestMessageId,
                telegramMenuMessageId = command.telegramMenuMessageId,
                telegramInlineMessageId = command.telegramInlineMessageId,
                language = command.language,
                options = command.plan.options,
                cleanupAfter = now.plus(consumedSessionTtl),
            ),
        )
    }

    @Transactional
    fun createRetry(command: CreateDownloadRetryCommand): DownloadChoiceSession = repository.save(
        DownloadChoiceSession(
            telegramUserId = command.telegramUserId,
            telegramChatId = command.telegramChatId,
            telegramUpdateId = command.telegramUpdateId,
            telegramRequestMessageId = command.telegramRequestMessageId,
            telegramMenuMessageId = command.telegramMenuMessageId,
            telegramInlineMessageId = command.telegramInlineMessageId,
            language = command.language,
            retryUrl = command.url,
            cleanupAfter = clock.instant().plus(consumedSessionTtl),
        ),
    )

    /**
     * Locks the session and marks a valid, available option as selected before job creation.
     * Validates the owner, originating menu, session age, and option availability under the same row lock.
     * Rejected selections return a [DownloadChoiceSelection] outcome without consuming the menu.
     * The consumed-session cleanup deadline starts at selection; call [release] if downstream job creation fails.
     */
    @Transactional
    fun select(command: SelectDownloadChoiceCommand): DownloadChoiceSelection {
        val session = repository.findForUpdate(command.token)
            ?: return DownloadChoiceSelection.Invalid
        val now = clock.instant()

        when (session.rejectionFor(command.telegramUserId, command.telegramChatId, command.telegramMenuMessageId, command.telegramInlineMessageId, now)) {
            SessionRejection.NOT_OWNER -> return DownloadChoiceSelection.NotOwner
            SessionRejection.INVALID -> return DownloadChoiceSelection.Invalid
            SessionRejection.ALREADY_SELECTED -> return DownloadChoiceSelection.AlreadySelected
            null -> Unit
        }

        if (command.optionKey == REPEAT_LAST_OPTION_KEY && session.retryUrl != null) {
            session.consume(now)
            return DownloadChoiceSelection.Retry(session, requireNotNull(session.retryUrl))
        }

        val option = session.options.firstOrNull { it.key == command.optionKey }
            ?: return DownloadChoiceSelection.Invalid
        if (!option.available) {
            return DownloadChoiceSelection.Unavailable(
                reason = option.unavailableReason
                    ?: messages.text(session.language, TelegramMessage.CHOICE_OPTION_UNAVAILABLE),
            )
        }

        session.consume(now)
        return DownloadChoiceSelection.Ready(
            session = session,
            option = option,
        )
    }

    /**
     * Consumes an unselected menu after validating its owner, originating message, and age under a row lock.
     * Uses the same consumed-session lifetime as [select]; this does not cancel an already enqueued job.
     */
    @Transactional
    fun cancel(command: CancelDownloadChoiceCommand): DownloadChoiceCancellation {
        val session = repository.findForUpdate(command.token)
            ?: return DownloadChoiceCancellation.Invalid
        val now = clock.instant()
        when (session.rejectionFor(command.telegramUserId, command.telegramChatId, command.telegramMenuMessageId, command.telegramInlineMessageId, now)) {
            SessionRejection.NOT_OWNER -> return DownloadChoiceCancellation.NotOwner
            SessionRejection.INVALID -> return DownloadChoiceCancellation.Invalid
            SessionRejection.ALREADY_SELECTED -> return DownloadChoiceCancellation.AlreadySelected
            null -> Unit
        }
        session.consume(now)
        return DownloadChoiceCancellation.Cancelled(session.language)
    }

    private fun DownloadChoiceSession.rejectionFor(userId: Long, chatId: Long?, messageId: Int?, inlineMessageId: String?, now: Instant): SessionRejection? {
        if (telegramUserId != userId) return SessionRejection.NOT_OWNER
        val sameMessage = if (inlineMessageId != null) {
            telegramInlineMessageId == inlineMessageId
        } else {
            telegramInlineMessageId == null && telegramChatId == chatId && telegramMenuMessageId == messageId
        }
        if (!sameMessage) return SessionRejection.INVALID
        if (selectedAt != null) return SessionRejection.ALREADY_SELECTED
        if (createdAt?.isAfter(now.minus(unselectedSessionMaxAge)) != true) return SessionRejection.INVALID
        return null
    }

    private fun DownloadChoiceSession.consume(now: Instant) {
        selectedAt = now
        cleanupAfter = now.plus(consumedSessionTtl)
    }

    private enum class SessionRejection { NOT_OWNER, INVALID, ALREADY_SELECTED }

    /**
     * Makes a selected option available after
     * [TelegramDownloadStarter][com.nkudrin713.kradnik.telegram.TelegramDownloadStarter] fails to enqueue it.
     * Clearing [DownloadChoiceSession.selectedAt] returns the row to the absolute unselected-session lifetime.
     * A missing session is ignored; releasing an existing session does not reset its creation time.
     */
    @Transactional
    fun release(token: UUID) {
        repository.findForUpdate(token)?.selectedAt = null
    }

    /**
     * Deletes selected or cancelled sessions past their cleanup deadline and unused menus past their maximum age.
     * Removes database rows only; Telegram menu messages are left unchanged.
     */
    @Scheduled(fixedDelayString = $$"${download.choice-session-cleanup-delay-ms:600000}")
    @Transactional
    fun deleteExpiredSessions() {
        val now = clock.instant()
        repository.deleteConsumed(now)
        repository.deleteExpiredUnselected(now.minus(unselectedSessionMaxAge))
    }
}

data class CreateDownloadChoiceSessionCommand(
    val telegramUserId: Long,
    val telegramChatId: Long,
    val telegramUpdateId: Int,
    val telegramRequestMessageId: Int,
    val telegramMenuMessageId: Int?,
    val telegramInlineMessageId: String? = null,
    val language: BotLanguage = BotLanguage.EN,
    val plan: DownloadChoicePlan,
)

data class CreateDownloadRetryCommand(
    val telegramUserId: Long,
    val telegramChatId: Long,
    val telegramUpdateId: Int,
    val telegramRequestMessageId: Int,
    val telegramMenuMessageId: Int?,
    val telegramInlineMessageId: String?,
    val language: BotLanguage,
    val url: String,
)

data class SelectDownloadChoiceCommand(
    val token: UUID,
    val optionKey: String,
    val telegramUserId: Long,
    val telegramChatId: Long?,
    val telegramMenuMessageId: Int?,
    val telegramInlineMessageId: String? = null,
)

data class CancelDownloadChoiceCommand(
    val token: UUID,
    val telegramUserId: Long,
    val telegramChatId: Long?,
    val telegramMenuMessageId: Int?,
    val telegramInlineMessageId: String? = null,
)

sealed interface DownloadChoiceCancellation {
    data class Cancelled(val language: BotLanguage) : DownloadChoiceCancellation
    data object NotOwner : DownloadChoiceCancellation
    data object AlreadySelected : DownloadChoiceCancellation
    data object Invalid : DownloadChoiceCancellation
}

sealed interface DownloadChoiceSelection {
    data class Retry(val session: DownloadChoiceSession, val url: String) : DownloadChoiceSelection

    data class Ready(
        val session: DownloadChoiceSession,
        val option: DownloadChoiceOptionSnapshot,
    ) : DownloadChoiceSelection

    data class Unavailable(val reason: String) : DownloadChoiceSelection

    data object NotOwner : DownloadChoiceSelection

    data object AlreadySelected : DownloadChoiceSelection

    data object Invalid : DownloadChoiceSelection
}
