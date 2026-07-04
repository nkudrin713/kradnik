package com.nkudrin713.kradnik.analytics

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "analytics_events")
class AnalyticsEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false)
    var environment: String = "",

    @Column(name = "event_type", nullable = false)
    var eventType: String = "",

    @Column(name = "job_id")
    var jobId: Long? = null,

    @Column(name = "telegram_user_id")
    var telegramUserId: Long? = null,

    @Column(name = "telegram_chat_id")
    var telegramChatId: Long? = null,

    @Column
    var platform: String? = null,

    @Column(name = "output_type")
    var outputType: String? = null,

    @Column(name = "cache_key")
    var cacheKey: String? = null,

    @Column(name = "source_duration_seconds")
    var sourceDurationSeconds: Int? = null,

    @Column(name = "downloaded_file_size")
    var downloadedFileSize: Long? = null,

    @Column(name = "telegram_file_size")
    var telegramFileSize: Long? = null,

    @Column
    var success: Boolean? = null,

    @Column(name = "error_code")
    var errorCode: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    var properties: Map<String, Any?> = emptyMap(),

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    var createdAt: Instant? = null,
)
