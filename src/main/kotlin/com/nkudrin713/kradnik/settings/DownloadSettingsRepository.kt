package com.nkudrin713.kradnik.settings

import org.springframework.data.jpa.repository.JpaRepository

interface DownloadSettingsRepository : JpaRepository<DownloadSettings, Long> {
	fun findByChatId(chatId: Long): DownloadSettings?
}
