package com.nkudrin713.kradnik.settings

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

@Entity
@Table(name = "download_settings")
class DownloadSetting(
	@Id
	@Column(name = "chat_id")
	var chatId: Long = 0,

	@Convert(converter = DownloadModeConverter::class)
	@Column(nullable = false)
	var mode: DownloadMode = DownloadMode.VIDEO,

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant? = null,
)
