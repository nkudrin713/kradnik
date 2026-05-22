package com.nkudrin713.kradnik.settings

import org.springframework.data.jpa.repository.JpaRepository

interface DownloadSettingRepository : JpaRepository<DownloadSetting, Long> {
	fun findByChatId(chatId: Long): DownloadSetting?
}
