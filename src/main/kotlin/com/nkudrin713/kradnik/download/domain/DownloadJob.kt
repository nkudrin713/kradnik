package com.nkudrin713.kradnik.download.domain

import com.nkudrin713.kradnik.download.repository.DownloadOutputTypeConverter
import com.nkudrin713.kradnik.download.repository.DownloadJobStatusConverter
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

	@Column(name = "original_url", nullable = false)
	var originalUrl: String = "",

	@Column(name = "normalized_url", nullable = false)
	var normalizedUrl: String = "",

	@Column(name = "cache_key", nullable = false)
	var cacheKey: String = "",

	@Convert(converter = DownloadOutputTypeConverter::class)
	@Column(name = "output_type", nullable = false)
	var outputType: OutputType = OutputType.VIDEO,

	@Convert(converter = DownloadJobStatusConverter::class)
	@Column(nullable = false)
	var status: DownloadJobStatus = DownloadJobStatus.QUEUED,

	@Column(nullable = false)
	var attempts: Int = 0,

	@Column(name = "source_title")
	var sourceTitle: String? = null,

	@Column(name = "source_extractor")
	var sourceExtractor: String? = null,

	@Column(name = "source_duration_seconds")
	var sourceDurationSeconds: Int? = null,

	@Column(name = "source_audio_title")
	var sourceAudioTitle: String? = null,

	@Column(name = "source_audio_performer")
	var sourceAudioPerformer: String? = null,

	@Column(name = "download_preset")
	var downloadPreset: String? = null,

	@Column(name = "selected_format")
	var selectedFormat: String? = null,

	@Column(name = "downloaded_file_size")
	var downloadedFileSize: Long? = null,

	@Column(name = "telegram_file_id")
	var telegramFileId: String? = null,

	@Column(name = "telegram_file_size")
	var telegramFileSize: Long? = null,

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

	@Column(name = "processing_started_at")
	var processingStartedAt: Instant? = null,

	@Column(name = "uploading_started_at")
	var uploadingStartedAt: Instant? = null,

	@Column(name = "downloaded_at")
	var downloadedAt: Instant? = null,

	@Column(name = "completed_at")
	var completedAt: Instant? = null,
)
