package com.nkudrin713.kradnik.settings

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DownloadSettingsService(
	private val downloadSettingsRepository: DownloadSettingsRepository,
) {
	@Transactional(readOnly = true)
	fun getMode(chatId: Long): DownloadMode =
		downloadSettingsRepository.findByChatId(chatId)?.mode ?: DownloadMode.ASK

	@Transactional
	fun replaceModeMenu(
		chatId: Long,
		messageId: Int,
	): Int? {
		val settings = getOrCreate(chatId)
		val previousMessageId = settings.modeMenuMessageId
		settings.modeMenuMessageId = messageId
		return previousMessageId
	}

	@Transactional
	fun selectMode(
		chatId: Long,
		menuMessageId: Int,
		mode: DownloadMode,
	): Boolean {
		val settings = downloadSettingsRepository.findByChatId(chatId) ?: return false
		if (settings.modeMenuMessageId != menuMessageId) {
			return false
		}

		settings.mode = mode
		settings.modeMenuMessageId = null
		return true
	}

	private fun getOrCreate(chatId: Long): DownloadSettings {
		return downloadSettingsRepository.findByChatId(chatId)
			?: downloadSettingsRepository.save(DownloadSettings(chatId = chatId))
	}
}
