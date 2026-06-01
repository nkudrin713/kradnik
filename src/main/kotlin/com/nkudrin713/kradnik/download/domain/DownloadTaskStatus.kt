package com.nkudrin713.kradnik.download.domain

enum class DownloadTaskStatus(val dbValue: String) {
	QUEUED("queued"),
	PROCESSING("processing"),
	UPLOADING("uploading"),
	COMPLETED("completed"),
	FAILED("failed");

	companion object {
		fun fromDb(value: String): DownloadTaskStatus =
			entries.firstOrNull { it.dbValue == value }
				?: throw IllegalArgumentException("Unknown download task status: $value")
	}
}
