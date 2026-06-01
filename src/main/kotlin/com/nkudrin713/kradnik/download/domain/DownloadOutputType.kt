package com.nkudrin713.kradnik.download.domain

enum class DownloadOutputType(val dbValue: String) {
	VIDEO("video"),
	AUDIO("audio");

	companion object {
		fun fromDb(value: String): DownloadOutputType =
			entries.firstOrNull { it.dbValue == value }
				?: throw IllegalArgumentException("Unknown download output type: $value")
	}
}
