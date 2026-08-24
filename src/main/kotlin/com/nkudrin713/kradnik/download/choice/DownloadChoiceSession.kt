package com.nkudrin713.kradnik.download.choice

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "download_choice_sessions")
class DownloadChoiceSession(
    @Id
    var token: UUID = UUID.randomUUID(),

    @Column(name = "telegram_user_id", nullable = false)
    var telegramUserId: Long = 0,

    @Column(name = "telegram_chat_id", nullable = false)
    var telegramChatId: Long = 0,

    @Column(name = "telegram_update_id", nullable = false)
    var telegramUpdateId: Int = 0,

    @Column(name = "telegram_request_message_id", nullable = false)
    var telegramRequestMessageId: Int = 0,

    @Column(name = "telegram_menu_message_id", nullable = false)
    var telegramMenuMessageId: Int = 0,

    @Convert(converter = DownloadChoiceOptionsJsonConverter::class)
    @Column(name = "options_json", nullable = false)
    var options: List<DownloadChoiceOptionSnapshot> = emptyList(),

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.EPOCH,

    @Column(name = "selected_at")
    var selectedAt: Instant? = null,

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    var createdAt: Instant? = null,
)
