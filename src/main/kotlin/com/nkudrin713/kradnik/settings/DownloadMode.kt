package com.nkudrin713.kradnik.settings

enum class DownloadMode(val dbValue: String) {
	VIDEO("video"),
	AUDIO("audio");

	companion object {
		fun fromDb(value: String): DownloadMode =
			entries.firstOrNull { it.dbValue == value }
				?: throw IllegalArgumentException("Unknown download mode: $value")
	}
}
