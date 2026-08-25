package com.nkudrin713.kradnik.download.domain

import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.repository.DownloadPlatformConverter
import com.nkudrin713.kradnik.download.repository.DownloadJobStatusConverter
import com.nkudrin713.kradnik.download.repository.DownloadOutputTypeConverter
import com.nkudrin713.kradnik.download.repository.StringListJsonConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.util.UUID

/** A persisted request snapshot whose mutable fields track queue and delivery progress. */
@Entity
@Table(name = "download_jobs")
class DownloadJob(
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = null,

	@Column(name = "telegram_user_id", nullable = false)
	var telegramUserId: Long = 0,

	@Column(name = "telegram_chat_id", nullable = false)
	var telegramChatId: Long = 0,

	@Column(name = "telegram_update_id")
	var telegramUpdateId: Int? = null,

	@Column(name = "telegram_request_message_id")
	var telegramRequestMessageId: Int? = null,

	@Column(name = "original_url", nullable = false)
	var originalUrl: String = "",

	@Column(name = "normalized_url", nullable = false)
	var normalizedUrl: String = "",

	@Column(name = "cache_key", nullable = false)
	var cacheKey: String = "",

	@Convert(converter = DownloadOutputTypeConverter::class)
	@Column(name = "output_type", nullable = false)
	var outputType: OutputType = OutputType.VIDEO,

	@Convert(converter = DownloadPlatformConverter::class)
	@Column(name = "platform", nullable = false)
	var platform: DownloadPlatform = DownloadPlatform.YOUTUBE,

	@Convert(converter = DownloadJobStatusConverter::class)
	@Column(nullable = false)
	var status: DownloadJobStatus = DownloadJobStatus.QUEUED,

	@Column(nullable = false)
	var attempts: Int = 0,

	@Column(name = "source_duration_seconds")
	var sourceDurationSeconds: Int? = null,

	@Column(name = "source_audio_title")
	var sourceAudioTitle: String? = null,

	@Column(name = "source_audio_performer")
	var sourceAudioPerformer: String? = null,

	@Column(name = "download_preset", nullable = false)
	var downloadPreset: String = "",

	@Column(name = "selected_format", nullable = false)
	var selectedFormat: String = "",

	@Convert(converter = StringListJsonConverter::class)
	@Column(name = "download_extra_args", nullable = false)
	var downloadExtraArgs: List<String> = emptyList(),

	@Column(name = "telegram_file_id")
	var telegramFileId: String? = null,

	@Column(name = "telegram_status_message_id")
	var telegramStatusMessageId: Int? = null,

	@Column(name = "error_message")
	var errorMessage: String? = null,

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant? = null,

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant? = null,

	@Column(name = "completed_at")
	var completedAt: Instant? = null,

	@Column(name = "next_attempt_at", nullable = false)
	var nextAttemptAt: Instant = Instant.EPOCH,

	@Column(name = "lease_token")
	var leaseToken: UUID? = null,

	@Column(name = "lease_expires_at")
	var leaseExpiresAt: Instant? = null,
) {
	fun requiredId(): Long = requireNotNull(id)
}
