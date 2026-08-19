package com.nkudrin713.kradnik.settings

import com.nkudrin713.kradnik.download.domain.OutputType

enum class DownloadMode(
	val dbValue: String,
	val displayName: String,
	val outputType: OutputType?,
) {
	VIDEO(
		dbValue = "video",
		displayName = "Видео",
		outputType = OutputType.VIDEO,
	),
	AUDIO(
		dbValue = "audio",
		displayName = "Звук",
		outputType = OutputType.AUDIO,
	),
	ASK(
		dbValue = "ask",
		displayName = "Спрашивать",
		outputType = null,
	);

	companion object {
		fun fromDb(value: String): DownloadMode =
			entries.firstOrNull { it.dbValue == value }
				?: throw IllegalArgumentException("Unknown download mode: $value")
	}
}
