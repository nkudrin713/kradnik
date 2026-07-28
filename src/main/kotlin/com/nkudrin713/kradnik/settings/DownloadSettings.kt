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
class DownloadSettings(
	@Id
	@Column(name = "chat_id")
	val chatId: Long = 0,

	@Convert(converter = DownloadModeConverter::class)
	@Column(nullable = false)
	var mode: DownloadMode = DownloadMode.ASK,

	@Column(name = "mode_menu_message_id")
	var modeMenuMessageId: Int? = null,

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	val updatedAt: Instant? = null,
)
