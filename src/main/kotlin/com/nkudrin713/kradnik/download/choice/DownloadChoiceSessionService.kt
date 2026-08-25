package com.nkudrin713.kradnik.download.choice

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Stores expiring choice menus and serializes selection with a database row lock. */
@Service
class DownloadChoiceSessionService(
    private val repository: DownloadChoiceSessionRepository,
    @Value("\${download.choice-session-ttl:30m}")
    private val sessionTtl: Duration = Duration.ofMinutes(30),
) {
    init {
        require(sessionTtl.isPositive()) { "download.choice-session-ttl must be positive" }
    }

    @Transactional
    fun create(command: CreateDownloadChoiceSessionCommand): DownloadChoiceSession {
        val now = Instant.now()
        repository.deleteExpired(now)
        return repository.save(
            DownloadChoiceSession(
                telegramUserId = command.telegramUserId,
                telegramChatId = command.telegramChatId,
                telegramUpdateId = command.telegramUpdateId,
                telegramRequestMessageId = command.telegramRequestMessageId,
                telegramMenuMessageId = command.telegramMenuMessageId,
                options = command.plan.options,
                expiresAt = now.plus(sessionTtl),
            ),
        )
    }

    /**
     * Marks a valid option as selected before job creation.
     * Call [release] if downstream job creation fails.
     */
    @Transactional
    fun select(command: SelectDownloadChoiceCommand): DownloadChoiceSelection {
        val session = repository.findForUpdate(command.token)
            ?: return DownloadChoiceSelection.Expired
        val now = Instant.now()

        if (session.expiresAt <= now) {
            return DownloadChoiceSelection.Expired
        }
        if (session.telegramUserId != command.telegramUserId || session.telegramChatId != command.telegramChatId) {
            return DownloadChoiceSelection.NotOwner
        }
        if (session.telegramMenuMessageId != command.telegramMenuMessageId) {
            return DownloadChoiceSelection.Invalid
        }
        if (session.selectedAt != null) {
            return DownloadChoiceSelection.AlreadySelected
        }

        val option = session.options.firstOrNull { it.key == command.optionKey }
            ?: return DownloadChoiceSelection.Invalid
        if (!option.available) {
            return DownloadChoiceSelection.Unavailable(
                reason = option.unavailableReason ?: "Вариант недоступен",
            )
        }

        session.selectedAt = now
        return DownloadChoiceSelection.Ready(
            session = session,
            option = option,
        )
    }

    /** Makes a previously selected option available again after a failed enqueue. */
    @Transactional
    fun release(token: UUID) {
        repository.findForUpdate(token)?.selectedAt = null
    }
}

data class CreateDownloadChoiceSessionCommand(
    val telegramUserId: Long,
    val telegramChatId: Long,
    val telegramUpdateId: Int,
    val telegramRequestMessageId: Int,
    val telegramMenuMessageId: Int,
    val plan: DownloadChoicePlan,
)

data class SelectDownloadChoiceCommand(
    val token: UUID,
    val optionKey: String,
    val telegramUserId: Long,
    val telegramChatId: Long,
    val telegramMenuMessageId: Int,
)

sealed interface DownloadChoiceSelection {
    data class Ready(
        val session: DownloadChoiceSession,
        val option: DownloadChoiceOptionSnapshot,
    ) : DownloadChoiceSelection

    data class Unavailable(val reason: String) : DownloadChoiceSelection

    data object Expired : DownloadChoiceSelection

    data object NotOwner : DownloadChoiceSelection

    data object AlreadySelected : DownloadChoiceSelection

    data object Invalid : DownloadChoiceSelection
}
