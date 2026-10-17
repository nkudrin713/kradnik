package com.nkudrin713.kradnik.download.choice

import com.nkudrin713.kradnik.localization.BotLanguage
import java.time.Instant
import java.util.UUID

/**
 * Persists the exact options rendered by
 * [DownloadChoiceCoordinator][com.nkudrin713.kradnik.telegram.DownloadChoiceCoordinator].
 * [DownloadChoiceSessionService] locks the row to validate callback ownership and allow only one active selection;
 * unselected menus have a finite lifetime, while consumed rows become eligible for cleanup after [cleanupAfter].
 */
class DownloadChoiceSession(
    var token: UUID = UUID.randomUUID(),
    var telegramUserId: Long = 0,
    var telegramChatId: Long = 0,
    var telegramUpdateId: Int = 0,
    var telegramRequestMessageId: Int = 0,
    var telegramMenuMessageId: Int? = null,
    var telegramInlineMessageId: String? = null,
    var language: BotLanguage = BotLanguage.EN,
    var options: List<DownloadChoiceOptionSnapshot> = emptyList(),
    var retryUrl: String? = null,
    var cleanupAfter: Instant = Instant.EPOCH,
    var selectedAt: Instant? = null,
    var createdAt: Instant? = null,
)
