package com.nkudrin713.kradnik.download.domain

import com.nkudrin713.kradnik.download.repository.DownloadOutputTypeConverter
import com.nkudrin713.kradnik.download.repository.DownloadTaskStatusConverter
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
@Table(name = "download_tasks")
class DownloadTask(
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

	@Convert(converter = DownloadOutputTypeConverter::class)
	@Column(name = "output_type", nullable = false)
	var outputType: DownloadOutputType = DownloadOutputType.VIDEO,

	@Convert(converter = DownloadTaskStatusConverter::class)
	@Column(nullable = false)
	var status: DownloadTaskStatus = DownloadTaskStatus.QUEUED,

	@Column(name = "source_title")
	var sourceTitle: String? = null,

	@Column(name = "source_extractor")
	var sourceExtractor: String? = null,

	@Column(name = "source_duration_seconds")
	var sourceDurationSeconds: Int? = null,

	@Column(name = "telegram_file_id")
	var telegramFileId: String? = null,

	@Column(name = "telegram_file_size")
	var telegramFileSize: Long? = null,

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
)
