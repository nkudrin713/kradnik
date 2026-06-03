package com.nkudrin713.kradnik.download.domain

enum class OutputType(val dbValue: String) {
	VIDEO("video"),
	AUDIO("audio");

	companion object {
		fun fromDb(value: String): OutputType =
			entries.firstOrNull { it.dbValue == value }
				?: throw IllegalArgumentException("Unknown download mode: $value")
	}
}
