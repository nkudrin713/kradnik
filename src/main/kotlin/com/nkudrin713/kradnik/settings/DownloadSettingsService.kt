package com.nkudrin713.kradnik.settings

import com.nkudrin713.kradnik.download.domain.OutputType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DownloadSettingsService(
	private val downloadSettingsRepository: DownloadSettingsRepository,
) {
	@Transactional(readOnly = true)
	fun getOutputType(chatId: Long): OutputType =
		downloadSettingsRepository.findByChatId(chatId)?.mode ?: OutputType.VIDEO

	@Transactional
	fun setMode(dto: DownloadSettingsDto): DownloadSettings {
		val mode = OutputType.fromDb(dto.mode)
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
