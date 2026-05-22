package com.nkudrin713.kradnik.settings

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DownloadSettingService(
	private val downloadSettingRepository: DownloadSettingRepository,
) {
	@Transactional(readOnly = true)
	fun getMode(chatId: Long): DownloadMode =
		downloadSettingRepository.findByChatId(chatId)?.mode ?: DownloadMode.VIDEO

	@Transactional
	fun setMode(dto: DownloadSettingDto): DownloadSetting {
		val mode = DownloadMode.valueOf(dto.mode)
		val setting = downloadSettingRepository.findByChatId(dto.chatId)
			?: return downloadSettingRepository.save(
				DownloadSetting(
					chatId = dto.chatId,
					mode = mode,
				)
			)

		setting.mode = mode
		return setting
	}
}

data class DownloadSettingDto(
	val chatId: Long,
	val mode: String,
)
