package com.nkudrin713.kradnik.settings

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DownloadSettingsService(
	private val downloadSettingsRepository: DownloadSettingsRepository,
) {
	@Transactional(readOnly = true)
	fun getMode(chatId: Long): DownloadMode =
		downloadSettingsRepository.findByChatId(chatId)?.mode ?: DownloadMode.AUDIO

	@Transactional
	fun setMode(dto: DownloadSettingsDto): DownloadSettings {
		val mode = DownloadMode.valueOf(dto.mode)
		val settings = downloadSettingsRepository.findByChatId(dto.chatId)
			?: return downloadSettingsRepository.save(
				DownloadSettings(
					chatId = dto.chatId,
					mode = mode,
				)
			)

		settings.mode = mode
		return settings
	}
}

data class DownloadSettingsDto(
	val chatId: Long,
	val mode: String,
)
