package com.nkudrin713.kradnik.settings

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.repository.DownloadOutputTypeConverter
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

	@Convert(converter = DownloadOutputTypeConverter::class)
	@Column(nullable = false)
	var mode: OutputType = OutputType.VIDEO,

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	val updatedAt: Instant? = null,
)
