package com.nkudrin713.kradnik.download.domain

enum class OutputType(val dbValue: String) {
	VIDEO("video"),
	AUDIO("audio"),
	COVER("cover");

	companion object {
		fun fromDb(value: String): OutputType =
			entries.firstOrNull { it.dbValue == value }
					?: throw IllegalArgumentException("Unknown output type: $value")
	}
}
